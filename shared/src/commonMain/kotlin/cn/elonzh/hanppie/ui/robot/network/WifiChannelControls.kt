package cn.elonzh.hanppie.ui.robot.network

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun wifiChannelModeText(code: Int): String = when (code) {
    0 -> tr(Res.string.wifi_channel_auto)
    1 -> tr(Res.string.wifi_channel_manual)
    else -> "${tr(Res.string.unknown)} ($code)"
}

@Composable
internal fun wifiRadioTargetText(target: WifiRadioTarget): String = wifiChannelModeText(target.mode.code) +
    (target.channel?.let { " · ${tr(Res.string.wifi_channel_number, it)}" } ?: "")

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun WifiChannelControls(state: RobotWifiState, canChange: Boolean, onRefresh: () -> Unit,
    onSelect: (WifiRadioTarget) -> Unit, onRetryReports: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("wifi-channel-controls"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onRefresh, Modifier.heightIn(min = 48.dp).testTag("wifi-radio-query"), !state.busy && state.recovery == null) {
            Text(tr(Res.string.wifi_radio_query))
        }
        state.radioMessage?.let { Text(it.resolve(), Modifier.testTag("wifi-radio-result"), fontSize = 15.sp) }
        val radio = state.radio
        if (radio != null) {
            Text(tr(Res.string.wifi_channel_mode, wifiChannelModeText(radio.selectionCode)), Modifier.testTag("wifi-channel-mode"), fontSize = 15.sp)
            Text(tr(Res.string.wifi_current_channel, radio.currentChannel), Modifier.testTag("wifi-channel-current"), fontSize = 15.sp)
        }
        val interference = state.signals.interferenceCode
        val status = when (interference) {
            0 -> tr(Res.string.wifi_interference_normal)
            1 -> tr(Res.string.wifi_interference_environment)
            2 -> tr(Res.string.wifi_interference_downlink)
            3 -> tr(Res.string.wifi_interference_uplink)
            4 -> tr(Res.string.wifi_interference_weak)
            null -> tr(Res.string.unknown)
            else -> "${tr(Res.string.unknown)} ($interference)"
        }
        Text(tr(Res.string.wifi_interference, status), Modifier.testTag("wifi-interference"), fontSize = 15.sp)
        if (state.recovery != null) Text(tr(Res.string.wifi_channel_pending_ap), fontSize = 14.sp)
        if (radio != null && (radio.modeCode != 0 || state.profile?.accessPoint != true)) {
            Text(tr(Res.string.wifi_channel_readonly), fontSize = 14.sp)
        } else if (radio != null) {
            val enabled = canChange && !state.busy && state.recovery == null
            if (!canChange) Text(tr(Res.string.wifi_task_active), fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WifiChannelMode.entries.forEach { mode ->
                    Button({ onSelect(WifiRadioTarget(mode)) }, Modifier.heightIn(min = 48.dp).testTag("wifi-channel-mode-${mode.code}"),
                        enabled && radio.selectionCode != mode.code) { Text(wifiChannelModeText(mode.code)) }
                }
            }
            if (radio.availableChannels.isEmpty()) Text(tr(Res.string.wifi_channel_unavailable), fontSize = 14.sp)
            if (radio.selectionCode == 1) {
                Text(tr(Res.string.wifi_report_value_note), fontSize = 14.sp)
                if (state.signals.channels == null) Text(tr(Res.string.wifi_monitor_waiting), Modifier.testTag("wifi-signal-waiting"), fontSize = 14.sp)
            }
            state.monitorError?.let {
                Text(it.resolve(), Modifier.testTag("wifi-monitor-error"), fontSize = 14.sp, color = MiuixTheme.colorScheme.error)
                Button(onRetryReports, Modifier.heightIn(min = 48.dp).testTag("wifi-monitor-retry"), !state.busy) {
                    Text(tr(Res.string.wifi_monitor_retry))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                radio.availableChannels.distinct().forEach { number ->
                    val value = state.signals.channels?.lastOrNull { it.channel == number }?.value
                    val current = number == radio.currentChannel
                    if (number in radio.selectableChannels) Button({ onSelect(WifiRadioTarget(WifiChannelMode.MANUAL, number)) },
                        Modifier.heightIn(min = 80.dp).testTag("wifi-channel-$number"), enabled && radio.selectionCode == 1 && !current,
                        colors = if (current) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tr(Res.string.wifi_channel_number, number))
                            if (current) Text(tr(Res.string.wifi_channel_current), fontSize = 13.sp)
                            Text(tr(Res.string.wifi_channel_value, value?.toString() ?: "—"), fontSize = 13.sp)
                        }
                    } else Text(tr(Res.string.wifi_channel_number, number), fontSize = 14.sp)
                }
            }
        }
    }
}
