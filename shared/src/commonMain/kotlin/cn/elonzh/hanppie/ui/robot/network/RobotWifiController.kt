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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong

internal data class WifiDraft(val ssid: String, val password: String) {
    val valid: Boolean get() = RobotApCredentials.validSsid(ssid) && RobotApCredentials.validPassword(password)
    override fun toString() = "WifiDraft(<redacted>)"
}

internal fun RobotWifiProfile.apDraft(): WifiDraft? {
    val name = ssid ?: return null
    val secret = password ?: return null
    return if (accessPoint) WifiDraft(name, secret) else null
}

internal enum class WifiPhase { IDLE, READING, EDITING, SAVING, READY, REBOOTING, RADIO_CHANGING, NETWORK_REQUIRED, RECONNECTING, VERIFIED }
internal data class WifiRecovery(val origin: RobotTarget, val previous: RobotWifiProfile, val expected: RobotWifiProfile,
    val configurationRequested: Boolean, val expectedRadio: WifiRadioTarget? = null)
internal data class WifiVerification(val profile: RobotWifiProfile, val radio: WifiRadioSettings? = null)
internal data class RobotWifiState(
    val connection: Long = 0, val connected: Boolean = false, val phase: WifiPhase = WifiPhase.IDLE,
    val profile: RobotWifiProfile? = null, val draft: WifiDraft? = null,
    val confirmed: Set<WifiConfirmedField> = emptySet(), val recovery: WifiRecovery? = null,
    val restartAcknowledged: Boolean = false, val error: UiText? = null,
    val radio: WifiRadioSettings? = null, val signals: WifiSignals = WifiSignals(),
    val monitoring: Boolean = false, val monitorError: UiText? = null, val radioMessage: UiText? = null,
) {
    val busy: Boolean get() = phase in setOf(WifiPhase.READING, WifiPhase.SAVING, WifiPhase.REBOOTING, WifiPhase.RADIO_CHANGING, WifiPhase.RECONNECTING)
}

/** Connection-scoped queries; a user-initiated network handoff survives its own expected disconnect. */
internal class RobotWifiController(private val scope: CoroutineScope,
    private val change: suspend (RobotWifiService, suspend () -> Unit) -> Unit,
    private val retire: (RobotWifiService) -> Unit,
    private val reconnect: suspend (RobotTarget, RobotWifiProfile, WifiRadioTarget?) -> WifiVerification,
) {
    private class Binding(val service: RobotWifiService, val origin: RobotTarget)
    val state = MutableStateFlow(RobotWifiState())
    private val bindings = MutableStateFlow<Binding?>(null)
    private var binding: Binding?
        get() = bindings.value
        set(value) { bindings.value = value }
    @Volatile private var job: Job? = null
    private val version = AtomicLong(0)
    private val connections = AtomicLong(0)
    private val channelViewVisible = MutableStateFlow(false)
    private val monitorRevision = MutableStateFlow(0L)
    private val monitoringJob = scope.launch {
        bindings.collectLatest { current -> if (current != null) observeSignals(current) }
    }

    fun showChannels(visible: Boolean) { channelViewVisible.value = visible }
    fun retrySignals() { monitorRevision.update { it + 1 } }

    private suspend fun observeSignals(current: Binding) = coroutineScope {
        val observer = launch {
            current.service.signals.collect { value ->
                if (binding === current) state.update { it.copy(signals = value) }
            }
        }
        var mayBeEnabled = false
        try {
            combine(channelViewVisible, state.map { it.connected && it.profile?.accessPoint == true &&
                it.radio?.modeCode == 0 && it.radio.selectionCode == 1 && it.recovery == null }.distinctUntilChanged(),
                monitorRevision) { visible, allowed, revision -> (visible && allowed) to revision }
                .distinctUntilChanged().collect { (wanted, _) ->
                    if (!wanted && !mayBeEnabled) return@collect
                    if (wanted) mayBeEnabled = true
                    try {
                        current.service.setSignalReports(wanted)
                        currentCoroutineContext().ensureActive()
                        if (!wanted) mayBeEnabled = false
                        if (binding === current) state.update { it.copy(monitoring = wanted, monitorError = null) }
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { if (binding === current) state.update {
                        it.copy(monitoring = false, monitorError = uiText(Res.string.wifi_monitor_failed)) } }
                }
        } finally {
            observer.cancel()
            if (mayBeEnabled) withContext(NonCancellable) {
                withTimeoutOrNull(1_500) { runCatching { current.service.setSignalReports(false) } }
            }
        }
    }

    fun attach(service: RobotWifiService, origin: RobotTarget) {
        val restoring = state.value.phase == WifiPhase.RECONNECTING && state.value.recovery != null
        if (!restoring) cancel()
        binding = Binding(service, origin)
        val connection = connections.addAndFetch(1)
        state.update { if (restoring) it.copy(connection = connection, connected = true)
            else RobotWifiState(connection = connection, connected = true, phase = WifiPhase.EDITING) }
    }

    fun edit(ssid: String, password: String) {
        if (!state.value.busy && state.value.connected && state.value.profile?.accessPoint == true &&
            state.value.phase in setOf(WifiPhase.EDITING, WifiPhase.READY, WifiPhase.VERIFIED))
            state.update { it.copy(draft = WifiDraft(ssid, password), phase = WifiPhase.EDITING, error = null) }
    }

    fun refresh() {
        val current = binding ?: return
        launch(WifiPhase.READING, current) { token ->
            try {
                val profile = current.service.read()
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(profile = profile, phase = WifiPhase.EDITING,
                    draft = if (profile.accessPoint) it.draft ?: profile.apDraft() else null,
                    radio = it.radio?.takeIf { radio -> profile.hasWifiConfiguration && radio.modeCode == profile.modeCode }) }
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

    fun refreshRadio() {
        val current = binding ?: return
        if (state.value.recovery != null || state.value.profile?.hasWifiConfiguration != true) return
        launch(WifiPhase.READING, current) { token ->
            try {
                val radio = current.service.readRadio()
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(radio = radio, radioMessage = null, phase = WifiPhase.EDITING) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { update(token) { it.copy(radio = null, phase = WifiPhase.EDITING,
                error = uiText(Res.string.wifi_radio_read_failed)) } }
        }
    }

    fun setChannelMode(mode: WifiChannelMode) = changeRadio(WifiRadioTarget(mode)) { service, dispatch -> service.setChannelMode(mode, dispatch) }
    fun setChannel(channel: Int) {
        val radio = state.value.radio ?: return
        if (radio.selectionCode != 1 || channel !in radio.selectableChannels) return
        changeRadio(WifiRadioTarget(WifiChannelMode.MANUAL, channel)) { service, dispatch -> service.setChannel(channel, dispatch) }
    }

    private fun changeRadio(target: WifiRadioTarget, write: suspend (RobotWifiService, () -> Unit) -> Unit) {
        val current = binding ?: return
        val before = state.value
        val profile = before.profile?.takeIf { it.accessPoint } ?: return
        if (before.recovery != null || before.radio?.modeCode != 0) return
        launch(WifiPhase.RADIO_CHANGING, current) { token ->
            var dispatched = false
            var acknowledged = false
            var actual: WifiRadioSettings? = null
            try {
                change(current.service) {
                    write(current.service) {
                        dispatched = true
                        update(token) { it.copy(radioMessage = null, recovery = WifiRecovery(current.origin, profile, profile, false, target)) }
                    }
                    acknowledged = true
                    val readback = current.service.readRadio()
                    actual = readback
                    if (!target.matches(readback)) throw RobotRequestException(RequestProblem.NOT_APPLIED)
                }
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(phase = WifiPhase.EDITING, radio = actual, recovery = null,
                    radioMessage = uiText(Res.string.wifi_radio_verified)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!dispatched || !acknowledged && error is RobotRequestException && error.problem == RequestProblem.REJECTED || actual != null) {
                    update(token) { it.copy(phase = WifiPhase.EDITING, radio = actual ?: it.radio, recovery = null,
                        error = uiText(if (actual == null) Res.string.wifi_radio_rejected else Res.string.wifi_radio_not_applied)) }
                } else {
                    update(token) { it.copy(phase = WifiPhase.NETWORK_REQUIRED, error = uiText(Res.string.wifi_radio_uncertain)) }
                    if (version.load() == token) retire(current.service)
                }
            }
        }
    }

    fun reboot() {
        val current = binding ?: return
        val before = state.value
        val profile = before.profile ?: return
        if (!profile.hasWifiConfiguration) return
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
                val verified = reconnect(recovery.origin, recovery.expected, recovery.expectedRadio)
                val profile = verified.profile
                currentCoroutineContext().ensureActive()
                update(token) { it.copy(phase = WifiPhase.VERIFIED, profile = profile, radio = verified.radio, recovery = null,
                    draft = profile.apDraft(), confirmed = emptySet()) }
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
                state.update { it.copy(connected = false, profile = null, radio = null, signals = WifiSignals(), monitoring = false) }
                return
            }
            if (before.phase != WifiPhase.NETWORK_REQUIRED) {
                version.addAndFetch(1); job?.cancel(); job = null
            }
            state.update { it.copy(connected = false, profile = null, radio = null, signals = WifiSignals(), monitoring = false, phase = WifiPhase.NETWORK_REQUIRED,
                error = if (before.phase == WifiPhase.NETWORK_REQUIRED) it.error else uiText(Res.string.wifi_connection_changed)) }
        } else cancel()
    }

    fun cancel() {
        version.addAndFetch(1); job?.cancel(); job = null
        state.value = RobotWifiState(connection = connections.addAndFetch(1), connected = binding != null,
            phase = if (binding != null) WifiPhase.EDITING else WifiPhase.IDLE)
    }

    fun close() { binding = null; cancel(); monitoringJob.cancel() }
}
