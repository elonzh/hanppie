package cn.elonzh.hanppie.robot.extensions

import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.robot.product.moduleIdentificationCommand
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ExtensionObservation { OFF, STARTING, ACTIVE, FAILED, CLOSED }
enum class ExtensionProblem { MODULES_UNKNOWN, MODULE_OFFLINE, REQUEST_REJECTED, REQUEST_UNKNOWN, INVALID_RESPONSE, DISCONNECTED }
class ExtensionOperationException(val problem: ExtensionProblem) : Exception("Extension operation: $problem")
data class RobotExtensionState(val online: Set<RobotComponent>? = null, val kind: ExtensionKind? = null,
    val observation: ExtensionObservation = ExtensionObservation.OFF, val busy: Boolean = false,
    val readings: Map<RobotComponent, ExtensionReading> = emptyMap(), val ageSeconds: Long? = null,
    val fresh: Boolean = false, val identifying: RobotComponent? = null, val problem: ExtensionProblem? = null)

interface RobotExtensionService {
    val state: StateFlow<RobotExtensionState>
    suspend fun setObserving(kind: ExtensionKind?)
    suspend fun identify(component: RobotComponent)
    fun stopIdentification()
}

/** The connection owns reports and lamp deadlines; the visible workflow owns subscription intent. */
internal class ExtensionCommands(private val lock: RobotLock, private val health: () -> RobotHealth,
    private val request: suspend (Int, Int, Int, ByteArray, () -> Unit) -> ByteArray,
    private val send: (Int, Int, Int, ByteArray) -> Unit,
    private val nowMillis: () -> Long = { robotNanoTime() / 1_000_000 },
) : RobotExtensionService {
    private val mutable = MutableStateFlow(RobotExtensionState())
    override val state = mutable.asStateFlow()
    private val operation = Mutex()
    private var closed = false
    private var generation = 0L
    private var subscribed: ExtensionKind? = null
    private var cleanupAllowed = true
    private var sampleAt: Long? = null
    private var identificationDeadline = 0L

    private fun ensureOpen(token: Long) {
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
        if (token != generation) throw CancellationException("Extension operation retired")
    }
    private fun checkOnline(component: RobotComponent? = null, kind: ExtensionKind? = null) {
        updateOnline()
        val online = state.value.online ?: throw ExtensionOperationException(ExtensionProblem.MODULES_UNKNOWN)
        if (component != null && component !in online || kind != null && online.none { kind.index(it) != null })
            throw ExtensionOperationException(ExtensionProblem.MODULE_OFFLINE)
    }
    private suspend fun command(token: Long, node: Int, set: Int, id: Int, payload: ByteArray, beforeSend: () -> Unit = {}) {
        val context = currentCoroutineContext()
        context.ensureActive(); lock.withLock { ensureOpen(token) }
        request(node, set, id, payload) { lock.withLock { context.ensureActive(); ensureOpen(token); beforeSend() } }
        context.ensureActive(); lock.withLock { ensureOpen(token) }
    }
    private suspend fun perform(action: suspend (Long) -> Unit) {
        check(operation.tryLock()) { "请等待扩展任务结束" }
        val token = lock.withLock {
            try { ensureOpen(generation); mutable.value = state.value.copy(busy = true, problem = null); generation }
            catch (error: Exception) { operation.unlock(); throw error }
        }
        try { action(token) }
        catch (error: Exception) {
            lock.withLock { if (!closed && token == generation && error !is CancellationException) mutable.value = state.value.copy(problem = when (error) {
                is ExtensionOperationException -> error.problem
                is RobotRequestException -> when (error.problem) {
                    RequestProblem.REJECTED -> ExtensionProblem.REQUEST_REJECTED
                    RequestProblem.INVALID_RESPONSE -> ExtensionProblem.INVALID_RESPONSE
                    RequestProblem.DISCONNECTED -> ExtensionProblem.DISCONNECTED
                    else -> ExtensionProblem.REQUEST_UNKNOWN
                }
                else -> ExtensionProblem.REQUEST_UNKNOWN
            }) }
            throw error
        } finally {
            lock.withLock {
                if (!closed) {
                    if (token != generation && cleanupAllowed) removeTopic()
                    mutable.value = state.value.copy(busy = false)
                }
            }
            operation.unlock()
        }
    }
    private fun clearSamples() {
        sampleAt = null; mutable.value = state.value.copy(readings = emptyMap(), ageSeconds = null, fresh = false)
    }
    private fun removeTopic(retainUntilRequestSettles: Boolean = false) {
        if (cleanupAllowed) subscribed?.topic()?.let { topic -> runCatching { send(9, 0x48, Protocol.CMD_VBUS_DEL_MSG, topic.remove()) } }
        if (!retainUntilRequestSettles) subscribed = null
    }
    override suspend fun setObserving(kind: ExtensionKind?) {
        if (kind == null) {
            retire()
            operation.withLock { lock.withLock { if (!closed) removeTopic() } }
            return
        }
        if (lock.withLock { ensureOpen(generation); state.value.kind == kind && state.value.observation == ExtensionObservation.ACTIVE }) return
        perform { token ->
            lock.withLock {
                cleanupAllowed = true
                checkOnline(kind = kind); stopIdentification(); removeTopic(); clearSamples()
                mutable.value = state.value.copy(kind = kind, observation = ExtensionObservation.STARTING)
                subscribed = kind
            }
            try {
                command(token, 9, 0x48, Protocol.CMD_VBUS_ADD_MSG, kind.topic().add()) { checkOnline(kind = kind) }
                lock.withLock { ensureOpen(token); clearSamples(); mutable.value = state.value.copy(observation = ExtensionObservation.ACTIVE) }
            } catch (error: Exception) {
                lock.withLock { if (!closed && token == generation) { removeTopic(); clearSamples(); mutable.value = state.value.copy(observation = ExtensionObservation.FAILED) } }
                throw error
            }
        }
    }
    override suspend fun identify(component: RobotComponent) = perform { token ->
        require(ExtensionKind.entries.any { it.index(component) != null })
        lock.withLock { checkOnline(component); stopIdentification() }
        val lamp = moduleIdentificationCommand(component, true)
        command(token, lamp.node, lamp.set, lamp.id, lamp.payload) {
            checkOnline(component)
            // A missing ACK may still have lit the module: retain ownership so cleanup still runs.
            mutable.value = state.value.copy(identifying = component); identificationDeadline = nowMillis() + 3_000
        }
    }
    override fun stopIdentification() = lock.withLock {
        state.value.identifying?.let { component ->
            val command = moduleIdentificationCommand(component, false)
            runCatching { send(command.node, command.set, command.id, command.payload) }
        }
        mutable.value = state.value.copy(identifying = null); identificationDeadline = 0
    }
    fun retireIdentification() = lock.withLock {
        mutable.value = state.value.copy(identifying = null); identificationDeadline = 0
    }
    /** Immediate local retirement, including an ADD/identification request still awaiting its ACK. */
    fun retire(sendCleanup: Boolean = true) = lock.withLock {
        if (closed) return@withLock
        generation++
        cleanupAllowed = sendCleanup
        if (sendCleanup) stopIdentification() else retireIdentification()
        // A dispatched ADD may take effect after this DEL. Its finally block repeats cleanup.
        if (sendCleanup) removeTopic(retainUntilRequestSettles = operation.isLocked)
        clearSamples(); mutable.value = state.value.copy(kind = null, observation = ExtensionObservation.OFF)
    }
    private fun updateOnline() {
        val online = health().modules?.mapNotNullTo(linkedSetOf()) { module ->
            RobotComponent.fromDeviceId(module.deviceId)?.takeIf { c -> ExtensionKind.entries.any { it.index(c) != null } }
        }
        if (online != state.value.online) {
            if (state.value.identifying !in online.orEmpty()) stopIdentification()
            mutable.value = state.value.copy(online = online, readings = state.value.readings.filterKeys { it in online.orEmpty() })
        }
    }
    private fun updateAge() {
        val age = sampleAt?.let { (nowMillis() - it).coerceAtLeast(0) }
        mutable.value = state.value.copy(ageSeconds = age?.div(1_000), fresh = state.value.observation == ExtensionObservation.ACTIVE &&
            state.value.readings.isNotEmpty() && age != null && age < 1_000)
    }
    fun receive(frame: DussFrame) = lock.withLock {
        if (closed) return@withLock
        updateOnline()
        if (state.value.observation == ExtensionObservation.ACTIVE) state.value.kind?.readings(frame)?.let { readings ->
            sampleAt = nowMillis()
            mutable.value = state.value.copy(readings = readings.filter { it.component in state.value.online.orEmpty() }.associateBy { it.component })
        }
        updateAge()
    }
    fun tick() = lock.withLock {
        if (closed) return@withLock
        updateOnline(); updateAge()
        if (state.value.identifying != null && nowMillis() >= identificationDeadline) stopIdentification()
    }
    fun close(sendCleanup: Boolean = true) = lock.withLock {
        if (closed) return@withLock
        retire(sendCleanup); if (sendCleanup) removeTopic()
        closed = true; mutable.value = RobotExtensionState(observation = ExtensionObservation.CLOSED)
    }
}
