package cn.elonzh.hanppie.robot.extensions.arm

import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.robot.product.moduleIdentificationCommand
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ArmObservation { OFF, STARTING, ACTIVE, FAILED, CLOSED }
enum class ArmProblem { MODULES_UNKNOWN, ARM_OFFLINE, CLAW_OFFLINE, SERVO_OFFLINE, BINDING_INVALID,
    BINDING_CHANGED, MODE_CHANGED, MODE_UNSUPPORTED, OBSERVATION_REQUIRED, REPORT_STALE,
    LEASE_EXPIRED, READBACK_MISMATCH, REQUEST_REJECTED, REQUEST_UNKNOWN, INVALID_RESPONSE, DISCONNECTED }
class ArmOperationException(val problem: ArmProblem) : Exception("Arm operation: $problem")

/** A lease is bound to both this service instance and one explicit input gesture. */
interface ArmControlLease
private class NativeArmControlLease(val owner: Any, val revision: Long) : ArmControlLease

data class RobotArmState(
    val observation: ArmObservation = ArmObservation.OFF, val busy: Boolean = false,
    val armOnline: Boolean = false, val clawOnline: Boolean = false, val servoIds: Set<Int>? = null,
    val binding: ArmServoBinding? = null, val requestedBinding: ArmServoBinding? = null,
    val bindingAcknowledged: Boolean? = null, val bindingMatchesRequest: Boolean? = null,
    val loadModeRaw: Int? = null, val loadModeSender: Int? = null, val loadModeAgeSeconds: Long? = null,
    val requestedLoadMode: ArmLoadMode? = null, val loadModeAcknowledged: Boolean? = null, val loadModeMatchesRequest: Boolean? = null,
    val position: ArmPosition? = null, val positionAgeSeconds: Long? = null, val positionFresh: Boolean = false,
    val clawStatusRaw: Int? = null, val clawAgeSeconds: Long? = null, val clawFresh: Boolean = false,
    val clawWarnings: ClawWarnings? = null, val clawWarningsAgeSeconds: Long? = null,
    val controlArmed: Boolean = false, val controlActive: Boolean = false, val problem: ArmProblem? = null,
    val identifyingServo: Int? = null,
)

interface RobotArmService {
    val state: StateFlow<RobotArmState>
    suspend fun setObserving(enabled: Boolean)
    suspend fun readBinding()
    suspend fun setBinding(binding: ArmServoBinding, expected: ArmServoBinding)
    suspend fun setLoadMode(mode: ArmLoadMode, expectedBinding: ArmServoBinding, expectedModeRaw: Int?)
    suspend fun identifyServo(id: Int)
    fun stopIdentification()
    fun beginControl(): ArmControlLease
    fun move(lease: ArmControlLease, x: Double, y: Double, claw: Double)
    fun stopControl()
}

/** Connection-owned state; UI owns intent, and AppSession supplies remote/maintenance admission. */
internal class ArmCommands(
    private val lock: RobotLock, private val health: () -> RobotHealth,
    private val request: suspend (Int, Int, Int, ByteArray, () -> Unit) -> ByteArray,
    private val send: (Int, Int, Int, ByteArray) -> Unit,
    private val nowMillis: () -> Long = { robotNanoTime() / 1_000_000 },
) : RobotArmService {
    private val mutable = MutableStateFlow(RobotArmState())
    override val state = mutable.asStateFlow()
    private val operation = Mutex()
    private val leaseOwner = Any()
    private var closed = false
    private var generation = 0L
    private var inputRevision = 0L
    private var inputDeadline = 0L
    private var input = Triple(0.0, 0.0, 0.0)
    private var mayHaveControl = false
    private var neutralRemaining = 0
    private var subscriptionsMayExist = false
    private var positionAt: Long? = null
    private var clawAt: Long? = null
    private var warningsAt: Long? = null
    private var modeAt: Long? = null
    private var modeWriteDispatched = false
    private var identifiedServo: Int? = null
    private var identificationDeadline = 0L

    private fun ensureOpen(token: Long) {
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
        if (token != generation) throw CancellationException("Arm operation retired")
    }
    private suspend fun command(token: Long, node: Int, set: Int, id: Int, payload: ByteArray = byteArrayOf(), beforeSend: () -> Unit = {}): ByteArray {
        val context = currentCoroutineContext()
        context.ensureActive(); lock.withLock { ensureOpen(token) }
        val result = request(node, set, id, payload) { lock.withLock { context.ensureActive(); ensureOpen(token); beforeSend() } }
        context.ensureActive(); lock.withLock { ensureOpen(token) }
        return result
    }
    private fun fail(problem: ArmProblem): Nothing = throw ArmOperationException(problem)
    private fun problem(error: Exception): ArmProblem = when (error) {
        is ArmOperationException -> error.problem
        is RobotRequestException -> when (error.problem) {
            RequestProblem.REJECTED -> ArmProblem.REQUEST_REJECTED
            RequestProblem.INVALID_RESPONSE -> ArmProblem.INVALID_RESPONSE
            RequestProblem.NOT_APPLIED -> ArmProblem.READBACK_MISMATCH
            RequestProblem.DISCONNECTED -> ArmProblem.DISCONNECTED
            RequestProblem.TIMED_OUT -> ArmProblem.REQUEST_UNKNOWN
        }
        else -> ArmProblem.REQUEST_UNKNOWN
    }
    private suspend fun perform(action: suspend (Long) -> Unit) {
        check(operation.tryLock()) { "请等待扩展任务结束" }
        val token = lock.withLock {
            try { ensureOpen(generation); updateOnline(); stopControl(); mutable.value = state.value.copy(busy = true, problem = null); generation }
            catch (error: Exception) { operation.unlock(); throw error }
        }
        try { action(token) }
        catch (error: Exception) {
            lock.withLock { if (!closed && token == generation) mutable.value = state.value.copy(problem = problem(error)) }
            throw error
        } finally {
            lock.withLock { if (!closed) mutable.value = state.value.copy(busy = false) }
            operation.unlock()
        }
    }
    private fun removeTopics() {
        if (!subscriptionsMayExist) return
        ArmTopic.entries.forEach { topic -> runCatching { send(9, 0x48, Protocol.CMD_VBUS_DEL_MSG, topic.remove()) } }
        subscriptionsMayExist = false
    }
    private fun clearSamples() {
        positionAt = null; clawAt = null; warningsAt = null
        mutable.value = state.value.copy(position = null, positionAgeSeconds = null, positionFresh = false,
            clawStatusRaw = null, clawAgeSeconds = null, clawFresh = false, clawWarnings = null, clawWarningsAgeSeconds = null)
    }
    override suspend fun setObserving(enabled: Boolean) {
        if (!enabled) {
            retireObservation()
            // Retirement above is immediate, even if an earlier ADD is still awaiting its reply.
            operation.withLock { lock.withLock { if (!closed) removeTopics() } }
            return
        }
        if (lock.withLock { ensureOpen(generation); state.value.observation == ArmObservation.ACTIVE }) return
        perform { token ->
            lock.withLock {
                mutable.value = state.value.copy(observation = ArmObservation.STARTING)
                clearSamples(); subscriptionsMayExist = true; removeTopics(); subscriptionsMayExist = true
            }
            try {
                for (topic in ArmTopic.entries) command(token, 9, 0x48, Protocol.CMD_VBUS_ADD_MSG, topic.add())
                lock.withLock { ensureOpen(token); clearSamples(); mutable.value = state.value.copy(observation = ArmObservation.ACTIVE) }
            } catch (error: Exception) {
                lock.withLock { if (!closed && token == generation) { removeTopics(); clearSamples(); mutable.value = state.value.copy(observation = ArmObservation.FAILED) } }
                throw error
            }
        }
    }
    /** Stop local consumption without writing to a device which may already be in its loader. */
    fun retireObservation() = lock.withLock {
        if (closed) return@withLock
        generation++; stopControl(); stopIdentification(); clearSamples()
        mutable.value = state.value.copy(observation = ArmObservation.OFF)
    }
    private suspend fun fetchBinding(token: Long): ArmServoBinding {
        val result = decodeArmBinding(command(token, 0xc3, 0x33, 0x68))
        lock.withLock {
            ensureOpen(token)
            mutable.value = state.value.copy(binding = result, bindingMatchesRequest = state.value.requestedBinding?.let { it == result })
        }
        return result
    }
    override suspend fun readBinding() = perform { fetchBinding(it) }
    private fun checkArm() { updateOnline(); if (!state.value.armOnline) fail(ArmProblem.ARM_OFFLINE) }
    override suspend fun setBinding(binding: ArmServoBinding, expected: ArmServoBinding) = perform { token ->
        require(binding.installed || binding.uninstalled)
        if (fetchBinding(token) != expected) fail(ArmProblem.BINDING_CHANGED)
        try {
            command(token, 0xc3, 0x33, 0x67, armBindingPayload(binding)) {
                updateOnline()
                val ids = state.value.servoIds ?: fail(ArmProblem.MODULES_UNKNOWN)
                if (binding.installed && !ids.containsAll(setOf(binding.main, binding.secondary))) fail(ArmProblem.SERVO_OFFLINE)
                if (binding.uninstalled) checkArm()
                mutable.value = state.value.copy(binding = null, requestedBinding = binding, bindingAcknowledged = null, bindingMatchesRequest = null)
            }
            lock.withLock { ensureOpen(token); mutable.value = state.value.copy(bindingAcknowledged = true) }
        } catch (error: RobotRequestException) {
            lock.withLock { if (!closed && token == generation) mutable.value = state.value.copy(binding = null, bindingAcknowledged = if (error.problem == RequestProblem.REJECTED) false else null) }
            throw error
        }
        if (fetchBinding(token) != binding) fail(ArmProblem.READBACK_MISMATCH)
    }
    override suspend fun setLoadMode(mode: ArmLoadMode, expectedBinding: ArmServoBinding, expectedModeRaw: Int?) = perform { token ->
        if (fetchBinding(token) != expectedBinding) fail(ArmProblem.BINDING_CHANGED)
        try {
            command(token, 3, 0x33, 0x21, byteArrayOf(mode.raw.toByte())) {
                checkArm()
                if (!expectedBinding.installed) fail(ArmProblem.BINDING_INVALID)
                if (state.value.loadModeRaw != expectedModeRaw) fail(ArmProblem.MODE_CHANGED)
                if (state.value.loadModeRaw == 255) fail(ArmProblem.MODE_UNSUPPORTED)
                modeWriteDispatched = true
                mutable.value = state.value.copy(requestedLoadMode = mode, loadModeAcknowledged = null, loadModeMatchesRequest = null)
            }
            lock.withLock { ensureOpen(token); mutable.value = state.value.copy(loadModeAcknowledged = true) }
        } catch (error: RobotRequestException) {
            lock.withLock { if (!closed && token == generation) mutable.value = state.value.copy(loadModeAcknowledged = if (error.problem == RequestProblem.REJECTED) false else null) }
            throw error
        }
    }
    override suspend fun identifyServo(id: Int) = perform { token ->
        require(id in 1..3)
        lock.withLock { stopIdentification() }
        val packet = moduleIdentificationCommand(checkNotNull(RobotComponent.fromDeviceId(0x0317 + id)), true)
        command(token, packet.node, packet.set, packet.id, packet.payload) {
            updateOnline()
            if (id !in state.value.servoIds.orEmpty()) fail(ArmProblem.SERVO_OFFLINE)
            identifiedServo = id; identificationDeadline = nowMillis() + 3_000
            mutable.value = state.value.copy(identifyingServo = id)
        }
    }
    override fun stopIdentification() = lock.withLock {
        val id = identifiedServo ?: return@withLock
        retireIdentification()
        val packet = moduleIdentificationCommand(checkNotNull(RobotComponent.fromDeviceId(0x0317 + id)), false)
        runCatching { send(packet.node, packet.set, packet.id, packet.payload) }
            .onFailure { if (!closed) mutable.value = state.value.copy(problem = ArmProblem.REQUEST_UNKNOWN) }
    }
    fun retireIdentification() = lock.withLock {
        identifiedServo = null; identificationDeadline = 0
        mutable.value = state.value.copy(identifyingServo = null)
    }
    override fun beginControl(): ArmControlLease = lock.withLock {
        ensureOpen(generation); updateOnline(); stopControl()
        if (state.value.busy || state.value.observation != ArmObservation.ACTIVE) fail(ArmProblem.OBSERVATION_REQUIRED)
        if (!state.value.armOnline && !state.value.clawOnline) fail(ArmProblem.MODULES_UNKNOWN)
        inputDeadline = nowMillis() + 250
        mutable.value = state.value.copy(controlArmed = true, problem = null)
        NativeArmControlLease(leaseOwner, inputRevision)
    }
    override fun move(lease: ArmControlLease, x: Double, y: Double, claw: Double) = lock.withLock {
        try {
            ensureOpen(generation)
            require(listOf(x, y, claw).all { it.isFinite() && it in -1.0..1.0 })
            if (lease !is NativeArmControlLease || lease.owner !== leaseOwner || lease.revision != inputRevision || !state.value.controlArmed || nowMillis() >= inputDeadline) fail(ArmProblem.LEASE_EXPIRED)
            updateOnline(); updateAge()
            if (x != 0.0 || y != 0.0) {
                checkArm()
                if (state.value.binding?.installed != true) fail(ArmProblem.BINDING_INVALID)
                if (!state.value.positionFresh) fail(ArmProblem.REPORT_STALE)
            }
            if (claw != 0.0) {
                if (!state.value.clawOnline) fail(ArmProblem.CLAW_OFFLINE)
                if (!state.value.clawFresh) fail(ArmProblem.REPORT_STALE)
            }
            input = Triple(x, y, claw); inputDeadline = nowMillis() + 250
            mutable.value = state.value.copy(controlActive = x != 0.0 || y != 0.0 || claw != 0.0)
            if (!state.value.controlActive) stopControl()
        } catch (error: Exception) { stopControl(); if (!closed) mutable.value = state.value.copy(problem = problem(error)); throw error }
    }
    override fun stopControl() = lock.withLock {
        inputRevision++; inputDeadline = 0; input = Triple(0.0, 0.0, 0.0)
        mutable.value = state.value.copy(controlArmed = false, controlActive = false)
        if (mayHaveControl) {
            neutralRemaining = 3
            runCatching { sendNeutral() }.onFailure { mutable.value = state.value.copy(problem = ArmProblem.REQUEST_UNKNOWN) }
        }
    }
    private fun sendNeutral() {
        if (neutralRemaining == 0) return
        send(3, 0x33, 0x10, armControlPayload(0.0, 0.0, 0.0, false, false))
        neutralRemaining--; if (neutralRemaining == 0) mayHaveControl = false
    }
    private fun updateOnline() {
        val modules = health().modules?.map { it.deviceId }?.toSet()
        val arm = modules?.contains(RobotComponent.ARM.deviceId) == true
        val claw = modules?.contains(RobotComponent.CLAW.deviceId) == true
        val servos = modules?.let { ids -> (1..3).filterTo(linkedSetOf()) { 0x0317 + it in ids } }
        val previous = state.value
        if (previous.armOnline != arm || previous.clawOnline != claw || previous.servoIds != servos) {
            stopControl()
            if (!arm) { positionAt = null; modeAt = null; modeWriteDispatched = false }
            if (!claw) { clawAt = null; warningsAt = null }
            mutable.value = state.value.copy(armOnline = arm, clawOnline = claw, servoIds = servos,
                binding = state.value.binding.takeIf { previous.armOnline == arm },
                position = state.value.position.takeIf { arm }, loadModeRaw = state.value.loadModeRaw.takeIf { arm },
                loadModeSender = state.value.loadModeSender.takeIf { arm },
                clawStatusRaw = state.value.clawStatusRaw.takeIf { claw }, clawWarnings = state.value.clawWarnings.takeIf { claw })
        }
    }
    private fun updateAge() {
        val now = nowMillis()
        fun age(at: Long?) = at?.let { ((now - it) / 1_000).coerceAtLeast(0) }
        val active = state.value.observation == ArmObservation.ACTIVE
        mutable.value = state.value.copy(positionAgeSeconds = age(positionAt), clawAgeSeconds = age(clawAt),
            loadModeAgeSeconds = age(modeAt), clawWarningsAgeSeconds = age(warningsAt),
            positionFresh = active && positionAt?.let { now - it < 500 } == true,
            clawFresh = active && clawAt?.let { now - it < 1_000 } == true)
    }
    fun receive(frame: DussFrame) = lock.withLock {
        if (closed) return@withLock
        updateOnline()
        if (state.value.armOnline) armNativePush(frame, 0x22)?.let { raw ->
            modeAt = nowMillis()
            mutable.value = state.value.copy(loadModeRaw = raw, loadModeSender = frame.sender,
                loadModeMatchesRequest = if (modeWriteDispatched) state.value.requestedLoadMode?.let { it.raw == raw } else state.value.loadModeMatchesRequest)
        }
        if (state.value.clawOnline) armNativePush(frame, 3)?.let { raw ->
            warningsAt = nowMillis(); mutable.value = state.value.copy(clawWarnings = ClawWarnings(raw, frame.sender))
        }
        if (state.value.observation == ArmObservation.ACTIVE) {
            if (state.value.armOnline) armPosition(frame)?.let { report -> positionAt = nowMillis(); mutable.value = state.value.copy(position = report) }
            if (state.value.clawOnline) ArmTopic.CLAW.data(frame)?.let { p -> clawAt = nowMillis(); mutable.value = state.value.copy(clawStatusRaw = p.u8(0)) }
        }
        updateAge()
    }
    fun tick(allowed: Boolean, linkFresh: Boolean) = lock.withLock {
        if (closed) return@withLock
        updateOnline(); updateAge()
        identifiedServo?.let { if (nowMillis() >= identificationDeadline || it !in state.value.servoIds.orEmpty()) stopIdentification() }
        val armMoving = input.first != 0.0 || input.second != 0.0
        val clawMoving = input.third != 0.0
        if (state.value.controlArmed && (!allowed || !linkFresh || nowMillis() >= inputDeadline ||
                armMoving && !state.value.positionFresh || clawMoving && !state.value.clawFresh)) stopControl()
        if (state.value.controlActive) {
            mayHaveControl = true; neutralRemaining = 0
            send(3, 0x33, 0x10, armControlPayload(input.first, input.second, input.third, armMoving, clawMoving))
        } else sendNeutral()
    }
    fun close(sendCleanup: Boolean = true) = lock.withLock {
        if (closed) return@withLock
        stopControl(); repeat(3) { runCatching { sendNeutral() } }
        if (sendCleanup) stopIdentification() else retireIdentification()
        if (sendCleanup) removeTopics()
        closed = true; generation++
        mutable.value = RobotArmState(observation = ArmObservation.CLOSED)
    }
}
