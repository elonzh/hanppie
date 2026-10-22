@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.robot.network

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong

internal enum class SdrPhase { IDLE, READING, READY, APPLYING, NETWORK_REQUIRED, RECONNECTING, VERIFIED }
internal data class SdrRecovery(val origin: RobotTarget, val change: SdrChange)
internal data class RobotSdrState(val connection: Long = 0, val connected: Boolean = false,
    val phase: SdrPhase = SdrPhase.IDLE, val settings: SdrSettings? = null,
    val recovery: SdrRecovery? = null, val error: UiText? = null, val verified: Boolean = false) {
    val busy: Boolean get() = phase in setOf(SdrPhase.READING, SdrPhase.APPLYING, SdrPhase.RECONNECTING)
    val visible: Boolean get() = settings != null || recovery != null
}

/** An SDR entry exists only after positive, connection-scoped mode and configuration evidence. */
internal class RobotSdrController(private val scope: CoroutineScope,
    private val change: suspend (RobotSdrService, suspend () -> Unit) -> Unit,
    private val retire: (RobotSdrService) -> Unit,
    private val reconnect: suspend (RobotTarget, SdrChange) -> Unit) {
    private class Binding(val service: RobotSdrService, val origin: RobotTarget)
    @Volatile private var binding: Binding? = null
    @Volatile private var job: Job? = null
    private val version = AtomicLong(0)
    private val connections = AtomicLong(0)
    val state = MutableStateFlow(RobotSdrState())

    fun attach(service: RobotSdrService, origin: RobotTarget, verified: SdrSettings? = null) {
        val restoring = state.value.phase == SdrPhase.RECONNECTING && state.value.recovery != null
        if (!restoring) clear()
        binding = Binding(service, origin)
        val connection = connections.addAndFetch(1)
        state.update { if (restoring) it.copy(connection = connection, connected = true, settings = verified)
            else RobotSdrState(connection, true, SdrPhase.READY, verified) }
        if (!restoring && verified == null) refresh()
    }

    fun refresh() {
        val current = binding ?: return
        if (state.value.recovery != null) return
        launch(SdrPhase.READING, current) { token ->
            try {
                val settings = current.service.read()
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(settings = settings, phase = SdrPhase.READY, verified = false) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { update(token) { it.copy(phase = SdrPhase.READY, error = uiText(Res.string.sdr_read_failed), verified = false) } }
        }
    }

    fun apply(target: SdrChange) {
        val current = binding ?: return
        if (state.value.settings == null || state.value.recovery != null) return
        launch(SdrPhase.APPLYING, current) { token ->
            var dispatched = false
            var acknowledged = false
            var readback: SdrSettings? = null
            var readCompleted = false
            try {
                change(current.service) {
                    current.service.set(target) {
                        dispatched = true
                        update(token) { it.copy(recovery = SdrRecovery(current.origin, target), verified = false) }
                    }
                    acknowledged = true
                    readback = current.service.read()
                    readCompleted = true
                    if (readback?.let(target::matches) != true) throw RobotRequestException(RequestProblem.NOT_APPLIED)
                }
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(settings = readback, phase = SdrPhase.READY, recovery = null, verified = true) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!dispatched || !acknowledged && error is RobotRequestException && error.problem == RequestProblem.REJECTED || readCompleted) {
                    update(token) { it.copy(phase = SdrPhase.READY, settings = if (readCompleted) readback else it.settings,
                        recovery = null, error = uiText(if (readCompleted) Res.string.sdr_not_applied else Res.string.sdr_rejected)) }
                } else {
                    update(token) { it.copy(phase = SdrPhase.NETWORK_REQUIRED, error = uiText(Res.string.sdr_uncertain)) }
                    if (version.load() == token) retire(current.service)
                }
            }
        }
    }

    fun reconnect() {
        val recovery = state.value.recovery ?: return
        if (state.value.phase != SdrPhase.NETWORK_REQUIRED) return
        launch(SdrPhase.RECONNECTING) { token ->
            try {
                reconnect(recovery.origin, recovery.change)
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(phase = SdrPhase.VERIFIED, recovery = null, verified = true) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { update(token) { it.copy(phase = SdrPhase.NETWORK_REQUIRED, error = uiText(Res.string.sdr_reconnect_failed)) } }
        }
    }

    fun stopWaiting() {
        val phase = state.value.phase
        if (phase !in setOf(SdrPhase.READING, SdrPhase.RECONNECTING)) return
        version.addAndFetch(1); job?.cancel(); job = null
        state.update { it.copy(phase = if (phase == SdrPhase.RECONNECTING) SdrPhase.NETWORK_REQUIRED else SdrPhase.READY) }
    }

    private fun launch(phase: SdrPhase, current: Binding? = null, action: suspend (Long) -> Unit) {
        if (!scope.isActive || current != null && binding !== current || state.value.busy || job?.isActive == true) return
        val token = version.addAndFetch(1)
        state.update { it.copy(phase = phase, error = null) }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) { action(token) }
    }
    private fun update(token: Long, action: (RobotSdrState) -> RobotSdrState) {
        state.update { if (version.load() == token) action(it) else it }
    }

    fun detach(preserveRecovery: Boolean = true) {
        binding = null
        if (preserveRecovery && state.value.recovery != null) {
            if (state.value.phase !in setOf(SdrPhase.RECONNECTING, SdrPhase.NETWORK_REQUIRED)) { version.addAndFetch(1); job?.cancel(); job = null }
            state.update { it.copy(connected = false, settings = null,
                phase = if (it.phase == SdrPhase.RECONNECTING) it.phase else SdrPhase.NETWORK_REQUIRED) }
        } else clear()
    }
    fun clear() {
        version.addAndFetch(1); job?.cancel(); job = null
        state.value = RobotSdrState(connection = connections.addAndFetch(1), connected = binding != null)
    }
    fun finish() { clear(); if (binding != null) refresh() }
    fun close() { binding = null; clear() }
}
