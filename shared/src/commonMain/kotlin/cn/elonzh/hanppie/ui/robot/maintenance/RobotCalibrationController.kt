@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.robot.maintenance

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.session.RobotTarget
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.robot.device.robotRequestMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong

internal data class CalibrationRecovery(val target: RobotTarget, val previous: RobotCalibrationState)
internal data class CalibrationUiState(val connection: Long = 0, val connected: Boolean = false,
    val task: RobotCalibrationState = RobotCalibrationState(), val working: Boolean = false, val canceling: Boolean = false,
    val autoContinue: Boolean = false, val recovery: CalibrationRecovery? = null, val canRecover: Boolean = false,
    val error: UiText? = null) {
    val busy: Boolean get() = working || task.busy
    val blocksControl: Boolean get() = busy || task.blocksControl || recovery != null
}

/** Owns UI intents and the original connection; native calibration state belongs to the session service. */
internal class RobotCalibrationController(private val scope: CoroutineScope,
    private val change: suspend (RobotCalibrationService, suspend () -> Unit) -> Unit,
    private val canContinue: (RobotCalibrationService) -> Boolean) {
    private class Binding(val service: RobotCalibrationService, val target: RobotTarget) { var recovering = false }
    @Volatile private var binding: Binding? = null
    private val revision = AtomicLong(0)
    private var job: Job? = null
    private var observer: Job? = null
    val state = MutableStateFlow(CalibrationUiState())

    init {
        scope.launch {
            state.collect { ui ->
                val current = binding
                if (current != null && ui.connected && ui.autoContinue && !ui.busy &&
                    ui.task.phase == CalibrationPhase.WAITING_LEVEL && ui.task.levelReady && canContinue(current.service)) {
                    stopAutomaticContinuation(ui.connection)
                    run(ui.connection) { it.service.startGimbal() }
                }
            }
        }
    }
    fun attach(service: RobotCalibrationService, target: RobotTarget) {
        detach()
        if (!scope.isActive) return
        val current = Binding(service, target); binding = current
        val recovery = state.value.recovery
        state.value = CalibrationUiState(connection = revision.load(), connected = true, task = service.state.value,
            recovery = recovery, canRecover = recovery?.target?.sameCalibrationRobot(target) == true)
        observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            service.state.collect { value ->
                state.update { ui ->
                    if (binding !== current) ui else {
                        val interruptedAgain = value.observingInterrupted && value.blocksControl && ui.recovery == null
                        if (interruptedAgain) current.recovering = true
                        val resolved = current.recovering && value.kind != null && !value.blocksControl && value.phase != CalibrationPhase.CLOSED
                        if (resolved) current.recovering = false
                        ui.copy(task = value, recovery = if (resolved) null else if (interruptedAgain) CalibrationRecovery(current.target, value) else ui.recovery,
                            canRecover = if (resolved) false else interruptedAgain || ui.canRecover,
                            autoContinue = ui.autoContinue && value.phase !in setOf(CalibrationPhase.UNCERTAIN, CalibrationPhase.CLOSED,
                                CalibrationPhase.SUCCEEDED, CalibrationPhase.FAILED, CalibrationPhase.CANCELED, CalibrationPhase.ARM_ACKNOWLEDGED))
                    }
                }
            }
        }
    }
    fun begin(kind: CalibrationKind, connection: Long = state.value.connection) {
        if (state.value.connection != connection) return
        if (state.value.recovery != null) { targetChanged(); return }
        run(connection) { current ->
            state.update { it.copy(autoContinue = kind == CalibrationKind.CHASSIS_AND_GIMBAL) }
            current.service.begin(kind)
        }
    }
    fun continueGimbal(connection: Long = state.value.connection) {
        if (state.value.connection != connection) return
        stopAutomaticContinuation(connection)
        run(connection) { it.service.startGimbal() }
    }
    fun retryArm(connection: Long = state.value.connection) = run(connection) { it.service.retryArm() }

    fun observePrevious(connection: Long = state.value.connection) {
        if (state.value.connection != connection) return
        val recovery = state.value.recovery ?: return
        if (!state.value.canRecover) { targetChanged(); return }
        run(connection) { current ->
            check(recovery.target.sameCalibrationRobot(current.target))
            current.recovering = true
            current.service.observeInterrupted(recovery.previous)
        }
    }
    fun confirmRestarted(connection: Long = state.value.connection) = run(connection) { current ->
        check(current.recovering && state.value.canRecover && state.value.recovery?.target?.sameCalibrationRobot(current.target) == true)
        current.service.confirmRestarted()
    }

    /** Cancels and joins an in-flight request before asking the same native task to stop its IMU. */
    fun cancel(connection: Long = state.value.connection, onCanceled: (() -> Unit)? = null) {
        if (state.value.connection != connection) return
        val current = binding ?: return
        if (!scope.isActive || state.value.canceling) return
        if (state.value.recovery != null && (!current.recovering || !state.value.canRecover)) { targetChanged(); return }
        stopAutomaticContinuation(connection)
        val previous = job; val token = revision.addAndFetch(1)
        state.update { it.copy(working = true, canceling = true, error = null) }
        val launched = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.cancelAndJoin(); currentCoroutineContext().ensureActive()
                change(current.service) { check(binding === current); current.service.cancel() }
                currentCoroutineContext().ensureActive()
                if (binding === current && revision.load() == token && !current.service.state.value.blocksControl) onCanceled?.invoke()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed(token, error) }
            finally { complete(token) }
        }
        if (revision.load() == token && launched.isActive) job = launched
    }
    fun stopAutomaticContinuation(connection: Long = state.value.connection) {
        if (state.value.connection == connection) state.update { it.copy(autoContinue = false) }
    }
    fun pageLeft(connection: Long = state.value.connection) = stopAutomaticContinuation(connection)

    private fun run(connection: Long, action: suspend (Binding) -> Unit) {
        val current = binding ?: return
        if (state.value.connection != connection || !scope.isActive || state.value.busy || job?.isActive == true) return
        val pending = state.value.recovery
        if (pending != null && (!pending.target.sameCalibrationRobot(current.target) ||
                current.recovering && current.service.state.value.kind != pending.previous.kind)) { targetChanged(); return }
        val token = revision.addAndFetch(1)
        state.update { it.copy(working = true, error = null) }
        val launched = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                change(current.service) { check(binding === current); action(current) }
                currentCoroutineContext().ensureActive()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed(token, error) }
            finally { complete(token) }
        }
        if (revision.load() == token && launched.isActive) job = launched
    }
    private fun targetChanged() { state.update { it.copy(error = uiText(Res.string.calibration_target_changed)) } }
    private fun failed(token: Long, error: Exception) {
        state.update { if (revision.load() == token) it.copy(autoContinue = false,
            error = robotRequestMessage(error, Res.string.calibration_request_failed)) else it }
    }
    private fun complete(token: Long) {
        if (revision.load() != token) return
        job = null
        state.update { if (revision.load() == token) it.copy(working = false, canceling = false) else it }
    }

    fun detach() {
        val old = binding
        revision.addAndFetch(1); binding = null; observer?.cancel(); observer = null; job?.cancel(); job = null
        val task = old?.service?.state?.value ?: state.value.task
        val interrupted = task.kind != null && (task.blocksControl || task.phase == CalibrationPhase.CLOSED && task.problem != null)
        val recovery = if (old != null && interrupted) CalibrationRecovery(old.target, task) else state.value.recovery
        state.value = CalibrationUiState(connection = revision.load(), task = task.copy(busy = false, levelReady = false,
            phase = if (interrupted) CalibrationPhase.CLOSED else task.phase,
            problem = if (interrupted) CalibrationProblem.REQUEST_UNKNOWN else task.problem), recovery = recovery)
    }
    fun clear() { detach(); state.value = CalibrationUiState(connection = revision.load()) }
}

private fun RobotTarget.sameCalibrationRobot(other: RobotTarget): Boolean = ip == other.ip && appId.equals(other.appId, ignoreCase = true) &&
    remotePort == other.remotePort && localIp == other.localIp
