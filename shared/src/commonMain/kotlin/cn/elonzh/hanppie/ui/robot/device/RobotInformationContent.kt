package cn.elonzh.hanppie.ui.robot.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.rotate
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.product.RobotVersionTarget
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun versionTargetName(target: RobotVersionTarget): String = when (target) {
    RobotVersionTarget.SYSTEM -> tr(Res.string.robot_info_system)
    RobotVersionTarget.SYSTEM_CAN -> tr(Res.string.robot_info_can)
    RobotVersionTarget.PROGRAM_RUNTIME -> tr(Res.string.robot_info_runtime)
    RobotVersionTarget.GIMBAL_MOTOR -> tr(Res.string.robot_info_gimbal_motor)
    RobotVersionTarget.VISION -> tr(Res.string.robot_info_vision)
    RobotVersionTarget.PERCEPTION -> tr(Res.string.robot_info_perception)
    else -> robotModuleName(checkNotNull(target.componentId))
}

/** Read-only identity and versions belong to the robot status page. */
@Composable
internal fun RobotInformationContent(model: ConsoleController) {
    val console by model.state.collectAsState()
    val controller = model.robotInformation
    val state by controller.state.collectAsState()
    val health by model.robotHealth.state.collectAsState()
    var expanded by remember(state.connection) { mutableStateOf(false) }
    LaunchedEffect(controller, state.connection, state.connected, console.connected, state.versions.keys) {
        if (state.connected && console.connected) controller.refresh()
    }
    DisposableEffect(controller) { onDispose { controller.cancelQueries() } }
    if (!console.connected) return
    cn.elonzh.hanppie.ui.settings.SettingsGroup(tr(Res.string.robot_info_title)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("robot-information"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val fields = buildList {
                add(InformationField(tr(Res.string.robot_info_model), console.productName, "model"))
                add(InformationField(tr(Res.string.robot_info_activation), when (val activation = state.activation) {
                    is InformationValue.Value -> tr(if (activation.value) Res.string.robot_info_activated else Res.string.robot_info_not_activated)
                    InformationValue.Loading -> tr(Res.string.robot_info_reading)
                    else -> "—"
                }, "activation"))
                val firmware = state.versions[RobotVersionTarget.SYSTEM]
                add(InformationField(tr(Res.string.robot_info_firmware), when (firmware) {
                    is InformationValue.Value -> firmware.value
                    InformationValue.Loading -> tr(Res.string.robot_info_reading)
                    else -> "—"
                }, "SYSTEM"))
                state.usage?.let { usage ->
                    usage.mileageMetres?.let { add(InformationField(tr(Res.string.robot_info_mileage_label), tr(Res.string.robot_info_metres, it), "mileage")) }
                    usage.drivingSeconds?.let { add(InformationField(tr(Res.string.robot_info_driving_label), tr(Res.string.robot_info_seconds, it), "driving")) }
                }
            }
            InformationGrid(fields)
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).testTag("robot-info-components")
                    .clickable(role = Role.Button) { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr(Res.string.robot_info_components), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        Modifier.size(18.dp).rotate(if (expanded) 90f else 0f))
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(24.dp).testTag("robot-info-loading"))
                    else if (state.connected) cn.elonzh.hanppie.ui.design.WorkbenchIconButton(tr(Res.string.refresh),
                        cn.elonzh.hanppie.ui.design.WorkbenchGlyph.REFRESH, controller::refresh, tag = "robot-info-refresh")
                }
            }
            if (expanded) {
                val online = health.modules.orEmpty().map { it.deviceId }.toSet()
                val ids = (online + health.unreportedDeviceIds + health.issues.map { it.affectedDeviceId }).sorted()
                ids.forEachIndexed { index, id ->
                    if (index > 0) HorizontalDivider()
                    Column(Modifier.fillMaxWidth().testTag("health-module-$id").padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(robotModuleName(id), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            val alerts = health.issues.count { it.affectedDeviceId == id && it.severity >= 2 }
                            Text(tr(when { alerts > 0 -> Res.string.health_alerts
                                id in online -> Res.string.health_online; else -> Res.string.health_unreported }, alerts),
                                fontSize = 13.sp, color = if (alerts > 0 || id !in online) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        val versions = state.versions.filterKeys { it.componentId == id }
                        val readable = versions.filterValues { it is InformationValue.Value || it == InformationValue.Loading }
                        if (readable.isEmpty()) Text(tr(Res.string.robot_info_version_unavailable), fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        readable.forEach { (target, value) ->
                            ComponentVersion(target, value, versions.size > 1)
                        }
                    }
                }
                // System software has no physical module report; show only versions the device actually returned.
                state.versions.filter { it.key.componentId == null && it.key != RobotVersionTarget.SYSTEM && it.value is InformationValue.Value }
                    .forEach { (target, value) -> HorizontalDivider(); ComponentVersion(target, value, true) }
            }
        }
    }
}

@Composable
private fun ComponentVersion(target: RobotVersionTarget, value: InformationValue<String>, showName: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp).testTag("robot-info-${target.name}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (showName) versionTargetName(target) else tr(Res.string.robot_info_firmware), Modifier.weight(1f),
            fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Box(Modifier.widthIn(min = 104.dp), contentAlignment = Alignment.CenterEnd) {
            if (value == InformationValue.Loading) CircularProgressIndicator(Modifier.size(16.dp))
            else Text((value as? InformationValue.Value)?.value ?: "—", Modifier.testTag("robot-info-value-${target.name}"), fontSize = 13.sp)
        }
    }
}

private data class InformationField(val label: String, val value: String, val tag: String)

@Composable
private fun InformationGrid(fields: List<InformationField>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 400.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields.chunked(columns).forEach { fieldsInRow ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    fieldsInRow.forEach { field -> InformationCell(field.label, field.value, field.tag, Modifier.weight(1f)) }
                    repeat(columns - fieldsInRow.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun InformationCell(label: String, value: String, tag: String, modifier: Modifier = Modifier) {
    Column(modifier.heightIn(min = 52.dp).testTag("robot-info-$tag"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, Modifier.testTag("robot-info-value-$tag"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun <T> InformationRow(label: String, value: InformationValue<T>, tag: String, query: () -> Unit,
    enabled: Boolean = true, subject: String = label, render: @Composable (T) -> String) {
    val summary = when (value) {
        InformationValue.Unread -> tr(Res.string.robot_info_unread)
        InformationValue.Loading -> tr(Res.string.robot_info_reading)
        is InformationValue.Value -> render(value.value)
        is InformationValue.Failed -> value.message.resolve()
    }
    val action = tr(if (value is InformationValue.Failed) Res.string.robot_info_retry_target else Res.string.robot_info_query_target, subject)
    Row(Modifier.fillMaxWidth().testTag("robot-info-$tag"), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(summary, Modifier.testTag("robot-info-value-$tag"), fontSize = 14.sp,
            color = if (value is InformationValue.Failed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface)
        }
        if (value is InformationValue.Failed) Button(query, Modifier.heightIn(min = 48.dp).testTag("robot-info-query-$tag")
            .semantics { contentDescription = action; stateDescription = summary }, enabled = enabled) {
            Text(tr(Res.string.album_retry))
        }
    }
}
