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

internal data class WifiDraft(val ssid: String, val password: String) {
    val valid: Boolean get() = RobotApCredentials.validSsid(ssid) && RobotApCredentials.validPassword(password)
    override fun toString() = "WifiDraft(<redacted>)"
}

internal enum class WifiPhase { IDLE, READING, EDITING, SAVING, READY, REBOOTING, NETWORK_REQUIRED, RECONNECTING, VERIFIED }
internal data class WifiRecovery(val origin: RobotTarget, val previous: RobotWifiProfile, val expected: RobotWifiProfile,
    val configurationRequested: Boolean)
internal data class RobotWifiState(
    val connection: Long = 0, val connected: Boolean = false, val phase: WifiPhase = WifiPhase.IDLE,
    val profile: RobotWifiProfile? = null, val draft: WifiDraft? = null,
    val confirmed: Set<WifiConfirmedField> = emptySet(), val recovery: WifiRecovery? = null,
    val restartAcknowledged: Boolean = false, val error: UiText? = null,
) {
    val busy: Boolean get() = phase in setOf(WifiPhase.READING, WifiPhase.SAVING, WifiPhase.REBOOTING, WifiPhase.RECONNECTING)
}

/** Connection-scoped queries; a user-initiated network handoff survives its own expected disconnect. */
internal class RobotWifiController(private val scope: CoroutineScope,
    private val change: suspend (RobotWifiService, suspend () -> Unit) -> Unit,
    private val retire: (RobotWifiService) -> Unit,
    private val reconnect: suspend (RobotTarget, RobotWifiProfile) -> RobotWifiProfile,
) {
    private class Binding(val service: RobotWifiService, val origin: RobotTarget)
    val state = MutableStateFlow(RobotWifiState())
    @Volatile private var binding: Binding? = null
    @Volatile private var job: Job? = null
    private val version = AtomicLong(0)
    private val connections = AtomicLong(0)

    fun attach(service: RobotWifiService, origin: RobotTarget) {
        val restoring = state.value.phase == WifiPhase.RECONNECTING && state.value.recovery != null
        if (!restoring) cancel()
        binding = Binding(service, origin)
        val connection = connections.addAndFetch(1)
        state.update { if (restoring) it.copy(connection = connection, connected = true)
            else RobotWifiState(connection = connection, connected = true, phase = WifiPhase.EDITING) }
    }

    fun edit(ssid: String, password: String) {
        if (!state.value.busy && state.value.connected && state.value.phase in setOf(WifiPhase.EDITING, WifiPhase.READY, WifiPhase.VERIFIED))
            state.update { it.copy(draft = WifiDraft(ssid, password), phase = WifiPhase.EDITING, error = null) }
    }

    fun refresh() {
        val current = binding ?: return
        launch(WifiPhase.READING, current) { token ->
            try {
                val profile = current.service.read()
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(profile = profile, phase = WifiPhase.EDITING,
                    draft = if (profile.accessPoint) it.draft ?: profile.password?.let { secret -> WifiDraft(profile.ssid, secret) } else null) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { update(token) { it.copy(profile = null, phase = WifiPhase.EDITING, error = uiText(Res.string.wifi_read_failed)) } }
        }
    }

    fun apply() {
        val current = binding ?: return
        val before = state.value
        val profile = before.profile?.takeIf { it.accessPoint } ?: return
        val draft = before.draft?.takeIf { it.valid } ?: return
        launch(WifiPhase.SAVING, current) { token ->
            try {
                change(current.service) {
                    update(token) { it.copy(confirmed = emptySet(), recovery = WifiRecovery(current.origin, profile,
                        RobotWifiProfile(0, draft.ssid, draft.password), true)) }
                    current.service.configure(RobotApCredentials(draft.ssid, draft.password)) { field ->
                        if (binding === current) update(token) { it.copy(confirmed = it.confirmed + field) }
                    }
                }
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(phase = WifiPhase.READY) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { update(token) { it.copy(phase = WifiPhase.EDITING, error = uiText(Res.string.wifi_save_uncertain)) } }
        }
    }

    fun reboot() {
        val current = binding ?: return
        val before = state.value
        val profile = before.profile ?: return
        val recovery = before.recovery ?: WifiRecovery(current.origin, profile, profile, false)
        if (recovery.configurationRequested && (before.phase != WifiPhase.READY || before.confirmed.size != 2)) return
        launch(WifiPhase.REBOOTING, current) { token ->
            try {
                change(current.service) {
                    update(token) { it.copy(recovery = recovery, restartAcknowledged = false) }
                    current.service.reboot()
                }
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(phase = WifiPhase.NETWORK_REQUIRED, restartAcknowledged = true) }
                retire(current.service)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (error is IllegalStateException || error is RobotRequestException && error.problem == RequestProblem.REJECTED) {
                    update(token) { it.copy(phase = if (recovery.configurationRequested) WifiPhase.READY else WifiPhase.EDITING,
                        recovery = recovery.takeIf { it.configurationRequested },
                        error = uiText(Res.string.wifi_reboot_rejected)) }
                } else {
                    update(token) { it.copy(phase = WifiPhase.NETWORK_REQUIRED, error = uiText(Res.string.wifi_reboot_uncertain)) }
                    if (version.load() == token) retire(current.service)
                }
            }
        }
    }

    fun reconnect() {
        val recovery = state.value.recovery ?: return
        if (state.value.phase != WifiPhase.NETWORK_REQUIRED) return
        launch(WifiPhase.RECONNECTING) { token ->
            try {
                val profile = reconnect(recovery.origin, recovery.expected)
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(phase = WifiPhase.VERIFIED, profile = profile, recovery = null,
                    draft = profile.password?.let { secret -> WifiDraft(profile.ssid, secret) }, confirmed = emptySet()) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { update(token) { it.copy(phase = WifiPhase.NETWORK_REQUIRED, error = uiText(Res.string.wifi_reconnect_failed)) } }
        }
    }

    fun stopWaiting() {
        val phase = state.value.phase
        if (phase !in setOf(WifiPhase.READING, WifiPhase.RECONNECTING)) return
        version.addAndFetch(1); job?.cancel(); job = null
        state.update { it.copy(phase = if (phase == WifiPhase.RECONNECTING) WifiPhase.NETWORK_REQUIRED else if (it.connected) WifiPhase.EDITING else WifiPhase.IDLE) }
    }

    private fun launch(phase: WifiPhase, current: Binding? = null, action: suspend (Long) -> Unit) {
        if (!scope.isActive || current != null && binding !== current || state.value.busy || job?.isActive == true) return
        val token = version.addAndFetch(1)
        state.update { it.copy(phase = phase, error = null) }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) { action(token) }
    }

    private fun update(token: Long, transform: (RobotWifiState) -> RobotWifiState) {
        state.update { if (version.load() == token) transform(it) else it }
    }

    fun detach(preserveRecovery: Boolean = true) {
        binding = null
        val before = state.value
        if (preserveRecovery && before.recovery != null) {
            if (before.phase == WifiPhase.RECONNECTING) {
                state.update { it.copy(connected = false, profile = null) }
                return
            }
            if (before.phase != WifiPhase.NETWORK_REQUIRED) {
                version.addAndFetch(1); job?.cancel(); job = null
            }
            state.update { it.copy(connected = false, profile = null, phase = WifiPhase.NETWORK_REQUIRED,
                error = if (before.phase == WifiPhase.NETWORK_REQUIRED) it.error else uiText(Res.string.wifi_connection_changed)) }
        } else cancel()
    }

    fun cancel() {
        version.addAndFetch(1); job?.cancel(); job = null
        state.value = RobotWifiState(connection = connections.addAndFetch(1), connected = binding != null,
            phase = if (binding != null) WifiPhase.EDITING else WifiPhase.IDLE)
    }

    fun close() { binding = null; cancel() }
}
