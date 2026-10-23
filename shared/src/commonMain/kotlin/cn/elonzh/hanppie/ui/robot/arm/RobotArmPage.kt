package cn.elonzh.hanppie.ui.robot.arm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.extensions.arm.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.*
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class ArmAction { INSTALL, UNINSTALL, LOAD_ON, LOAD_OFF, EXIT }
private data class ArmPrompt(val action: ArmAction, val connection: Long, val binding: ArmServoBinding?,
    val mode: Int?, val servos: Set<Int>?, val selected: ArmServoBinding?)

@Composable
internal fun RobotArmPage(model: ConsoleController, modifier: Modifier = Modifier, onBack: () -> Unit,
    onAddressing: () -> Unit, onCalibration: () -> Unit, exitRequested: Boolean = false,
    onExitCancelled: () -> Unit = {}, onExitComplete: () -> Unit = onBack) {
    val controller = model.robotArm
    val ui by controller.state.collectAsState()
    val console by model.state.collectAsState()
    val foreground by model.foregroundState.collectAsState()
    val camera by model.robotCamera.state.collectAsState()
    val album by model.robotAlbum.state.collectAsState()
    val chat by model.chat.state.collectAsState()
    val addressing by model.robotAddressing.state.collectAsState()
    val calibration by model.robotCalibration.state.collectAsState()
    val firmware by model.robotFirmware.state.collectAsState()
    val task = ui.task
    val binding = task.binding
    val canChange = ui.connected && console.connected && foreground && !console.busy && !ui.busy &&
        !console.scriptRunPhase.mayBeExecuting && !chat.running && !camera.storageBusy && camera.storage?.recording != true &&
        !album.nativeBusy && !addressing.blocksControl && !calibration.blocksControl && !firmware.blocksControl
    var main by remember(ui.connection) { mutableStateOf<Int?>(null) }
    var secondary by remember(ui.connection) { mutableStateOf<Int?>(null) }
    var guide by remember(ui.connection) { mutableStateOf(false) }
    var details by remember(ui.connection) { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<ArmPrompt?>(null) }
    LaunchedEffect(ui.connection, binding) {
        if (binding != null) { main = binding.main.takeIf { binding.installed }; secondary = binding.secondary.takeIf { binding.installed } }
    }
    val selected = main?.let { a -> secondary?.let { b -> ArmServoBinding(a, b) } }
    fun request(action: ArmAction) { prompt = ArmPrompt(action, ui.connection, binding, task.loadModeRaw, task.servoIds, selected) }
    fun dismiss() { if (prompt?.action == ArmAction.EXIT) onExitCancelled(); prompt = null }
    LaunchedEffect(exitRequested, ui.changing) {
        if (exitRequested) {
            if (!ui.changing) { onExitCancelled(); onExitComplete() }
            else if (prompt?.action != ArmAction.EXIT) request(ArmAction.EXIT)
        }
    }
    LaunchedEffect(ui.connection, ui.connected, binding, task.loadModeRaw, task.servoIds, selected, canChange) {
        prompt?.let { p ->
            if (p.connection != ui.connection || !ui.connected || p.action != ArmAction.EXIT &&
                (!canChange || p.binding != binding || p.mode != task.loadModeRaw || p.servos != task.servoIds || p.selected != selected)) dismiss()
        }
    }
    val pending = prompt
    WorkbenchDialog(show = pending != null, onDismissRequest = ::dismiss, title = tr(when (pending?.action) {
        ArmAction.INSTALL -> Res.string.arm_install_question
        ArmAction.UNINSTALL -> Res.string.arm_uninstall_question
        ArmAction.LOAD_ON -> Res.string.arm_load_on_question
        ArmAction.LOAD_OFF -> Res.string.arm_load_off_question
        else -> Res.string.arm_leave_question
    }), summary = when (pending?.action) {
        ArmAction.INSTALL -> tr(Res.string.arm_install_summary, pending.selected?.main ?: 0, pending.selected?.secondary ?: 0)
        ArmAction.UNINSTALL -> tr(Res.string.arm_uninstall_summary)
        ArmAction.LOAD_ON -> tr(Res.string.arm_load_on_summary)
        ArmAction.LOAD_OFF -> tr(Res.string.arm_load_off_summary)
        else -> tr(Res.string.arm_leave_summary)
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(::dismiss, Modifier.weight(1f).heightIn(min = 48.dp).testTag("arm-confirm-cancel")) { Text(tr(Res.string.cancel)) }
            Button({
                val p = prompt ?: return@Button; prompt = null
                val current = controller.state.value
                if (current.connection != p.connection || !current.connected) return@Button
                if (p.action == ArmAction.EXIT) { controller.setActive(false); onExitCancelled(); onExitComplete(); return@Button }
                val expected = p.binding ?: return@Button
                if (current.task.binding != expected || current.task.loadModeRaw != p.mode || current.task.servoIds != p.servos) return@Button
                when (p.action) {
                    ArmAction.INSTALL -> p.selected?.let { controller.setBinding(it, expected, p.connection) }
                    ArmAction.UNINSTALL -> controller.setBinding(ArmServoBinding.Uninstalled, expected, p.connection)
                    ArmAction.LOAD_ON -> controller.setLoadMode(ArmLoadMode.ON_LOAD, expected, p.mode, p.connection)
                    ArmAction.LOAD_OFF -> controller.setLoadMode(ArmLoadMode.FREE, expected, p.mode, p.connection)
                    ArmAction.EXIT -> Unit
                }
            }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("arm-confirm"), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(tr(when (pending?.action) {
                    ArmAction.INSTALL -> Res.string.arm_bind
                    ArmAction.UNINSTALL -> Res.string.arm_uninstall
                    ArmAction.LOAD_ON -> Res.string.arm_load_on
                    ArmAction.LOAD_OFF -> Res.string.arm_load_off
                    else -> Res.string.arm_leave
                }))
            }
        }
    }
    val colors = MiuixTheme.colorScheme
    Column(modifier.fillMaxSize().background(colors.background).testTag("arm-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchIconButton(tr(Res.string.back), WorkbenchGlyph.BACK, onClick = onBack, tag = "arm-back")
            Text(tr(Res.string.arm_title), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("arm-scroll"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = HanppieDesignTokens.PageMaxWidth).fillMaxWidth().padding(HanppieDesignTokens.PagePaddingCompact),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ui.interruptedAddress?.let { address ->
                    Text(tr(Res.string.arm_previous_change, address), Modifier.testTag("arm-interrupted"), color = colors.error)
                    Button(controller::acknowledgePreviousChange, Modifier.heightIn(min = 48.dp)) { Text(tr(Res.string.arm_acknowledge)) }
                }
                if (!ui.connected) Text(tr(Res.string.arm_not_connected), Modifier.testTag("arm-page-status"))
                else {
                    Text(tr(Res.string.arm_online_status, tr(if (task.armOnline) Res.string.arm_online else Res.string.arm_not_online),
                        tr(if (task.clawOnline) Res.string.arm_online else Res.string.arm_not_online)), Modifier.testTag("arm-page-status"), fontSize = 16.sp)
                    Text(tr(if (ui.changing) Res.string.arm_updating else if (ui.working) Res.string.arm_reading else when (task.observation) {
                        ArmObservation.ACTIVE -> Res.string.arm_status_connected
                        ArmObservation.FAILED -> Res.string.arm_observation_required
                        else -> Res.string.arm_status_wait
                    }), color = colors.onSurfaceVariantSummary)
                    Button({ controller.refresh(ui.connection) }, Modifier.heightIn(min = 48.dp).testTag("arm-refresh"), enabled = canChange) {
                        Text(tr(Res.string.arm_read_status))
                    }
                }
                (ui.error ?: task.problem?.let(::armProblemMessage))?.let { Text(it.resolve(), Modifier.testTag("arm-error"), color = colors.error) }
                if (ui.connected && !canChange && !ui.busy) Text(tr(Res.string.arm_task_active), color = colors.onSurfaceVariantSummary)
                Text(tr(Res.string.arm_installation), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (ui.connected) Text(when {
                    binding?.installed == true -> tr(Res.string.arm_binding_current, binding.main, binding.secondary)
                    binding?.uninstalled == true -> tr(Res.string.arm_uninstalled)
                    else -> tr(Res.string.arm_binding_wait)
                }, Modifier.testTag("arm-binding"))
                if (task.requestedBinding != null) Text(tr(when {
                    task.bindingMatchesRequest == true -> Res.string.arm_binding_verified
                    task.bindingAcknowledged == true -> Res.string.arm_binding_pending
                    task.bindingAcknowledged == false -> Res.string.arm_rejected
                    else -> Res.string.arm_result_unknown
                }), Modifier.testTag("arm-binding-result"), color = colors.onSurfaceVariantSummary)
                Button({ guide = !guide }, Modifier.heightIn(min = 48.dp).testTag("arm-guide-toggle")) { Text(tr(Res.string.arm_install_guide)) }
                if (guide) {
                    Text(tr(Res.string.arm_wiring_guide), Modifier.testTag("arm-guide"))
                    Button(onAddressing, Modifier.heightIn(min = 48.dp).testTag("arm-addressing"), enabled = !ui.busy && foreground) { Text(tr(Res.string.addressing_title)) }
                }
                if (task.servoIds?.isNotEmpty() == true || binding?.installed == true) {
                    ServoChoice(Res.string.arm_main_servo, "main", task.servoIds.orEmpty(), main, canChange,
                        { main = it }, { controller.identifyServo(it, ui.connection) }, task.identifyingServo)
                    ServoChoice(Res.string.arm_secondary_servo, "secondary", task.servoIds.orEmpty(), secondary, canChange,
                        { secondary = it }, { controller.identifyServo(it, ui.connection) }, task.identifyingServo)
                    if (selected != null && !selected.installed) Text(tr(Res.string.arm_choose_different), color = colors.onSurfaceVariantSummary)
                    Button({ request(ArmAction.INSTALL) }, Modifier.heightIn(min = 48.dp).testTag("arm-bind"),
                        enabled = canChange && binding != null && selected?.installed == true && selected != binding && task.servoIds.orEmpty().containsAll(setOf(selected.main, selected.secondary)),
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.arm_bind)) }
                    if (binding?.installed == true) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onCalibration, Modifier.heightIn(min = 48.dp).testTag("arm-calibration"), enabled = canChange && task.armOnline) { Text(tr(Res.string.calibration_title)) }
                            Button({ request(ArmAction.UNINSTALL) }, Modifier.heightIn(min = 48.dp).testTag("arm-uninstall"), enabled = canChange && task.armOnline) { Text(tr(Res.string.arm_uninstall)) }
                        }
                    }
                } else if (ui.connected) Text(tr(Res.string.arm_connect_servos), color = colors.onSurfaceVariantSummary)
                if (task.armOnline) {
                    Text(tr(Res.string.arm_load_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(tr(when (task.loadModeRaw) { 0 -> Res.string.arm_load_free; 1 -> Res.string.arm_load_enabled; 255 -> Res.string.arm_mode_unsupported; else -> Res.string.arm_load_wait }),
                        Modifier.testTag("arm-load-status"))
                    if (task.requestedLoadMode != null && task.loadModeMatchesRequest != true) Text(tr(if (task.loadModeAcknowledged == true)
                        Res.string.arm_load_pending else Res.string.arm_result_unknown), Modifier.testTag("arm-load-result"))
                    Text(tr(Res.string.arm_load_help), color = colors.onSurfaceVariantSummary)
                    if (task.loadModeRaw != 255) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ request(ArmAction.LOAD_ON) }, Modifier.heightIn(min = 48.dp).testTag("arm-load-on"), enabled = canChange && binding?.installed == true) { Text(tr(Res.string.arm_load_on)) }
                        Button({ request(ArmAction.LOAD_OFF) }, Modifier.heightIn(min = 48.dp).testTag("arm-load-off"), enabled = canChange && binding?.installed == true) { Text(tr(Res.string.arm_load_off)) }
                    }
                }
                if (ui.connected) {
                    Button({ details = !details }, Modifier.heightIn(min = 48.dp).testTag("arm-details-toggle")) { Text(tr(Res.string.arm_details)) }
                    if (details) {
                        Text(tr(Res.string.arm_servo_ids, task.servoIds?.sorted()?.joinToString() ?: "—"))
                        Text(tr(Res.string.arm_position_raw, task.position?.xRaw?.toString() ?: "—", task.position?.yRaw?.toString() ?: "—",
                            task.positionAgeSeconds?.toString() ?: "—"), Modifier.testTag("arm-position"))
                        Text(tr(Res.string.arm_flags_raw, task.position?.flagsRaw?.toString() ?: "—", task.clawStatusRaw?.toString() ?: "—",
                            task.clawWarnings?.raw?.toString() ?: "—"))
                        Text(tr(Res.string.arm_mode_raw, task.loadModeRaw?.toString() ?: "—", task.loadModeAgeSeconds?.toString() ?: "—"))
                    }
                }
            }
        }
    }
}

@Composable
private fun ServoChoice(label: org.jetbrains.compose.resources.StringResource, tag: String, online: Set<Int>, selected: Int?, enabled: Boolean,
    choose: (Int) -> Unit, identify: (Int) -> Unit, identifying: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(label), fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (id in 1..3) {
                val chosen = selected == id
                val description = tr(label) + " " + id
                Button({ choose(id) }, Modifier.heightIn(min = 48.dp).testTag("arm-$tag-$id")
                    .semantics { role = Role.RadioButton; this.selected = chosen; contentDescription = description }, enabled = enabled && id in online,
                    colors = if (chosen) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) { Text(id.toString()) }
            }
            if (selected != null) Button({ identify(selected) }, Modifier.heightIn(min = 48.dp).testTag("arm-identify-$tag"), enabled = enabled && selected in online) {
                Text(tr(if (identifying == selected) Res.string.arm_identifying else Res.string.arm_identify))
            }
        }
    }
}
