@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.robot.device

import cn.elonzh.hanppie.resources.*
import kotlin.concurrent.Volatile
import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotSystemStatus
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.concurrent.atomics.AtomicLong

internal enum class PowerPhase { IDLE, READING, READY, WRITING, ACTION }
internal enum class PowerOutcome { ACKNOWLEDGED, UNCONFIRMED }
internal data class PowerResult(val action: RobotPowerAction, val outcome: PowerOutcome)
internal data class RobotPowerState(val connection: Long = 0, val connected: Boolean = false,
    val phase: PowerPhase = PowerPhase.IDLE, val policy: LowPowerPolicy? = null, val verified: Boolean = false,
    val report: RobotSystemStatus? = null, val result: PowerResult? = null, val error: UiText? = null) {
    val busy: Boolean get() = phase in setOf(PowerPhase.READING, PowerPhase.WRITING, PowerPhase.ACTION)
}

/** Policy belongs to the robot; the controller retains a possibly dispatched action across link loss. */
internal class RobotPowerController(private val scope: CoroutineScope,
    private val change: suspend (RobotPowerService, suspend () -> Unit) -> Unit,
    private val retire: (RobotPowerService) -> Unit) {
    private class Binding(val service: RobotPowerService)
    @Volatile private var binding: Binding? = null
    private val revision = AtomicLong(0)
    private var job: Job? = null
    private var observer: Job? = null
    val state = MutableStateFlow(RobotPowerState())

    fun attach(service: RobotPowerService, reports: StateFlow<RobotSystemStatus?>) {
        clear()
        if (!scope.isActive) return
        val current = Binding(service); binding = current
        state.value = RobotPowerState(connection = revision.load(), connected = true)
        observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            reports.collect { value -> state.update { if (binding === current) it.copy(report = value) else it } }
        }
    }
    fun refresh() {
        val current = binding ?: return
        launch(current, PowerPhase.READING) { token ->
            try {
                val policy = current.service.readPolicy(); currentCoroutineContext().ensureActive()
                update(token) { it.copy(phase = PowerPhase.READY, policy = policy, verified = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update(token) { it.copy(phase = PowerPhase.READY,
                error = robotRequestMessage(error, Res.string.robot_power_read_failed), verified = false) } }
        }
    }
    fun apply(policy: LowPowerPolicy) {
        val current = binding ?: return
        if (state.value.policy == null) return
        launch(current, PowerPhase.WRITING) { token ->
            var dispatched = false; var readback: LowPowerPolicy? = null
            try {
                change(current.service) {
                    current.service.setPolicy(policy) { dispatched = true }
                    readback = current.service.readPolicy()
                    if (readback != policy) throw RobotRequestException(RequestProblem.NOT_APPLIED)
                }
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(phase = PowerPhase.READY, policy = readback, verified = true) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update(token) { it.copy(phase = PowerPhase.READY,
                policy = if (dispatched) readback else it.policy, verified = false,
                error = uiText(when {
                    error is RobotRequestException && error.problem == RequestProblem.REJECTED -> Res.string.robot_power_rejected
                    !dispatched -> Res.string.robot_power_task_active
                    else -> Res.string.robot_power_policy_uncertain
                })) } }
        }
    }
    fun perform(action: RobotPowerAction) {
        val current = binding ?: return
        launch(current, PowerPhase.ACTION) { token ->
            var dispatched = false
            try {
                change(current.service) {
                    current.service.perform(action) {
                        dispatched = true
                        update(token) { it.copy(result = PowerResult(action, PowerOutcome.UNCONFIRMED)) }
                    }
                }
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(phase = PowerPhase.READY, result = PowerResult(action, PowerOutcome.ACKNOWLEDGED)) }
                if (revision.load() == token && action != RobotPowerAction.EXIT_STANDBY) retire(current.service)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val uncertain = dispatched && !(error is RobotRequestException && error.problem == RequestProblem.REJECTED)
                update(token) { it.copy(phase = PowerPhase.READY,
                    result = if (uncertain) PowerResult(action, PowerOutcome.UNCONFIRMED) else null,
                    error = if (uncertain) null else uiText(if (dispatched) Res.string.robot_power_rejected else Res.string.robot_power_task_active)) }
                if (revision.load() == token && uncertain) retire(current.service)
            }
        }
    }
    private fun launch(current: Binding, phase: PowerPhase, action: suspend (Long) -> Unit) {
        if (binding !== current || !scope.isActive || state.value.busy || job?.isActive == true) return
        val token = revision.addAndFetch(1)
        state.update { it.copy(phase = phase, error = null, result = null) }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) { action(token) }
    }
    private fun update(token: Long, transform: (RobotPowerState) -> RobotPowerState) {
        state.update { if (revision.load() == token) transform(it) else it }
    }
    fun cancelRead() {
        if (state.value.phase != PowerPhase.READING) return
        revision.addAndFetch(1); job?.cancel(); job = null
        state.update { it.copy(phase = PowerPhase.READY) }
    }
    fun detach() {
        binding = null; observer?.cancel(); observer = null
        if (state.value.result != null) state.update { it.copy(connected = false, policy = null, report = null, verified = false,
            phase = if (it.phase == PowerPhase.ACTION && job?.isActive != true) PowerPhase.READY else it.phase) }
        else clear()
    }
    fun clear() {
        revision.addAndFetch(1); binding = null; observer?.cancel(); observer = null; job?.cancel(); job = null
        state.value = RobotPowerState(connection = revision.load())
    }
}
