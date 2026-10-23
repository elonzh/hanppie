package cn.elonzh.hanppie.ui.robot.maintenance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.*
import cn.elonzh.hanppie.ui.i18n.tr
import org.jetbrains.compose.resources.StringResource
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class CalibrationAction { START, STOP, EXIT, RETRY_ARM, OBSERVE, RESTARTED }
private data class CalibrationPrompt(val action: CalibrationAction, val connection: Long, val kind: CalibrationKind?, val canStopImu: Boolean)

@Composable
internal fun RobotCalibrationPage(model: ConsoleController, modifier: Modifier = Modifier, onBack: () -> Unit,
    exitRequested: Boolean = false, onExitCancelled: () -> Unit = {}, onExitComplete: () -> Unit = onBack) {
    val controller = model.robotCalibration
    val ui by controller.state.collectAsState()
    val console by model.state.collectAsState()
    val health by model.robotHealth.state.collectAsState()
    val camera by model.robotCamera.state.collectAsState()
    val album by model.robotAlbum.state.collectAsState()
    val chat by model.chat.state.collectAsState()
    val talking by model.talking.collectAsState()
    val talkBusy by model.talkBusy.collectAsState()
    val addressing by model.robotAddressing.state.collectAsState()
    val wifi by model.robotWifi.state.collectAsState()
    val sdr by model.robotSdr.state.collectAsState()
    val task = ui.task
    val canChange = ui.connected && console.connected && !console.busy && !console.scriptRunPhase.mayBeExecuting &&
        !camera.storageBusy && camera.storage?.recording != true && !album.nativeBusy && !chat.running && !talking && !talkBusy &&
        !addressing.blocksControl && wifi.recovery == null && sdr.recovery == null
    val gimbalOnline = health.modules?.any { it.deviceId == RobotComponent.GIMBAL.deviceId } == true
    val armOnline = health.modules?.any { it.deviceId == RobotComponent.ARM.deviceId } == true
    fun available(kind: CalibrationKind): Boolean = health.modules != null && when (kind) {
        CalibrationKind.CHASSIS_AND_GIMBAL -> true
        CalibrationKind.GIMBAL -> gimbalOnline
        CalibrationKind.ARM -> armOnline
    }
    var selected by remember(ui.connection) { mutableStateOf<CalibrationKind?>(null) }
    var chooseAgain by remember(ui.connection) { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<CalibrationPrompt?>(null) }
    fun dismiss() { if (prompt?.action == CalibrationAction.EXIT) onExitCancelled(); prompt = null }
    fun request(action: CalibrationAction) {
        controller.stopAutomaticContinuation(ui.connection)
        prompt = CalibrationPrompt(action, ui.connection, if (action == CalibrationAction.START) selected else task.kind, task.canStopImu)
    }
    DisposableEffect(controller, ui.connection) {
        val connection = ui.connection
        onDispose { controller.pageLeft(connection) }
    }
    LaunchedEffect(exitRequested, task.phase, ui.canceling) {
        if (exitRequested && !ui.canceling) {
            if (!task.blocksControl && !ui.busy) { onExitCancelled(); onExitComplete() }
            else if (prompt?.action != CalibrationAction.EXIT) request(CalibrationAction.EXIT)
        }
    }
    LaunchedEffect(ui.connection, ui.connected, ui.busy, task.phase, task.canStopImu, task.imuStatusRaw, task.gimbalStatusRaw, health.modules) {
        prompt?.let { p ->
            if (p.connection != ui.connection || !ui.connected ||
                p.action == CalibrationAction.START && (ui.busy || p.kind?.let(::available) != true) ||
                p.action == CalibrationAction.STOP && !task.canStopImu ||
                p.action == CalibrationAction.EXIT && p.canStopImu != task.canStopImu ||
                p.action == CalibrationAction.RETRY_ARM && (ui.busy || task.phase != CalibrationPhase.UNCERTAIN || !armOnline) ||
                p.action == CalibrationAction.RESTARTED && (ui.busy || !task.observingInterrupted || task.phase != CalibrationPhase.UNCERTAIN ||
                    task.imuStatusRaw == 3 || task.gimbalStatusRaw == 1)) dismiss()
        }
    }
    val pending = prompt
    WorkbenchDialog(show = pending != null, onDismissRequest = ::dismiss,
        title = tr(when (pending?.action) {
            CalibrationAction.START -> Res.string.calibration_start_question
            CalibrationAction.RETRY_ARM -> Res.string.calibration_arm_retry_question
            CalibrationAction.OBSERVE -> Res.string.calibration_observe_question
            CalibrationAction.RESTARTED -> Res.string.calibration_restart_question
            CalibrationAction.STOP -> Res.string.calibration_stop_question
            else -> if (pending?.canStopImu == true) Res.string.calibration_stop_exit_question else Res.string.calibration_leave_question
        }), summary = tr(when (pending?.action) {
            CalibrationAction.START -> if (pending.kind == CalibrationKind.ARM) Res.string.calibration_arm_confirm else Res.string.calibration_start_confirm
            CalibrationAction.RETRY_ARM -> Res.string.calibration_arm_confirm
            CalibrationAction.OBSERVE -> Res.string.calibration_observe_confirm
            CalibrationAction.RESTARTED -> Res.string.calibration_restart_confirm
            CalibrationAction.STOP -> Res.string.calibration_stop_summary
            else -> if (pending?.canStopImu == true) Res.string.calibration_stop_summary else Res.string.calibration_leave_summary
        })) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(::dismiss, Modifier.weight(1f).heightIn(min = 48.dp).testTag("calibration-confirm-cancel")) { Text(tr(Res.string.cancel)) }
            Button({
                val p = prompt ?: return@Button
                prompt = null
                if (p.action == CalibrationAction.EXIT) onExitCancelled()
                val current = controller.state.value
                if (current.connection != p.connection || !current.connected) return@Button
                when (p.action) {
                    CalibrationAction.START -> if (!current.busy && p.kind?.let(::available) == true) {
                        selected = null; chooseAgain = false; controller.begin(p.kind, p.connection)
                    }
                    CalibrationAction.STOP -> if (current.task.canStopImu) controller.cancel(p.connection)
                    CalibrationAction.EXIT -> if (current.task.canStopImu) controller.cancel(p.connection, onExitComplete)
                        else { controller.stopAutomaticContinuation(p.connection); onExitComplete() }
                    CalibrationAction.RETRY_ARM -> controller.retryArm(p.connection)
                    CalibrationAction.OBSERVE -> controller.observePrevious(p.connection)
                    CalibrationAction.RESTARTED -> controller.confirmRestarted(p.connection)
                }
            }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("calibration-confirm"), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(tr(when (pending?.action) {
                    CalibrationAction.START -> if (pending.kind == CalibrationKind.ARM) Res.string.calibration_arm_confirm_action else Res.string.calibration_start
                    CalibrationAction.RETRY_ARM -> Res.string.calibration_arm_confirm_action
                    CalibrationAction.RESTARTED -> Res.string.calibration_restart_confirm_action
                    CalibrationAction.OBSERVE -> Res.string.calibration_observe
                    CalibrationAction.STOP -> Res.string.calibration_stop
                    else -> if (pending?.canStopImu == true) Res.string.calibration_stop_exit else Res.string.calibration_leave
                }))
            }
        }
    }
    Column(modifier.fillMaxSize().testTag("robot-calibration-page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().testTag("calibration-header"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchIconButton(tr(Res.string.back), WorkbenchGlyph.BACK, onBack, enabled = !ui.canceling, tag = "calibration-back")
            Text(tr(Res.string.calibration_title), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("calibration-body"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.error?.let { Text(it.resolve(), Modifier.testTag("calibration-error"), fontSize = 15.sp, color = MiuixTheme.colorScheme.error) }
            if (ui.busy) Text(tr(if (ui.canceling) Res.string.calibration_stopping else Res.string.calibration_working),
                Modifier.testTag("calibration-working"), fontSize = 15.sp)
            val recovery = ui.recovery
            if (recovery != null) {
                Text(tr(Res.string.calibration_interrupted), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(tr(Res.string.calibration_origin, calibrationKindName(checkNotNull(recovery.previous.kind)), recovery.target.ip), fontSize = 15.sp)
                if (!ui.connected) {
                    Text(tr(Res.string.calibration_no_replay), fontSize = 15.sp)
                    Button(model::reconnectCalibration, Modifier.heightIn(min = 48.dp).testTag("calibration-reconnect"), enabled = !console.busy && !ui.busy,
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.calibration_reconnect)) }
                } else if (!ui.canRecover) {
                    Text(tr(Res.string.calibration_wrong_robot), fontSize = 15.sp)
                    Button(model::disconnect, Modifier.heightIn(min = 48.dp), enabled = !console.busy) { Text(tr(Res.string.disconnect_close_session)) }
                } else if (!task.observingInterrupted) {
                    Text(tr(Res.string.calibration_no_replay), fontSize = 15.sp)
                    Button({ request(CalibrationAction.OBSERVE) }, Modifier.heightIn(min = 48.dp).testTag("calibration-observe"),
                        enabled = canChange && !ui.busy, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.calibration_observe)) }
                }
            }
            val showTask = recovery == null || task.observingInterrupted
            if (showTask) {
                if (!ui.connected || !console.connected) {
                    Text(tr(Res.string.calibration_connect_first), fontSize = 15.sp)
                    Button(model::discover, Modifier.heightIn(min = 48.dp), enabled = !console.busy) { Text(tr(Res.string.automatic_connection)) }
                } else if (task.blocksControl || ui.busy) {
                    task.kind?.let { Text(calibrationKindName(it), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                    task.problem?.let { Text(tr(calibrationProblemText(it)), Modifier.testTag("calibration-problem"),
                        fontSize = 15.sp, color = MiuixTheme.colorScheme.error) }
                    if (task.phase == CalibrationPhase.UNCERTAIN && (task.observingInterrupted || task.gimbalMayBeActive)) {
                        Text(tr(Res.string.calibration_restart_help), Modifier.testTag("calibration-restart-help"), fontSize = 15.sp)
                    }
                    CalibrationTaskContent(controller, ui, canChange)
                    if (task.kind == CalibrationKind.ARM && task.phase == CalibrationPhase.UNCERTAIN) {
                        CalibrationArmPreparation()
                        Button({ request(CalibrationAction.RETRY_ARM) }, Modifier.heightIn(min = 48.dp).testTag("calibration-arm-retry"),
                            enabled = canChange && !ui.busy && armOnline, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.calibration_arm_retry)) }
                    }
                    if (task.observingInterrupted && task.phase == CalibrationPhase.UNCERTAIN) {
                        if (task.imuStatusRaw == 3 || task.gimbalStatusRaw == 1) Text(tr(Res.string.calibration_device_busy), fontSize = 15.sp)
                        Button({ request(CalibrationAction.RESTARTED) }, Modifier.heightIn(min = 48.dp).testTag("calibration-restarted"),
                            enabled = canChange && !ui.busy && task.imuStatusRaw != 3 && task.gimbalStatusRaw != 1) { Text(tr(Res.string.calibration_restart_action)) }
                    }
                } else if (selected != null) {
                    val kind = checkNotNull(selected)
                    Text(calibrationKindName(kind), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    when (kind) {
                        CalibrationKind.CHASSIS_AND_GIMBAL -> {
                            Text(tr(Res.string.calibration_prepare_combined), fontSize = 15.sp)
                            if (!gimbalOnline) Text(tr(Res.string.calibration_no_gimbal), fontSize = 15.sp)
                        }
                        CalibrationKind.GIMBAL -> Text(tr(Res.string.calibration_prepare_gimbal), fontSize = 15.sp)
                        CalibrationKind.ARM -> CalibrationArmPreparation()
                    }
                    if (!canChange) Text(tr(Res.string.calibration_task_active), fontSize = 15.sp)
                    if (health.modules == null) Text(tr(Res.string.calibration_wait_modules), fontSize = 15.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ request(CalibrationAction.START) }, Modifier.heightIn(min = 48.dp).testTag("calibration-start"),
                            enabled = canChange && available(kind) && !ui.busy, colors = ButtonDefaults.buttonColorsPrimary()) {
                            Text(tr(if (kind == CalibrationKind.ARM) Res.string.calibration_arm_confirm_action else Res.string.calibration_start))
                        }
                        Button({ selected = null; chooseAgain = true }, Modifier.heightIn(min = 48.dp).testTag("calibration-choose-another")) { Text(tr(Res.string.calibration_choose_another)) }
                    }
                } else if (task.kind != null && task.phase != CalibrationPhase.IDLE && !chooseAgain) {
                    Text(tr(when (task.phase) {
                        CalibrationPhase.SUCCEEDED -> Res.string.calibration_report_finished
                        CalibrationPhase.FAILED -> Res.string.calibration_report_failed
                        CalibrationPhase.ARM_ACKNOWLEDGED -> Res.string.calibration_arm_ack
                        else -> if (task.restartConfirmedByOperator) Res.string.calibration_operator_restart
                            else if (task.stopAcknowledged) Res.string.calibration_stop_ack else Res.string.calibration_idle_observed
                    }), Modifier.testTag("calibration-result"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(tr(if (task.kind == CalibrationKind.ARM) Res.string.calibration_arm_check else Res.string.calibration_result_check), fontSize = 15.sp)
                    Button({ chooseAgain = true }, Modifier.heightIn(min = 48.dp).testTag("calibration-choose-another")) { Text(tr(Res.string.calibration_choose_another)) }
                } else {
                    Text(tr(Res.string.calibration_choose), fontSize = 16.sp)
                    if (health.modules == null) Text(tr(Res.string.calibration_wait_modules), fontSize = 15.sp)
                    listOf(CalibrationKind.CHASSIS_AND_GIMBAL).plus(if (gimbalOnline) listOf(CalibrationKind.GIMBAL) else emptyList())
                        .plus(if (armOnline) listOf(CalibrationKind.ARM) else emptyList()).forEach { kind ->
                            Button({ selected = kind }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("calibration-kind-" + kind.name.lowercase())) {
                                Text(calibrationKindName(kind))
                            }
                        }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (ui.connected && task.canStopImu && (ui.recovery == null || task.observingInterrupted)) Button({ request(CalibrationAction.STOP) },
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("calibration-stop"), enabled = !ui.canceling) { Text(tr(Res.string.calibration_stop)) }
    }
}

@Composable
private fun ColumnScope.CalibrationTaskContent(controller: RobotCalibrationController, ui: CalibrationUiState, canChange: Boolean) {
    val task = ui.task
    task.reportAgeSeconds?.let { Text(tr(Res.string.calibration_report_age, it), fontSize = 14.sp) }
    when {
        task.phase == CalibrationPhase.WAITING_LEVEL -> {
            Text(tr(Res.string.calibration_return_level), Modifier.testTag("calibration-level"), fontSize = 16.sp)
            Text(tr(if (ui.autoContinue) Res.string.calibration_auto_next else Res.string.calibration_manual_next), fontSize = 15.sp)
            if (!ui.autoContinue) Button({ controller.continueGimbal(ui.connection) }, Modifier.heightIn(min = 48.dp).testTag("calibration-continue-gimbal"),
                enabled = canChange && !ui.busy && task.levelReady, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.calibration_continue_gimbal)) }
        }
        task.gimbalMayBeActive -> {
            Text(tr(Res.string.calibration_gimbal_running), fontSize = 15.sp)
            Text(task.gimbalProgress?.let { tr(Res.string.calibration_progress, it) } ?: tr(if (task.gimbalProgressRaw == null)
                Res.string.calibration_wait_report else Res.string.calibration_progress_unknown),
                Modifier.testTag("calibration-progress"), fontSize = 16.sp)
            Text(tr(Res.string.calibration_no_gimbal_stop), fontSize = 15.sp)
        }
        task.kind == CalibrationKind.CHASSIS_AND_GIMBAL -> {
            val current = task.imuSide
            Text(task.imuProgress?.let { tr(Res.string.calibration_progress, it) } ?: tr(if (task.imuProgressRaw == null)
                Res.string.calibration_wait_report else Res.string.calibration_progress_unknown),
                Modifier.testTag("calibration-progress"), fontSize = 16.sp)
            if (current != null) Text(tr(Res.string.calibration_current_pose, calibrationSideName(current)),
                Modifier.testTag("calibration-current-pose"), fontSize = 16.sp)
            val next = if (current == null) ImuCalibrationSide.HORIZONTAL else if (current in task.finishedSides)
                ImuCalibrationSide.entries.firstOrNull { it !in task.finishedSides } else current
            Text(if (next == null) tr(Res.string.calibration_wait_finish) else if (next == current) tr(Res.string.calibration_hold_pose)
                else tr(Res.string.calibration_next_pose, calibrationSideName(next)), Modifier.testTag("calibration-pose-guidance"), fontSize = 15.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ImuCalibrationSide.entries.forEach { side ->
                    Column(Modifier.background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(12.dp)
                        .testTag("calibration-side-" + side.name.lowercase()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(calibrationSideName(side), fontSize = 15.sp)
                        Text(tr(if (side in task.finishedSides) Res.string.calibration_side_done else Res.string.calibration_side_pending), fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        task.kind == CalibrationKind.ARM -> Text(tr(Res.string.calibration_arm_wait), fontSize = 15.sp)
    }
}

@Composable
private fun ColumnScope.CalibrationArmPreparation() {
    Text(tr(Res.string.calibration_arm_pose), fontSize = 15.sp)
    Text(tr(Res.string.calibration_arm_first), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text(tr(Res.string.calibration_arm_second), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text(tr(Res.string.calibration_arm_keep), fontSize = 15.sp)
}

@Composable internal fun calibrationKindName(kind: CalibrationKind): String = tr(when (kind) {
    CalibrationKind.CHASSIS_AND_GIMBAL -> Res.string.calibration_combined
    CalibrationKind.GIMBAL -> Res.string.calibration_gimbal
    CalibrationKind.ARM -> Res.string.calibration_arm
})
@Composable private fun calibrationSideName(side: ImuCalibrationSide): String = tr(when (side) {
    ImuCalibrationSide.HORIZONTAL -> Res.string.calibration_horizontal
    ImuCalibrationSide.FRONT -> Res.string.calibration_front
    ImuCalibrationSide.LEFT -> Res.string.calibration_left
    ImuCalibrationSide.BACK -> Res.string.calibration_back
    ImuCalibrationSide.RIGHT -> Res.string.calibration_right
})
private fun calibrationProblemText(problem: CalibrationProblem): StringResource = when (problem) {
    CalibrationProblem.REQUEST_UNKNOWN -> Res.string.calibration_unknown
    CalibrationProblem.REPORT_UNKNOWN -> Res.string.calibration_report_unknown
    CalibrationProblem.REPORT_STALE -> Res.string.calibration_report_stale
    CalibrationProblem.GIMBAL_TIMEOUT -> Res.string.calibration_timeout
    CalibrationProblem.STOP_UNCONFIRMED -> Res.string.calibration_stop_unknown
    CalibrationProblem.MODULE_CHANGED -> Res.string.calibration_module_changed
}
