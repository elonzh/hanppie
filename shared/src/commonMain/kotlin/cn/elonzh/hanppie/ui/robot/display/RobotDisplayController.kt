@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.robot.display

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.display.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong

internal enum class DisplayTarget { SIGHT, HEADLIGHTS }
internal data class DisplayDeviceState(
    val connection: Long = 0, val connected: Boolean = false,
    val target: DisplayTarget? = null, val writing: Boolean = false,
    val sight: SightCalibration? = null, val headlights: HeadlightBrightness? = null,
    val headlightsUnavailable: Boolean = false,
    val confirmed: DisplayTarget? = null, val uncertain: DisplayTarget? = null, val error: UiText? = null,
) { val busy: Boolean get() = target != null }

/** Connection snapshots and explicit device writes, separate from saved host preferences. */
internal class RobotDisplayController(private val scope: CoroutineScope,
    private val writeAccess: suspend (RobotDisplayService, suspend () -> Unit) -> Unit) {
    private class Binding(val service: RobotDisplayService)
    @Volatile private var binding: Binding? = null
    private val revision = AtomicLong(0)
    private var job: Job? = null
    private var dispatched = false
    val state = MutableStateFlow(DisplayDeviceState())

    fun attach(service: RobotDisplayService) {
        clear()
        if (!scope.isActive) return
        binding = Binding(service)
        state.value = DisplayDeviceState(connection = revision.load(), connected = true)
        // Read-only: may coexist with FPV. This never changes motion, zoom or firing policy.
        refreshSight()
    }

    fun refreshSight() = operate(DisplayTarget.SIGHT, false) { service, _ ->
        val value = service.readSight(); { it.copy(sight = value) }
    }

    fun refreshHeadlights() = operate(DisplayTarget.HEADLIGHTS, false) { service, _ ->
        val value = service.readHeadlights(); { it.copy(headlights = value, headlightsUnavailable = false) }
    }

    fun setSight(value: SightCalibration) {
        if (state.value.sight == null) return
        operate(DisplayTarget.SIGHT, true) { service, onDispatch ->
            val actual = service.setSight(value, onDispatch); { it.copy(sight = actual) }
        }
    }

    fun setHeadlights(percent: Int) {
        require(percent in 0..100)
        if (state.value.headlights == null) return
        operate(DisplayTarget.HEADLIGHTS, true) { service, onDispatch ->
            val actual = service.setHeadlights(percent, onDispatch); { it.copy(headlights = actual) }
        }
    }

    private fun operate(target: DisplayTarget, writing: Boolean,
        operation: suspend (RobotDisplayService, () -> Unit) -> (DisplayDeviceState) -> DisplayDeviceState) {
        val current = binding ?: return
        if (!scope.isActive || state.value.busy || job?.isActive == true) return
        val token = revision.addAndFetch(1)
        dispatched = false
        state.update { it.copy(target = target, writing = writing, confirmed = null, uncertain = null, error = null).let { next ->
            if (writing) next else next.without(target)
        } }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var sent = false
            try {
                var result: ((DisplayDeviceState) -> DisplayDeviceState)? = null
                val action: suspend () -> Unit = {
                    result = operation(current.service) {
                        sent = true
                        if (binding === current && revision.load() == token) dispatched = true
                    }
                }
                if (writing) writeAccess(current.service, action) else action()
                currentCoroutineContext().ensureActive()
                update(token) { checkNotNull(result)(it).copy(target = null, writing = false,
                    confirmed = if (writing) target else null) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val unknown = sent && !(error is RobotRequestException && error.problem == RequestProblem.REJECTED)
                val lightsUnavailable = target == DisplayTarget.HEADLIGHTS && !writing &&
                    error is RobotRequestException && error.problem == RequestProblem.REJECTED && error.responseCode == 2
                update(token) { old ->
                    (if (unknown || !writing) old.without(target) else old).copy(target = null, writing = false,
                        uncertain = if (unknown) target else null,
                        headlightsUnavailable = old.headlightsUnavailable || lightsUnavailable,
                        error = if (lightsUnavailable) null else uiText(when {
                            unknown -> Res.string.display_write_uncertain
                            error is RobotRequestException && error.problem == RequestProblem.REJECTED -> Res.string.display_rejected
                            error is RobotRequestException && error.problem == RequestProblem.NOT_APPLIED -> Res.string.display_not_applied
                            writing -> Res.string.display_task_active
                            else -> Res.string.display_read_failed
                        }))
                }
            }
        }
    }

    private fun update(token: Long, change: (DisplayDeviceState) -> DisplayDeviceState) {
        state.update { if (revision.load() == token) change(it) else it }
    }

    fun cancelRead() {
        if (!state.value.busy || state.value.writing) return
        revision.addAndFetch(1); job?.cancel(); job = null
        state.update { it.copy(target = null) }
    }

    fun clear() {
        revision.addAndFetch(1); binding = null; job?.cancel(); job = null
        val uncertain = if (state.value.writing && dispatched) state.value.target else state.value.uncertain
        dispatched = false
        state.value = DisplayDeviceState(connection = revision.load(), uncertain = uncertain,
            error = if (uncertain != null) uiText(Res.string.display_write_uncertain) else null)
    }
}

private fun DisplayDeviceState.without(target: DisplayTarget) = when (target) {
    DisplayTarget.SIGHT -> copy(sight = null)
    DisplayTarget.HEADLIGHTS -> copy(headlights = null)
}
