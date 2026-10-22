package cn.elonzh.hanppie.ui.robot.network

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun sdrSelectionText(code: Int): String = when (code) {
    0 -> tr(Res.string.wifi_channel_manual); 1 -> tr(Res.string.wifi_channel_auto)
    else -> "${tr(Res.string.unknown)} ($code)"
}
@Composable
private fun sdrBandText(code: Int): String = when (code) {
    0 -> tr(Res.string.sdr_dual_band); 1 -> "2.4 GHz"; 2 -> "5.8 GHz"
    else -> "${tr(Res.string.unknown)} ($code)"
}
@Composable
private fun sdrBandwidthText(code: Int): String = when (code) {
    0 -> "20 MHz"; 1 -> "10 MHz"; else -> "${tr(Res.string.unknown)} ($code)"
}
@Composable
private fun sdrChangeText(change: SdrChange): String = when (change) {
    is SdrChange.Selection -> tr(Res.string.sdr_selection, sdrSelectionText(change.value))
    is SdrChange.Band -> tr(Res.string.sdr_band, sdrBandText(change.value))
    is SdrChange.Bandwidth -> tr(Res.string.sdr_bandwidth, sdrBandwidthText(change.value))
    is SdrChange.Point -> tr(Res.string.sdr_frequency, sdrFrequencyMHz(change.value) ?: "${change.value}")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SdrSettingsControls(model: ConsoleController) {
    val controller = model.robotSdr
    val state by controller.state.collectAsState()
    if (!state.visible) return
    val console by model.state.collectAsState()
    val album by model.robotAlbum.state.collectAsState()
    val camera by model.robotCamera.state.collectAsState()
    val chat by model.chat.state.collectAsState()
    val talking by model.talking.collectAsState()
    val talkBusy by model.talkBusy.collectAsState()
    val wifi by model.robotWifi.state.collectAsState()
    val canChange = !console.busy && !console.scriptRunPhase.mayBeExecuting && !album.nativeBusy && !camera.storageBusy &&
        camera.storage?.recording != true && !chat.running && !talking && !talkBusy && wifi.recovery == null
    var confirmation by remember { mutableStateOf<SdrChange?>(null) }
    var finishing by remember { mutableStateOf(false) }
    LaunchedEffect(state.connection, state.connected, state.settings, state.busy, canChange) {
        if (state.busy || !state.connected || !canChange) confirmation = null
    }
    // A new connection or readback invalidates a pending decision even if its old values happen to match.
    DisposableEffect(state.connection, state.settings) {
        onDispose { confirmation = null; finishing = false }
    }
    WorkbenchDialog(show = confirmation != null || finishing, onDismissRequest = { confirmation = null; finishing = false },
        title = tr(if (finishing) Res.string.wifi_end_question else Res.string.sdr_apply_question),
        summary = tr(if (finishing) Res.string.wifi_end_summary else Res.string.sdr_apply_summary)) {
        confirmation?.let { Text(sdrChangeText(it), fontSize = 15.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ confirmation = null; finishing = false }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(Res.string.cancel)) }
            Button({ val target = confirmation; confirmation = null
                if (finishing) { finishing = false; controller.finish() } else target?.let(controller::apply)
            }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("sdr-confirm"), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(tr(if (finishing) Res.string.wifi_finish else Res.string.sdr_apply))
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("sdr-settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr(Res.string.sdr_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        state.error?.let { Text(it.resolve(), Modifier.testTag("sdr-error"), fontSize = 14.sp, color = MiuixTheme.colorScheme.error) }
        if (state.busy) {
            Text(tr(when (state.phase) { SdrPhase.APPLYING -> Res.string.sdr_applying
                SdrPhase.RECONNECTING -> Res.string.wifi_reconnecting; else -> Res.string.sdr_reading }), fontSize = 15.sp)
            if (state.phase != SdrPhase.APPLYING) Button(controller::stopWaiting, Modifier.heightIn(min = 48.dp).testTag("sdr-stop-waiting")) {
                Text(tr(Res.string.wifi_stop_waiting))
            }
        } else if (state.recovery != null) {
            Text(tr(Res.string.sdr_recovery), fontSize = 15.sp)
            Text(sdrChangeText(state.recovery!!.change), fontSize = 15.sp)
            Button(controller::reconnect, Modifier.heightIn(min = 48.dp).testTag("sdr-reconnect"), !console.busy,
                colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.sdr_reconnect)) }
            Button({ finishing = true }, Modifier.heightIn(min = 48.dp).testTag("sdr-finish")) { Text(tr(Res.string.wifi_finish)) }
        } else state.settings?.let { settings ->
            if (state.verified) Text(tr(Res.string.sdr_verified), Modifier.testTag("sdr-verified"), fontSize = 15.sp)
            Button(controller::refresh, Modifier.heightIn(min = 48.dp).testTag("sdr-query")) { Text(tr(Res.string.sdr_query)) }
            Text(tr(Res.string.sdr_selection, sdrSelectionText(settings.selectionCode)), fontSize = 15.sp)
            Text(tr(Res.string.sdr_band, sdrBandText(settings.bandCode)), fontSize = 15.sp)
            Text(tr(Res.string.sdr_bandwidth, sdrBandwidthText(settings.bandwidthCode)), fontSize = 15.sp)
            Text(sdrFrequencyMHz(settings.point)?.let { tr(Res.string.sdr_frequency, it) }
                ?: tr(Res.string.sdr_unknown_point, settings.point), fontSize = 15.sp)
            if (!canChange) Text(tr(Res.string.wifi_task_active), fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SdrSelection.entries.forEach { mode -> Button({ confirmation = SdrChange.Selection(mode) },
                    Modifier.heightIn(min = 48.dp).testTag("sdr-mode-${mode.code}"), canChange && mode.code != settings.selectionCode) {
                    Text(sdrSelectionText(mode.code))
                } }
            }
            if (settings.selectionCode == 0) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(SdrBand.GHZ_2_4, SdrBand.GHZ_5_8).filter { it == SdrBand.GHZ_2_4 || settings.dualBandSupported }.forEach { band ->
                        Button({ confirmation = SdrChange.Band(band) }, Modifier.heightIn(min = 48.dp).testTag("sdr-band-${band.code}"),
                            canChange && band.code != settings.bandCode) { Text(sdrBandText(band.code)) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SdrBandwidth.entries.forEach { bandwidth -> Button({ confirmation = SdrChange.Bandwidth(bandwidth) },
                        Modifier.heightIn(min = 48.dp).testTag("sdr-width-${bandwidth.code}"), canChange && bandwidth.code != settings.bandwidthCode) {
                        Text(sdrBandwidthText(bandwidth.code))
                    } }
                }
                val points = settings.manualPoints
                if (points != null) {
                    var frequency by remember(state.connection, settings) { mutableStateOf(sdrFrequencyMHz(settings.point).orEmpty()) }
                    val selected = points.firstOrNull { sdrFrequencyMHz(it) == frequency.trim() }
                    TextField(frequency, { frequency = it }, Modifier.widthIn(max = 380.dp).fillMaxWidth().testTag("sdr-frequency"),
                        label = tr(Res.string.sdr_frequency_label), singleLine = true)
                    Text(tr(Res.string.sdr_range, sdrFrequencyMHz(points.first).orEmpty(), sdrFrequencyMHz(points.last).orEmpty()), fontSize = 14.sp)
                    Button({ selected?.let { confirmation = SdrChange.Point(it) } }, Modifier.heightIn(min = 48.dp).testTag("sdr-point-apply"),
                        canChange && selected != null && selected != settings.point) { Text(tr(Res.string.sdr_frequency_apply)) }
                }
            }
        }
    }
}
