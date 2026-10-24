package cn.elonzh.hanppie.ui.robot.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.gamepad.*
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun GamepadInformationPage(session: GamepadSession, modifier: Modifier = Modifier, onBack: () -> Unit) {
    Column(modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).testTag("gamepad-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            WorkbenchIconButton(tr(Res.string.back), WorkbenchGlyph.BACK, onClick = onBack, tag = "gamepad-back")
            Text(tr(Res.string.gamepad_information), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("gamepad-scroll"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = HanppieDesignTokens.PageMaxWidth).fillMaxWidth().padding(HanppieDesignTokens.PagePaddingCompact)) {
                GamepadInformationContent(session)
            }
        }
    }
}

@Composable
private fun GamepadInformationContent(session: GamepadSession) {
    if (!session.available) return
    val state by session.state.collectAsState()
    LaunchedEffect(session) { session.refresh() }
    Column(Modifier.fillMaxWidth().testTag("gamepad-information"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(Res.string.gamepad_information_help), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (state.phase == GamepadPhase.DISCONNECTED) {
            Button(session::refresh, Modifier.heightIn(min = 48.dp).testTag("gamepad-discover")) { Text(tr(Res.string.gamepad_refresh)) }
            if (state.accessories.isEmpty()) Text(tr(Res.string.gamepad_none), Modifier.testTag("gamepad-empty"), fontSize = 14.sp)
            state.accessories.forEach { device ->
                Button({ session.connect(device.id) }, Modifier.heightIn(min = 48.dp).testTag("gamepad-connect-${device.id}")) {
                    Text(tr(Res.string.gamepad_connect, device.label))
                }
            }
        } else {
            Text(state.selected?.label.orEmpty(), fontSize = 15.sp)
            Text(tr(if (state.phase == GamepadPhase.CONNECTING) Res.string.gamepad_opening else Res.string.gamepad_link_open),
                Modifier.testTag("gamepad-phase"), fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(session::disconnect, Modifier.heightIn(min = 48.dp).testTag("gamepad-disconnect")) {
                    Text(tr(if (state.phase == GamepadPhase.CONNECTING) Res.string.cancel else Res.string.disconnect_close_session))
                }
                if (state.phase == GamepadPhase.CONNECTED) {
                    Button(session::readFirmware, Modifier.heightIn(min = 48.dp).testTag("gamepad-firmware-query"), !state.querying) {
                        Text(tr(Res.string.gamepad_query_firmware))
                    }
                    Button(session::readActivation, Modifier.heightIn(min = 48.dp).testTag("gamepad-activation-query"), !state.querying) {
                        Text(tr(Res.string.gamepad_query_activation))
                    }
                }
            }
            if (state.phase == GamepadPhase.CONNECTED) {
                if (state.querying) Text(tr(Res.string.robot_info_reading), Modifier.testTag("gamepad-querying"), fontSize = 14.sp)
                val report = state.report
                if (report == null) Text(tr(Res.string.gamepad_waiting_report), fontSize = 14.sp)
                else {
                    if (!state.reportFresh) Text(tr(Res.string.gamepad_report_stale), Modifier.testTag("gamepad-stale"),
                        fontSize = 14.sp, color = MiuixTheme.colorScheme.error)
                    Text(tr(Res.string.gamepad_battery, report.batteryPercent?.let { "$it%" } ?: tr(Res.string.unknown)),
                        Modifier.testTag("gamepad-battery"), fontSize = 14.sp)
                    Text(tr(Res.string.gamepad_accessories,
                        tr(if (report.mouseConnected) Res.string.connected else Res.string.not_connected),
                        tr(if (report.keyboardConnected) Res.string.connected else Res.string.not_connected)), fontSize = 14.sp)
                    if (state.reportFresh) report.warnings.forEach { warning ->
                        Text(warning.label(), Modifier.testTag("gamepad-warning-${warning.name}"),
                            fontSize = 14.sp, color = MiuixTheme.colorScheme.error)
                    }
                }
                Text(tr(Res.string.gamepad_firmware, state.firmware ?: tr(Res.string.robot_info_unread)), Modifier.testTag("gamepad-firmware"), fontSize = 14.sp)
                val activation = if (!state.activationRead) tr(Res.string.robot_info_unread)
                    else tr(when (state.activated) { true -> Res.string.robot_info_activated; false -> Res.string.robot_info_not_activated; null -> Res.string.unknown })
                Text(tr(Res.string.gamepad_activation, activation), Modifier.testTag("gamepad-activation"), fontSize = 14.sp)
            }
        }
        state.problem?.let { problem ->
            Text(tr(when (problem) {
                GamepadProblem.NOT_FOUND -> Res.string.gamepad_missing
                GamepadProblem.PERMISSION_DENIED -> Res.string.gamepad_permission_denied
                GamepadProblem.OPEN_FAILED -> Res.string.gamepad_open_failed
                GamepadProblem.LINK_LOST -> Res.string.gamepad_link_lost
                GamepadProblem.TIMEOUT -> Res.string.gamepad_query_timeout
                GamepadProblem.INVALID_RESPONSE -> Res.string.gamepad_query_invalid
                GamepadProblem.REJECTED -> Res.string.gamepad_query_rejected
            }), Modifier.testTag("gamepad-error"), fontSize = 14.sp, color = MiuixTheme.colorScheme.error)
        }
    }
}
