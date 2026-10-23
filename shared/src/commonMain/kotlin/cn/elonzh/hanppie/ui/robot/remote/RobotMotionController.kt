@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.remote.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong

internal enum class MotionSettingsPhase { IDLE, READING, READY, WRITING }
internal data class MotionSettingsState(val connection: Long = 0, val connected: Boolean = false,
    val phase: MotionSettingsPhase = MotionSettingsPhase.IDLE, val configuration: RobotMotionConfiguration? = null,
    val confirmed: Boolean = false, val uncertain: Boolean = false, val error: UiText? = null) {
    val busy: Boolean get() = phase == MotionSettingsPhase.READING || phase == MotionSettingsPhase.WRITING
}

/** Device snapshots never become host preferences or reconnect-time writes. */
internal class RobotMotionController(private val scope: CoroutineScope,
    private val access: suspend (RobotMotionService, suspend () -> Unit) -> Unit) {
    private class Binding(val service: RobotMotionService)
    @Volatile private var binding: Binding? = null
    private val revision = AtomicLong(0)
    private var job: Job? = null
    private var dispatched = false
    val state = MutableStateFlow(MotionSettingsState())

    fun attach(service: RobotMotionService) {
        clear()
        if (!scope.isActive) return
        binding = Binding(service)
        state.value = MotionSettingsState(connection = revision.load(), connected = true)
    }

    fun refresh() = operate(MotionSettingsPhase.READING) { service, _ -> service.read() }

    fun setSpeedLevel(level: Int) {
        require(level in 1..4)
        if (state.value.configuration == null || state.value.uncertain) return
        operate(MotionSettingsPhase.WRITING) { service, onDispatch -> service.setSpeedLevel(level, onDispatch) }
    }

    fun setParameter(parameter: ChassisParameter, value: Float) {
        val snapshot = state.value.configuration ?: return
        if (snapshot.speedLevelRaw != 4 || snapshot.parameters[parameter]?.range?.accepts(value) != true) return
        operate(MotionSettingsPhase.WRITING) { service, onDispatch ->
            val actual = service.setParameter(parameter, value, onDispatch)
            snapshot.copy(parameters = snapshot.parameters + (parameter to actual))
        }
    }

    private fun operate(phase: MotionSettingsPhase,
        operation: suspend (RobotMotionService, () -> Unit) -> RobotMotionConfiguration) {
        val current = binding ?: return
        if (!scope.isActive || state.value.busy || job?.isActive == true) return
        val token = revision.addAndFetch(1)
        dispatched = false
        state.update { it.copy(phase = phase, error = null, confirmed = false, uncertain = false,
            configuration = if (phase == MotionSettingsPhase.READING) null else it.configuration) }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var sent = false
            try {
                var result: RobotMotionConfiguration? = null
                access(current.service) { result = operation(current.service) {
                    sent = true
                    if (revision.load() == token && binding === current) dispatched = true
                } }
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(phase = MotionSettingsPhase.READY, configuration = checkNotNull(result),
                    confirmed = phase == MotionSettingsPhase.WRITING) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val unknown = sent && !(error is RobotRequestException && error.problem == RequestProblem.REJECTED)
                update(token) { it.copy(phase = MotionSettingsPhase.READY, confirmed = false, uncertain = unknown,
                    configuration = if (unknown) null else it.configuration,
                    error = uiText(when {
                        unknown -> Res.string.motion_write_uncertain
                        error is RobotRequestException && error.problem == RequestProblem.REJECTED -> Res.string.motion_rejected
                        error is RobotRequestException && error.problem == RequestProblem.NOT_APPLIED -> Res.string.motion_not_applied
                        phase == MotionSettingsPhase.READING -> Res.string.motion_read_failed
                        else -> Res.string.motion_task_active
                    })) }
            }
        }
    }

    private fun update(token: Long, transform: (MotionSettingsState) -> MotionSettingsState) {
        state.update { if (revision.load() == token) transform(it) else it }
    }

    fun cancelRead() {
        if (state.value.phase != MotionSettingsPhase.READING) return
        revision.addAndFetch(1); job?.cancel(); job = null
        state.update { it.copy(phase = MotionSettingsPhase.READY, configuration = null) }
    }

    fun clear() {
        revision.addAndFetch(1); binding = null; job?.cancel(); job = null
        val uncertain = state.value.uncertain || state.value.phase == MotionSettingsPhase.WRITING && dispatched
        dispatched = false
        state.value = MotionSettingsState(connection = revision.load(), uncertain = uncertain,
            error = if (uncertain) uiText(Res.string.motion_write_uncertain) else null)
    }
}
