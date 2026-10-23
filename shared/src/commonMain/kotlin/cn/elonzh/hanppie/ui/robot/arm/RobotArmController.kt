package cn.elonzh.hanppie.ui.robot.arm

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.extensions.arm.*
import cn.elonzh.hanppie.robot.session.RobotLock
import cn.elonzh.hanppie.robot.session.RobotTarget
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.robot.device.robotRequestMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal enum class ArmDirection(val x: Double = 0.0, val y: Double = 0.0, val claw: Double = 0.0) {
    // Ordinary App prefab places Open on the positive end of ControlSliderClaw.
    FORWARD(x = 1.0), BACKWARD(x = -1.0), UP(y = 1.0), DOWN(y = -1.0), OPEN(claw = 1.0), CLOSE(claw = -1.0),
}
internal enum class ArmUiOperation { OBSERVE, READ, WRITE, IDENTIFY }
internal data class ArmUiState(val connection: Long = 0, val connected: Boolean = false,
    val task: RobotArmState = RobotArmState(), val operation: ArmUiOperation? = null,
    val error: UiText? = null, val interruptedAddress: String? = null, val held: Set<ArmDirection> = emptySet(), val pausing: Boolean = false) {
    val working: Boolean get() = operation != null
    val busy: Boolean get() = working || pausing || task.busy
    val changing: Boolean get() = operation == ArmUiOperation.WRITE || operation == ArmUiOperation.IDENTIFY
}

/** Navigation owns visibility, this controller owns intent, and the connection owns native state. */
internal class RobotArmController(private val scope: CoroutineScope,
    private val change: suspend (RobotArmService, suspend () -> Unit) -> Unit,
    private val canObserve: (RobotArmService) -> Boolean,
    private val canHold: (RobotArmService) -> Boolean,
) {
    private class Binding(val service: RobotArmService, val target: RobotTarget)
    private val lock = RobotLock()
    private var binding: Binding? = null
    private var connection = 0L
    private var revision = 0L
    private var active = false
    private var observationAttempted = false
    private var job: Job? = null
    private var cleanup: Job? = null
    private var cleanupBinding: Binding? = null
    private var observer: Job? = null
    private var inputJob: Job? = null
    private var inputLease: ArmControlLease? = null
    private var holdRevision = 0L
    private val holds = linkedMapOf<Long, ArmDirection>()
    val state = MutableStateFlow(ArmUiState())

    fun attach(service: RobotArmService, target: RobotTarget) = lock.withLock {
        detach()
        if (!scope.isActive) return@withLock
        val current = Binding(service, target); binding = current; observationAttempted = false
        state.value = ArmUiState(connection = connection, connected = true, task = service.state.value,
            interruptedAddress = state.value.interruptedAddress)
        observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            service.state.collect { value -> lock.withLock {
                if (binding !== current) return@withLock
                if (value.observation == ArmObservation.CLOSED) rememberUnverified(current)
                state.value = state.value.copy(task = value)
                if (!value.controlArmed && inputLease != null) stopControl()
            } }
        }
        reconcile()
    }
    fun setActive(value: Boolean) = lock.withLock {
        if (active != value) { active = value; observationAttempted = false }
        reconcile()
    }
    fun refreshVisibility() = lock.withLock { reconcile() }
    private fun reconcile() {
        val current = binding ?: return
        if (!active || !canObserve(current.service)) {
            if (job?.isActive == true || current.service.state.value.observation in setOf(ArmObservation.ACTIVE, ArmObservation.STARTING) || inputLease != null)
                deactivate(current)
            return
        }
        if (cleanup?.isActive == true || job?.isActive == true || observationAttempted) return
        observationAttempted = true
        run(ArmUiOperation.OBSERVE) {
            it.service.setObserving(true)
            if (it.service.state.value.armOnline || it.service.state.value.servoIds?.isNotEmpty() == true) it.service.readBinding()
        }
    }
    private fun rememberUnverified(current: Binding) {
        val task = state.value.task
        if (task.requestedBinding != null && task.bindingAcknowledged != false && task.bindingMatchesRequest != true ||
            task.requestedLoadMode != null && task.loadModeAcknowledged != false && task.loadModeMatchesRequest != true)
            state.value = state.value.copy(interruptedAddress = current.target.ip)
    }
    private fun deactivate(current: Binding) {
        val previous = job; previous?.cancel(); job = null
        revision++; val token = revision
        rememberUnverified(current); stopControl(); current.service.stopIdentification()
        state.value = state.value.copy(operation = null, pausing = true)
        if (cleanupBinding === current && cleanup?.isActive == true) return
        cleanupBinding = current
        val launched = scope.launch {
            try {
                previous?.cancelAndJoin()
                current.service.setObserving(false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { lock.withLock { if (binding === current && revision == token) state.value = state.value.copy(error = message(error)) } }
            finally { lock.withLock {
                if (cleanupBinding === current) {
                    cleanup = null; cleanupBinding = null
                    if (binding === current) {
                        state.value = state.value.copy(pausing = false)
                        if (active && canObserve(current.service)) { observationAttempted = false; reconcile() }
                    }
                }
            } }
        }
        if (launched.isActive) cleanup = launched
    }
    suspend fun pauseAndJoin() {
        setActive(false)
        val pending = lock.withLock { cleanup }
        pending?.join()
    }
    fun refresh(connection: Long = state.value.connection) = lock.withLock {
        if (state.value.connection != connection || state.value.busy) return@withLock
        val current = binding ?: return@withLock
        if (current.service.state.value.observation != ArmObservation.ACTIVE) {
            observationAttempted = false; reconcile()
        } else run(ArmUiOperation.READ) { it.service.readBinding() }
    }
    fun setBinding(value: ArmServoBinding, expected: ArmServoBinding, connection: Long = state.value.connection) = lock.withLock {
        if (!confirmed(connection, expected)) return@withLock
        run(ArmUiOperation.WRITE) { it.service.setBinding(value, expected) }
    }
    fun setLoadMode(value: ArmLoadMode, expectedBinding: ArmServoBinding, expectedMode: Int?, connection: Long = state.value.connection) = lock.withLock {
        if (!confirmed(connection, expectedBinding) || state.value.task.loadModeRaw != expectedMode) {
            state.value = state.value.copy(error = uiText(Res.string.arm_target_changed)); return@withLock
        }
        run(ArmUiOperation.WRITE) { it.service.setLoadMode(value, expectedBinding, expectedMode) }
    }
    private fun confirmed(connection: Long, expected: ArmServoBinding): Boolean {
        val valid = state.value.connection == connection && state.value.connected && state.value.task.binding == expected
        if (!valid) state.value = state.value.copy(error = uiText(Res.string.arm_target_changed))
        return valid
    }
    fun identifyServo(id: Int, connection: Long = state.value.connection) = lock.withLock {
        if (state.value.connection == connection) run(ArmUiOperation.IDENTIFY) { it.service.identifyServo(id) }
    }
    fun acknowledgePreviousChange() = lock.withLock { state.value = state.value.copy(interruptedAddress = null) }
    private fun run(kind: ArmUiOperation, action: suspend (Binding) -> Unit) {
        val current = binding ?: return
        if (!scope.isActive || !active || !canObserve(current.service) || state.value.busy || job?.isActive == true || cleanup?.isActive == true) return
        revision++; val token = revision
        stopControl(); state.value = state.value.copy(operation = kind, error = null)
        val launched = scope.launch {
            try {
                if (kind == ArmUiOperation.WRITE || kind == ArmUiOperation.IDENTIFY) change(current.service) {
                    lock.withLock { check(binding === current && active && revision == token && canObserve(current.service)) }
                    currentCoroutineContext().ensureActive(); action(current)
                } else action(current)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { lock.withLock { if (binding === current && revision == token) state.value = state.value.copy(error = message(error)) } }
            finally { lock.withLock { if (binding === current && revision == token) { state.value = state.value.copy(operation = null); job = null } } }
        }
        if (revision == token && launched.isActive) job = launched
    }

    fun press(direction: ArmDirection, connection: Long = state.value.connection): Long? = lock.withLock {
        val current = binding ?: return@withLock null
        if (state.value.connection != connection || !active || state.value.busy || !canHold(current.service)) return@withLock null
        try {
            if (inputLease == null) inputLease = current.service.beginControl()
            holdRevision++; val token = holdRevision; holds[token] = direction
            state.value = state.value.copy(held = holds.values.toSet(), error = null)
            sendInput(current)
            if (inputJob?.isActive != true) inputJob = scope.launch {
                while (isActive) {
                    delay(50)
                    val keepGoing = lock.withLock {
                        if (binding !== current || !active || !canHold(current.service) || holds.isEmpty()) { stopControl(); false }
                        else {
                        try { sendInput(current) }
                        catch (error: Exception) { stopControl(); state.value = state.value.copy(error = message(error)); return@withLock false }
                        inputLease != null
                        }
                    }
                    if (!keepGoing) return@launch
                }
            }
            token
        } catch (error: Exception) { stopControl(); state.value = state.value.copy(error = message(error)); null }
    }
    fun release(token: Long?) = lock.withLock {
        if (token == null || holds.remove(token) == null) return@withLock
        val current = binding
        if (holds.isEmpty() || current == null) stopControl()
        else try { sendInput(current); state.value = state.value.copy(held = holds.values.toSet()) }
        catch (error: Exception) { stopControl(); state.value = state.value.copy(error = message(error)) }
    }
    /** Accessibility activation is one bounded movement, never a latched hold. */
    fun pulse(direction: ArmDirection, connection: Long = state.value.connection) {
        val token = press(direction, connection) ?: return
        scope.launch { try { delay(150) } finally { release(token) } }
    }
    private fun sendInput(current: Binding) {
        val lease = inputLease ?: return
        fun axis(value: (ArmDirection) -> Double) = holds.values.sumOf(value).coerceIn(-1.0, 1.0)
        val x = axis { it.x }; val y = axis { it.y }; val claw = axis { it.claw }
        if (x == 0.0 && y == 0.0 && claw == 0.0) { stopControl(); return }
        current.service.move(lease, x, y, claw)
    }
    fun stopControl() = lock.withLock {
        holds.clear(); inputLease = null; inputJob?.cancel(); inputJob = null
        binding?.service?.stopControl()
        state.value = state.value.copy(held = emptySet())
    }
    fun detach() = lock.withLock {
        val previous = binding
        if (previous != null) { rememberUnverified(previous); stopControl(); previous.service.stopIdentification() }
        binding = null; connection++; revision++; observationAttempted = false
        observer?.cancel(); observer = null
        cleanup = null; cleanupBinding = null
        val pending = job; pending?.cancel(); job = null
        if (previous != null) scope.launch { pending?.cancelAndJoin(); runCatching { previous.service.setObserving(false) } }
        state.value = ArmUiState(connection = connection, interruptedAddress = state.value.interruptedAddress)
    }
    fun clear() = lock.withLock { active = false; detach(); state.value = state.value.copy(interruptedAddress = null) }
}

internal fun armProblemMessage(problem: ArmProblem): UiText = uiText(when (problem) {
    ArmProblem.MODULES_UNKNOWN -> Res.string.arm_modules_unknown
    ArmProblem.ARM_OFFLINE -> Res.string.arm_offline
    ArmProblem.CLAW_OFFLINE -> Res.string.arm_claw_offline
    ArmProblem.SERVO_OFFLINE -> Res.string.arm_servo_offline
    ArmProblem.BINDING_INVALID -> Res.string.arm_binding_invalid
    ArmProblem.BINDING_CHANGED, ArmProblem.MODE_CHANGED -> Res.string.arm_target_changed
    ArmProblem.MODE_UNSUPPORTED -> Res.string.arm_mode_unsupported
    ArmProblem.OBSERVATION_REQUIRED -> Res.string.arm_observation_required
    ArmProblem.REPORT_STALE -> Res.string.arm_report_stale
    ArmProblem.LEASE_EXPIRED -> Res.string.arm_hold_again
    ArmProblem.READBACK_MISMATCH -> Res.string.arm_readback_mismatch
    ArmProblem.REQUEST_REJECTED -> Res.string.arm_rejected
    ArmProblem.REQUEST_UNKNOWN -> Res.string.arm_result_unknown
    ArmProblem.INVALID_RESPONSE -> Res.string.arm_invalid_response
    ArmProblem.DISCONNECTED -> Res.string.arm_not_connected
})
private fun message(error: Exception): UiText = if (error is ArmOperationException) armProblemMessage(error.problem)
    else robotRequestMessage(error, Res.string.arm_task_active)
