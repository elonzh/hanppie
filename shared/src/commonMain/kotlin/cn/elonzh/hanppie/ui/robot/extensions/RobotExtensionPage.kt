package cn.elonzh.hanppie.ui.robot.extensions

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
import cn.elonzh.hanppie.robot.extensions.*
import cn.elonzh.hanppie.robot.product.RobotVersionTarget
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.*
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.device.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
internal fun RobotExtensionPage(model: ConsoleController, modifier: Modifier = Modifier, onBack: () -> Unit,
    onAddressing: () -> Unit) {
    val controller = model.robotExtensions
    val ui by controller.state.collectAsState()
    val console by model.state.collectAsState()
    val foreground by model.foregroundState.collectAsState()
    val chat by model.chat.state.collectAsState()
    val addressing by model.robotAddressing.state.collectAsState()
    val calibration by model.robotCalibration.state.collectAsState()
    val firmware by model.robotFirmware.state.collectAsState()
    val information by model.robotInformation.state.collectAsState()
    val canUse = ui.connected && console.connected && foreground && (!console.busy || ui.changing) &&
        !console.scriptRunPhase.mayBeExecuting && !chat.running && !addressing.blocksControl && !calibration.blocksControl && !firmware.blocksControl
    val selected = ui.selected
    val reading = selected?.let { ui.task.readings[it] }
    val target = selected?.let { component -> RobotVersionTarget.entries.firstOrNull { it.componentId == component.deviceId } }
    val hasVersionTarget = target != null && target in information.versions
    var details by remember(ui.connection, selected) { mutableStateOf(false) }
    LaunchedEffect(ui.connection, selected, hasVersionTarget, canUse) {
        if (canUse && selected != null && target != null && information.versions[target] == InformationValue.Unread)
            model.robotInformation.readVersion(target)
    }
    val colors = MiuixTheme.colorScheme
    Column(modifier.fillMaxSize().background(colors.background).testTag("extensions-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            WorkbenchIconButton(tr(Res.string.back), WorkbenchGlyph.BACK, onClick = onBack, tag = "extensions-back")
            Text(if (selected == null) tr(Res.string.extensions_title) else tr(Res.string.extensions_selected_title,
                tr(Res.string.extensions_title), robotModuleName(selected.deviceId)), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("extensions-scroll"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = HanppieDesignTokens.PageMaxWidth).fillMaxWidth().padding(HanppieDesignTokens.PagePaddingCompact),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(tr(when {
                    !ui.connected -> Res.string.extensions_not_connected
                    !canUse -> Res.string.extensions_paused
                    ui.task.online == null -> Res.string.extensions_modules_wait
                    else -> Res.string.extensions_choose_module
                }), Modifier.testTag("extensions-page-status"), fontSize = 16.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExtensionKind.entries.forEach { family ->
                        val label = tr(Res.string.extensions_family_count, tr(when (family) {
                            ExtensionKind.SERVO -> Res.string.extensions_servo
                            ExtensionKind.TOF -> Res.string.extensions_tof
                            ExtensionKind.ADAPTER -> Res.string.extensions_adapter
                        }), ui.task.online?.count { family.index(it) != null }?.toString() ?: "—")
                        Button({ controller.selectFamily(family, ui.connection) }, Modifier.heightIn(min = 48.dp)
                            .testTag("extensions-family-${family.name}").semantics { this.selected = ui.family == family; role = Role.Tab },
                            colors = if (ui.family == family) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                            Text(label, fontSize = 15.sp)
                        }
                    }
                }
                ui.error?.let { Text(it.resolve(), Modifier.testTag("extensions-error"), color = colors.error) }
                val online = ui.task.online?.filter { ui.family.index(it) != null }?.sortedBy { ui.family.index(it) }
                if (online?.isEmpty() == true) Text(tr(Res.string.extensions_empty), Modifier.testTag("extensions-empty"))
                if (!online.isNullOrEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    online.forEach { component ->
                        val label = robotModuleName(component.deviceId)
                        Button({ controller.select(component, ui.connection) }, Modifier.heightIn(min = 48.dp)
                            .testTag("extensions-module-${component.name}").semantics { this.selected = ui.selected == component; role = Role.RadioButton; contentDescription = label },
                            enabled = canUse, colors = if (selected == component) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                            Text(label, fontSize = 15.sp)
                        }
                    }
                }
                if (selected != null) {
                    Text(robotModuleName(selected.deviceId), Modifier.testTag("extensions-selected"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    val active = ui.task.observation == ExtensionObservation.ACTIVE
                    Text(when {
                        ui.busy -> tr(if (ui.changing) Res.string.arm_identifying else Res.string.extensions_receiving)
                        !canUse -> tr(Res.string.extensions_paused)
                        !active -> tr(Res.string.extensions_reception_off)
                        reading == null -> tr(Res.string.extensions_report_wait)
                        !ui.task.fresh -> tr(Res.string.extensions_stale, ui.task.ageSeconds ?: 0)
                        else -> tr(Res.string.extensions_age, ui.task.ageSeconds ?: 0)
                    }, Modifier.testTag("extensions-data-status"), color = colors.onSurfaceVariantSummary)
                    if (ui.error == null) ui.task.problem?.let { Text(extensionProblemMessage(it).resolve(), color = colors.error) }
                    if (!active && !ui.busy) Button({ controller.retry(ui.connection) }, Modifier.heightIn(min = 48.dp)
                        .testTag("extensions-retry"), enabled = canUse) { Text(tr(Res.string.arm_retry_status)) }
                    if (reading != null) {
                        when (reading) {
                            is ServoReading -> if (reading.valid) {
                                Text(tr(Res.string.extensions_angle, ((reading.angleDegrees * 10).roundToInt() / 10.0).toString()), Modifier.testTag("extensions-servo-angle"))
                                Text(tr(Res.string.extensions_speed_raw, reading.speedRaw), Modifier.testTag("extensions-servo-speed"))
                            } else Text(tr(Res.string.extensions_servo_invalid), Modifier.testTag("extensions-servo-invalid"))
                            is TofReading -> Text(tr(Res.string.extensions_distance, reading.distanceMillimetres), Modifier.testTag("extensions-tof-distance"))
                            is AdapterReading -> {
                                Text(tr(Res.string.extensions_port, 1, reading.io1, reading.adc1), Modifier.testTag("extensions-adapter-port1"))
                                Text(tr(Res.string.extensions_port, 2, reading.io2, reading.adc2), Modifier.testTag("extensions-adapter-port2"))
                            }
                        }
                        if (reading !is AdapterReading) {
                            Button({ details = !details }, Modifier.heightIn(min = 48.dp).testTag("extensions-raw-toggle")) {
                                Text(tr(if (details) Res.string.extensions_hide_raw else Res.string.extensions_show_raw))
                            }
                            if (details) Text(when (reading) {
                                is ServoReading -> tr(Res.string.extensions_servo_raw, if (reading.valid) 1 else 0, reading.speedRaw, reading.angleRaw)
                                is TofReading -> tr(Res.string.extensions_tof_raw, reading.commandRaw, reading.directionRaw, reading.flagRaw)
                            }, Modifier.testTag("extensions-raw"), fontSize = 14.sp)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ controller.identify(ui.connection) }, Modifier.heightIn(min = 48.dp).testTag("extensions-identify"),
                            enabled = canUse && !ui.busy && ui.task.identifying != selected) { Text(tr(Res.string.arm_identify)) }
                        if (ui.task.identifying != null) Button(controller::stopIdentification, Modifier.heightIn(min = 48.dp)
                            .testTag("extensions-stop-identify")) { Text(tr(Res.string.extensions_stop_identify)) }
                    }
                    if (ui.task.identifying == selected) Text(tr(Res.string.arm_identifying), Modifier.testTag("extensions-identifying"), fontSize = 14.sp)
                    if (target != null && hasVersionTarget) InformationRow(tr(Res.string.extensions_version), information.versions[target] ?: InformationValue.Unread,
                        "extension-${target.name}", { model.robotInformation.readVersion(target) }, enabled = canUse && !ui.busy,
                        subject = "${robotModuleName(selected.deviceId)} · ${tr(Res.string.extensions_version)}") { it }
                }
                Button(onAddressing, Modifier.heightIn(min = 48.dp).testTag("extensions-addressing"), enabled = foreground && !ui.busy) {
                    Text(tr(Res.string.addressing_title))
                }
            }
        }
    }
}
