package cn.elonzh.hanppie.ui.robot.network

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.*
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class WifiAction { SAVE, REBOOT, RADIO, END }
private data class WifiConfirmation(val action: WifiAction, val connection: Long, val draft: WifiDraft?,
    val target: WifiRadioTarget? = null, val radioSource: WifiRadioSettings? = null)

@Composable
internal fun RobotWifiPage(model: ConsoleController, modifier: Modifier = Modifier, onBack: () -> Unit,
    onOpenWifiSettings: (() -> Unit)? = null) {
    val controller = model.robotWifi
    val state by controller.state.collectAsState()
    val console by model.state.collectAsState()
    val album by model.robotAlbum.state.collectAsState()
    val camera by model.robotCamera.state.collectAsState()
    val chat by model.chat.state.collectAsState()
    val talking by model.talking.collectAsState()
    val talkBusy by model.talkBusy.collectAsState()
    val canChange = !console.busy && !console.scriptRunPhase.mayBeExecuting && !album.nativeBusy && !camera.storageBusy &&
        camera.storage?.recording != true && !chat.running && !talking && !talkBusy
    var confirmation by remember { mutableStateOf<WifiConfirmation?>(null) }
    var showPassword by remember(state.connection) { mutableStateOf(false) }
    var channels by rememberSaveable { mutableStateOf(false) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    DisposableEffect(controller, channels) {
        controller.showChannels(channels)
        onDispose { controller.showChannels(false) }
    }
    LaunchedEffect(state.connection, channels, state.profile?.modeCode) {
        if (state.connected && !state.busy) {
            if (state.profile == null) controller.refresh()
            else if (channels && state.radio == null) controller.refreshRadio()
        }
    }
    LaunchedEffect(state.connection, state.connected, state.busy, state.draft, state.radio, canChange) {
        confirmation?.let { before ->
            if (before.connection != state.connection || state.busy || before.action != WifiAction.END && (!state.connected || !canChange) ||
                before.action == WifiAction.SAVE && before.draft != state.draft ||
                before.action == WifiAction.RADIO && before.radioSource != state.radio) confirmation = null
        }
    }
    val action = confirmation?.action
    WorkbenchDialog(show = confirmation != null, onDismissRequest = { confirmation = null },
        title = tr(when (action) { WifiAction.SAVE -> Res.string.wifi_apply_question; WifiAction.REBOOT -> Res.string.wifi_restart_question; WifiAction.RADIO -> Res.string.wifi_channel_change_question; else -> Res.string.wifi_end_question }),
        summary = tr(when (action) { WifiAction.SAVE -> Res.string.wifi_apply_summary; WifiAction.REBOOT -> Res.string.wifi_restart_summary; WifiAction.RADIO -> Res.string.wifi_channel_change_summary; else -> Res.string.wifi_end_summary })) {
        confirmation?.target?.let { Text(tr(Res.string.wifi_channel_target, wifiRadioTargetText(it)), fontSize = 15.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ confirmation = null }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(Res.string.cancel)) }
            Button({
                val target = confirmation?.target
                confirmation = null
                when (action) {
                    WifiAction.SAVE -> controller.apply()
                    WifiAction.REBOOT -> controller.reboot()
                    WifiAction.RADIO -> target?.let { selected ->
                        val channel = selected.channel
                        if (channel == null) controller.setChannelMode(selected.mode) else controller.setChannel(channel)
                    }
                    else -> { controller.cancel(); onBack() }
                }
            }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("wifi-confirm"),
                colors = if (action == WifiAction.END) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary()) {
                Text(tr(when (action) { WifiAction.SAVE -> Res.string.wifi_apply; WifiAction.REBOOT -> Res.string.wifi_restart_only; WifiAction.RADIO -> Res.string.wifi_channel_apply; else -> Res.string.wifi_finish }))
            }
        }
    }
    Column(modifier.fillMaxSize().testTag("robot-wifi-page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchIconButton(tr(Res.string.back), WorkbenchGlyph.BACK, onBack, enabled = !state.busy, tag = "wifi-back")
            Text(tr(Res.string.robot_wifi), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("wifi-body"), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            state.error?.let { error -> item { Text(error.resolve(), Modifier.testTag("wifi-error"), fontSize = 14.sp, color = MiuixTheme.colorScheme.error) } }
            if (state.busy) item {
                Text(tr(when (state.phase) {
                    WifiPhase.READING -> Res.string.wifi_reading; WifiPhase.SAVING -> Res.string.wifi_saving
                    WifiPhase.REBOOTING -> Res.string.wifi_rebooting
                    WifiPhase.RADIO_CHANGING -> Res.string.wifi_radio_changing
                    else -> Res.string.wifi_reconnecting
                }), Modifier.testTag("wifi-progress"), fontSize = 15.sp)
                if (state.phase in setOf(WifiPhase.READING, WifiPhase.RECONNECTING))
                    Button(controller::stopWaiting, Modifier.heightIn(min = 48.dp).testTag("wifi-stop-waiting")) { Text(tr(Res.string.wifi_stop_waiting)) }
            }
            if (state.confirmed.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (WifiConfirmedField.SSID in state.confirmed) Text(tr(Res.string.wifi_ssid_ack), Modifier.testTag("wifi-ssid-ack"), fontSize = 14.sp)
                    if (WifiConfirmedField.PASSWORD in state.confirmed) Text(tr(Res.string.wifi_password_ack), Modifier.testTag("wifi-password-ack"), fontSize = 14.sp)
                }
            }
            if (state.phase in setOf(WifiPhase.NETWORK_REQUIRED, WifiPhase.RECONNECTING)) {
                item {
                    Text(tr(Res.string.wifi_network_required), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (state.restartAcknowledged) Text(tr(Res.string.wifi_reboot_ack), Modifier.testTag("wifi-reboot-ack"), fontSize = 14.sp)
                    Text(tr(if (state.recovery?.configurationRequested == true) Res.string.wifi_partial_summary else Res.string.wifi_reconnect_summary), fontSize = 14.sp)
                }
                state.recovery?.let { recovery -> item { RecoveryProfiles(recovery) } }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (onOpenWifiSettings != null) Button(onOpenWifiSettings, Modifier.heightIn(min = 48.dp).testTag("wifi-open-system"), !state.busy) { Text(tr(Res.string.open_wifi_settings)) }
                        Button(controller::reconnect, Modifier.heightIn(min = 48.dp).testTag("wifi-reconnect"), !state.busy && !console.busy,
                            colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.wifi_reconnect)) }
                        Button({ confirmation = WifiConfirmation(WifiAction.END, state.connection, null) },
                            Modifier.heightIn(min = 48.dp).testTag("wifi-end"), !state.busy) { Text(tr(Res.string.wifi_finish)) }
                    }
                }
            } else if (state.phase == WifiPhase.VERIFIED) item {
                Text(tr(Res.string.wifi_verified), Modifier.testTag("wifi-verified"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                state.profile?.let { Text(tr(Res.string.wifi_current_name, it.ssid), fontSize = 15.sp) }
                state.radio?.let { Text(tr(Res.string.wifi_current_channel, it.currentChannel), fontSize = 15.sp) }
                Button({ if (channels) controller.refreshRadio() else controller.refresh() }, Modifier.heightIn(min = 48.dp).testTag("wifi-query")) { Text(tr(Res.string.wifi_query)) }
            } else if (state.connected) {
                state.profile?.let { profile -> item {
                    Text(tr(Res.string.wifi_mode, tr(when (profile.modeCode) { 0 -> Res.string.direct_mode; 2 -> Res.string.router_mode; else -> Res.string.unknown })),
                        Modifier.testTag("wifi-mode"), fontSize = 15.sp)
                    Text(tr(Res.string.wifi_current_name, profile.ssid), fontSize = 14.sp)
                } }
                if (state.phase in setOf(WifiPhase.EDITING, WifiPhase.READING)) item {
                    TabRow(listOf(tr(Res.string.wifi_network_tab), tr(Res.string.wifi_channels_tab)), if (channels) 1 else 0,
                        { if (!state.busy) { focus.clearFocus(); keyboard?.hide(); channels = it == 1 } },
                        modifier = Modifier.fillMaxWidth().testTag("wifi-tabs"), height = 48.dp)
                }
                if (channels && state.phase == WifiPhase.EDITING) item {
                    WifiChannelControls(state, canChange, controller::refreshRadio,
                        { confirmation = WifiConfirmation(WifiAction.RADIO, state.connection, null, it, state.radio) }, controller::retrySignals)
                }
                if (state.phase == WifiPhase.READY) item {
                    Text(tr(Res.string.wifi_ready), Modifier.testTag("wifi-ready"), fontSize = 15.sp)
                    Button({ state.draft?.let { controller.edit(it.ssid, it.password) } }, Modifier.heightIn(min = 48.dp).testTag("wifi-edit")) { Text(tr(Res.string.wifi_edit_again)) }
                }
                if (state.phase == WifiPhase.EDITING && !channels) {
                    item { Button(controller::refresh, Modifier.heightIn(min = 48.dp).testTag("wifi-query")) { Text(tr(Res.string.wifi_query)) } }
                    if (!canChange) item { Text(tr(Res.string.wifi_task_active), fontSize = 14.sp) }
                    state.draft?.takeIf { state.profile?.accessPoint == true }?.let { draft -> item {
                        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextField(draft.ssid, { controller.edit(it, controller.state.value.draft?.password.orEmpty()) },
                                Modifier.fillMaxWidth().testTag("wifi-ssid"), label = tr(Res.string.wifi_name), singleLine = true)
                            Text(tr(Res.string.wifi_name_rules), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            TextField(draft.password, { controller.edit(controller.state.value.draft?.ssid.orEmpty(), it) },
                                Modifier.fillMaxWidth().testTag("wifi-password"), label = tr(Res.string.wifi_password), singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                            Text(tr(Res.string.wifi_password_rules), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Button({ showPassword = !showPassword }, Modifier.heightIn(min = 48.dp).testTag("wifi-password-visibility")) { Text(tr(if (showPassword) Res.string.wifi_hide_password else Res.string.wifi_show_password)) }
                            Button({ confirmation = WifiConfirmation(WifiAction.SAVE, state.connection, draft) }, Modifier.heightIn(min = 48.dp).testTag("wifi-apply"), draft.valid && canChange,
                                colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.wifi_apply)) }
                        }
                    } }
                }
                if (!channels && (state.phase == WifiPhase.READY || state.phase == WifiPhase.EDITING && state.recovery == null && state.profile != null &&
                    (state.draft == null || state.draft == state.profile?.let { WifiDraft(it.ssid, it.password.orEmpty()) }))) item {
                    Button({ confirmation = WifiConfirmation(WifiAction.REBOOT, state.connection, state.draft) },
                        Modifier.heightIn(min = 48.dp).testTag("wifi-reboot"), canChange) {
                        Text(tr(if (state.phase == WifiPhase.READY) Res.string.wifi_restart else Res.string.wifi_restart_only))
                    }
                }
            } else item { Text(tr(Res.string.wifi_connect_first), fontSize = 15.sp) }
        }
    }
}

@Composable
private fun RecoveryProfiles(recovery: WifiRecovery) {
    recovery.expectedRadio?.let { Text(tr(Res.string.wifi_channel_target, wifiRadioTargetText(it)), fontSize = 15.sp) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 720.dp * LocalDensity.current.fontScale
        if (wide && recovery.previous != recovery.expected) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CredentialSummary(recovery.previous, tr(Res.string.wifi_previous), Modifier.weight(1f))
            CredentialSummary(recovery.expected, tr(Res.string.wifi_expected), Modifier.weight(1f))
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (recovery.previous != recovery.expected) CredentialSummary(recovery.previous, tr(Res.string.wifi_previous))
            CredentialSummary(recovery.expected, tr(Res.string.wifi_expected))
        }
    }
}

@Composable
private fun CredentialSummary(profile: RobotWifiProfile, title: String, modifier: Modifier = Modifier) {
    var show by remember(profile) { mutableStateOf(false) }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(profile.ssid, fontSize = 15.sp)
            profile.password?.let { password ->
                Text(if (show) password else "••••••••", fontSize = 15.sp)
                Button({ show = !show }, Modifier.heightIn(min = 48.dp)) { Text(tr(if (show) Res.string.wifi_hide_password else Res.string.wifi_show_password)) }
            }
        }
    }
}
