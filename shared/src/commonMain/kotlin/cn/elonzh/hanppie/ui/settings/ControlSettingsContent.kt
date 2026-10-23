package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.remote.ChassisParameter
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.remote.MotionSettingsPhase
import cn.elonzh.hanppie.ui.robot.remote.RobotMotionController
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
internal fun ControlSettingsContent(model: ConsoleController) {
    val control by model.controlSettings.collectAsState()
    val busy by model.settingsBusy.collectAsState()
    var keyboard by remember { mutableStateOf(false) }
    val profile = if (keyboard) control.inputSensitivities.keyboard else control.inputSensitivities.screen
    fun change(value: InputSensitivity) {
        model.settingsMessage.value = null
        model.controlSettings.value = control.copy(inputSensitivities = if (keyboard)
            control.inputSensitivities.copy(keyboard = value) else control.inputSensitivities.copy(screen = value))
    }
    SettingsDropdown(tr(Res.string.input_profile), "input-profile", keyboard.toString(),
        listOf("false" to tr(Res.string.input_screen), "true" to tr(Res.string.input_keyboard)), !busy) { keyboard = it.toBoolean() }
    SettingsDropdown(tr(Res.string.input_response), "input-response", profile.preset.name,
        SensitivityPreset.entries.map { it.name to tr(if (it == SensitivityPreset.STANDARD) Res.string.input_standard else Res.string.input_custom) },
        !busy) { change(profile.copy(preset = SensitivityPreset.valueOf(it))) }
    Text(tr(Res.string.input_response_scope), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    if (profile.preset == SensitivityPreset.CUSTOM) {
        InputAxis.entries.forEach { axis ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(inputAxisLabel(axis), Modifier.weight(1f), fontSize = 14.sp)
                Text(profile.custom[axis].toString(), fontSize = 14.sp)
            }
            Slider(profile.custom[axis].toFloat(), { change(profile.copy(custom = profile.custom.with(axis, it.roundToInt()))) },
                valueRange = 0f..100f, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("input-axis-${axis.name}"))
        }
        Button({ change(profile.copy(custom = AxisSensitivity())) }, Modifier.heightIn(min = 48.dp).testTag("input-custom-default"), !busy) {
            Text(tr(Res.string.input_custom_default))
        }
    }
    Text(tr(Res.string.input_host_limits), fontSize = 15.sp)
    SettingsDropdown(tr(Res.string.gimbal_sensitivity), "gimbal-sensitivity", control.gimbalSpeed.toString(),
        listOf(15, 30, 45, 60, 90, 120).map { it.toString() to tr(Res.string.degrees_per_second_value, it) }, !busy) { value ->
        model.settingsMessage.value = null
        model.controlSettings.value = control.copy(gimbalSpeed = value.toInt())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        control.translationSpeeds.indices.forEach { index ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", Modifier.width(24.dp), style = MiuixTheme.textStyles.footnote1)
                CompactNumberField(control.translationSpeeds[index], tr(Res.string.meters_per_second), Modifier.weight(1f)) { value ->
                    val next = control.translationSpeeds.toMutableList().also { it[index] = value }
                    runCatching { control.copy(translationSpeeds = next) }.onSuccess { model.controlSettings.value = it }
                }
                CompactNumberField(control.rotationSpeeds[index], tr(Res.string.degrees_per_second), Modifier.weight(1f)) { value ->
                    val next = control.rotationSpeeds.toMutableList().also { it[index] = value }
                    runCatching { control.copy(rotationSpeeds = next) }.onSuccess { model.controlSettings.value = it }
                }
            }
        }
        CompactNumberField(control.joystickDeadZone, tr(Res.string.joystick_dead_zone), Modifier.fillMaxWidth()) { value ->
            runCatching { control.copy(joystickDeadZone = value) }.onSuccess { model.controlSettings.value = it }
        }
    }
    val connected by model.state.collectAsState()
    RobotMotionSettingsContent(model.robotMotion, connected.connected)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun RobotMotionSettingsContent(controller: RobotMotionController, connected: Boolean) {
    val state by controller.state.collectAsState()
    LaunchedEffect(controller, connected) { if (connected) controller.refresh() }
    DisposableEffect(controller) { onDispose { controller.cancelRead() } }
    Text(tr(Res.string.motion_device_title), Modifier.testTag("motion-settings-title"), fontSize = 15.sp)
    Column(Modifier.fillMaxWidth().testTag("motion-settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr(Res.string.motion_device_scope), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        state.error?.let { Text(it.resolve(), Modifier.testTag("motion-settings-error"), color = MiuixTheme.colorScheme.error, fontSize = 14.sp) }
        when {
            !connected -> Text(tr(Res.string.motion_connect_first), fontSize = 14.sp)
            state.busy -> {
                Text(tr(if (state.phase == MotionSettingsPhase.READING) Res.string.motion_reading else Res.string.motion_writing),
                    Modifier.testTag("motion-settings-busy"), fontSize = 14.sp)
                if (state.phase == MotionSettingsPhase.READING) Button(controller::cancelRead,
                    Modifier.heightIn(min = 48.dp).testTag("motion-cancel-read")) { Text(tr(Res.string.cancel)) }
            }
            else -> {
                if (state.confirmed) Text(tr(Res.string.motion_updated), Modifier.testTag("motion-settings-confirmed"), fontSize = 14.sp)
                val configuration = state.configuration
                if (configuration != null) {
                    var level by remember(configuration) { mutableStateOf(configuration.speedLevelRaw) }
                    SettingsDropdown(tr(Res.string.motion_level), "motion-level", level.toString(), (1..4).map { it.toString() to motionLevelLabel(it) }) { level = it.toInt() }
                    Text(tr(Res.string.motion_current, motionLevelLabel(configuration.speedLevelRaw)), fontSize = 14.sp)
                    Button({ controller.setSpeedLevel(level) }, Modifier.heightIn(min = 48.dp).testTag("motion-write-level"),
                        level in 1..4 && level != configuration.speedLevelRaw) { Text(tr(Res.string.motion_write_level)) }
                    var parameter by remember { mutableStateOf(ChassisParameter.FORWARD_SPEED) }
                    SettingsDropdown(tr(Res.string.motion_parameter), "motion-parameter", parameter.name,
                        ChassisParameter.entries.map { it.name to motionParameterLabel(it) }) { parameter = ChassisParameter.valueOf(it) }
                    configuration.parameters[parameter]?.let { reading ->
                        Text(tr(Res.string.motion_current, motionNumber(reading.value)), Modifier.testTag("motion-parameter-current"), fontSize = 14.sp)
                        Text(tr(Res.string.motion_range, motionNumber(reading.range.minimum), motionNumber(reading.range.maximum), motionNumber(reading.range.defaultValue)),
                            Modifier.testTag("motion-parameter-range"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (configuration.speedLevelRaw == 4) {
                            var draft by remember(configuration, parameter) { mutableStateOf(motionNumber(reading.value)) }
                            val value = draft.toFloatOrNull()?.takeIf(reading.range::accepts)
                            TextField(draft, { draft = it }, label = tr(Res.string.motion_value), singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("motion-parameter-value"))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({ draft = motionNumber(reading.range.defaultValue) }, Modifier.heightIn(min = 48.dp).testTag("motion-use-default")) {
                                    Text(tr(Res.string.motion_use_default))
                                }
                                Button({ value?.let { controller.setParameter(parameter, it) } }, Modifier.heightIn(min = 48.dp).testTag("motion-write-parameter"),
                                    value != null && value != reading.value) { Text(tr(Res.string.motion_write_parameter)) }
                            }
                        } else Text(tr(Res.string.motion_custom_required), fontSize = 13.sp)
                    }
                }
                Button(controller::refresh, Modifier.heightIn(min = 48.dp).testTag("motion-refresh")) { Text(tr(Res.string.refresh)) }
            }
        }
    }
}

private fun inputAxisLabel(axis: InputAxis): String = tr(when (axis) {
    InputAxis.FORWARD -> Res.string.input_axis_forward; InputAxis.LATERAL -> Res.string.input_axis_lateral
    InputAxis.YAW -> Res.string.input_axis_yaw; InputAxis.PITCH -> Res.string.input_axis_pitch
})
private fun motionLevelLabel(level: Int): String = when (level) {
    1 -> tr(Res.string.motion_level_low); 2 -> tr(Res.string.motion_level_medium); 3 -> tr(Res.string.motion_level_high)
    4 -> tr(Res.string.motion_level_custom); else -> tr(Res.string.motion_level_unknown, level)
}
private fun motionParameterLabel(parameter: ChassisParameter): String = tr(when (parameter) {
    ChassisParameter.FORWARD_SPEED -> Res.string.motion_forward; ChassisParameter.BACKWARD_SPEED -> Res.string.motion_backward
    ChassisParameter.LATERAL_SPEED -> Res.string.motion_lateral; ChassisParameter.FORWARD_ACCELERATION -> Res.string.motion_acc_forward
    ChassisParameter.LATERAL_ACCELERATION -> Res.string.motion_acc_lateral; ChassisParameter.FORWARD_BRAKING -> Res.string.motion_brake_forward
    ChassisParameter.LATERAL_BRAKING -> Res.string.motion_brake_lateral
})
private fun motionNumber(value: Float): String = value.toString().removeSuffix(".0")
