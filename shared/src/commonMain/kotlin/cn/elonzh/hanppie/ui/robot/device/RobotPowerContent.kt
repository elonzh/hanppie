package cn.elonzh.hanppie.ui.robot.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun powerActionName(action: RobotPowerAction): String = tr(when (action) {
    RobotPowerAction.ENTER_STANDBY -> Res.string.robot_power_standby
    RobotPowerAction.EXIT_STANDBY -> Res.string.robot_power_wake
    RobotPowerAction.REBOOT -> Res.string.robot_power_reboot
    RobotPowerAction.SHUTDOWN -> Res.string.robot_power_shutdown
})

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RobotPowerContent(model: ConsoleController) {
    val controller = model.robotPower
    val state by controller.state.collectAsState()
    val console by model.state.collectAsState()
    if ((!console.connected || !state.connected) && state.result == null) return
    val camera by model.robotCamera.state.collectAsState()
    val album by model.robotAlbum.state.collectAsState()
    val chat by model.chat.state.collectAsState()
    val talk by model.pushToTalk.state.collectAsState()
    val wifi by model.robotWifi.state.collectAsState()
    val sdr by model.robotSdr.state.collectAsState()
    val canChange = !console.busy && !state.busy && state.connected && !console.scriptRunPhase.mayBeExecuting &&
        !camera.storageBusy && camera.storage?.recording != true && !album.nativeBusy && !chat.running && !talk.active &&
        wifi.recovery == null && sdr.recovery == null
    var pendingAction by remember(state.connection) { mutableStateOf<RobotPowerAction?>(null) }
    var pendingPolicy by remember(state.connection, state.policy) { mutableStateOf<LowPowerPolicy?>(null) }
    LaunchedEffect(canChange, state.connected) { if (!canChange || !state.connected) { pendingAction = null; pendingPolicy = null } }
    WorkbenchDialog(show = canChange && (pendingAction != null || pendingPolicy != null),
        onDismissRequest = { pendingAction = null; pendingPolicy = null }, title = tr(Res.string.robot_power_confirm_title),
        summary = tr(when (pendingAction) {
            RobotPowerAction.EXIT_STANDBY -> Res.string.robot_power_wake_summary
            null -> Res.string.robot_power_policy_summary
            else -> Res.string.robot_power_action_summary
        })) {
        pendingAction?.let { Text(powerActionName(it), fontSize = 16.sp) }
        pendingPolicy?.let { Text(tr(if (it.enabled) Res.string.robot_power_enable_delay else Res.string.robot_power_disable, it.delaySeconds), fontSize = 16.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ pendingAction = null; pendingPolicy = null }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("power-confirm-cancel")) {
                Text(tr(Res.string.cancel))
            }
            Button({ val action = pendingAction; val policy = pendingPolicy; pendingAction = null; pendingPolicy = null
                if (action != null) controller.perform(action) else policy?.let(controller::apply)
            }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("power-confirm"), colors = if (pendingAction == RobotPowerAction.SHUTDOWN) ButtonDefaults.buttonColors(
                color = MiuixTheme.colorScheme.errorContainer, contentColor = MiuixTheme.colorScheme.onErrorContainer)
                else ButtonDefaults.buttonColorsPrimary()) {
                Text(tr(Res.string.robot_power_confirm))
            }
        }
    }
    Text(tr(Res.string.robot_power_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Column(Modifier.fillMaxWidth().testTag("robot-power"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.result?.takeIf { state.phase != PowerPhase.ACTION }?.let { result ->
            val resultIntoView = remember { BringIntoViewRequester() }
            LaunchedEffect(result) {
                // Place the shortened maintenance content before revealing the result.
                withFrameNanos { }
                resultIntoView.bringIntoView()
            }
            Column(Modifier.fillMaxWidth().bringIntoViewRequester(resultIntoView),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(powerActionName(result.action), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(tr(if (result.outcome == PowerOutcome.UNCONFIRMED) Res.string.robot_power_action_uncertain
                    else if (result.action == RobotPowerAction.EXIT_STANDBY) Res.string.robot_power_wake_ack
                    else Res.string.robot_power_action_ack), Modifier.testTag("power-result"), fontSize = 15.sp)
            }
        }
        state.error?.let { Text(it.resolve(), Modifier.testTag("power-error"), fontSize = 14.sp, color = MiuixTheme.colorScheme.error) }
        if (!console.connected || !state.connected) Text(tr(Res.string.robot_power_connect), Modifier.testTag("power-connect"), fontSize = 15.sp)
        else {
            val report = state.report
            Text(if (report == null) tr(Res.string.robot_power_waiting) else tr(if (report.lowPower) Res.string.robot_power_report_standby else Res.string.robot_power_report_awake,
                report.reportAgeSeconds), Modifier.testTag("power-status"), fontSize = 15.sp)
            if (state.busy) {
                Text(tr(if (state.phase == PowerPhase.READING) Res.string.robot_info_reading else Res.string.robot_power_sending), fontSize = 15.sp)
                if (state.phase == PowerPhase.READING) Button(controller::cancelRead, Modifier.heightIn(min = 48.dp).testTag("power-cancel-read")) { Text(tr(Res.string.cancel)) }
            } else {
                Button(controller::refresh, Modifier.heightIn(min = 48.dp).testTag("power-query")) { Text(tr(Res.string.robot_power_query)) }
                state.policy?.let { policy ->
                    Text(tr(Res.string.robot_power_policy_scope), fontSize = 14.sp)
                    Text(tr(if (policy.enabled) Res.string.robot_power_policy_on else Res.string.robot_power_policy_off, policy.delaySeconds),
                        Modifier.testTag("power-policy"), fontSize = 15.sp)
                    if (state.verified) Text(tr(Res.string.robot_power_verified), Modifier.testTag("power-verified"), fontSize = 14.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 180, 300).forEach { seconds ->
                            Button({ pendingPolicy = LowPowerPolicy(true, seconds) }, Modifier.heightIn(min = 48.dp).testTag("power-delay-$seconds"),
                                enabled = canChange && policy != LowPowerPolicy(true, seconds)) { Text(tr(Res.string.robot_power_enable_delay, seconds)) }
                        }
                        Button({ pendingPolicy = policy.copy(enabled = false) }, Modifier.heightIn(min = 48.dp).testTag("power-disable-auto"),
                            enabled = canChange && policy.enabled) { Text(tr(Res.string.robot_power_disable)) }
                    }
                }
                if (!canChange) Text(tr(Res.string.robot_power_task_active), fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RobotPowerAction.entries.forEach { action -> Button({ pendingAction = action },
                        Modifier.heightIn(min = 48.dp).testTag("power-action-${action.name}"), enabled = canChange) { Text(powerActionName(action)) } }
                }
            }
        }
    }
}
