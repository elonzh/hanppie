package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.adjustableValue
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.display.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DisplaySettingsContent(model: ConsoleController, onCalibrate: () -> Unit) {
    val display by model.displaySettings.collectAsState()
    val settingsBusy by model.settingsBusy.collectAsState()
    val connection by model.state.collectAsState()
    val ui by model.robotDisplay.state.collectAsState()
    DisposableEffect(model) { onDispose { model.robotDisplay.cancelRead() } }
    fun change(value: DisplaySettings) { model.settingsMessage.value = null; model.displaySettings.value = value }
    SettingsDropdown(tr(Res.string.display_framing), "display-framing", display.fillVideo.toString(),
        listOf("false" to tr(Res.string.display_fit), "true" to tr(Res.string.display_fill)), !settingsBusy) {
        change(display.copy(fillVideo = it.toBoolean()))
    }
    SettingsDropdown(tr(Res.string.display_reticle), "display-reticle", display.reticle.name,
        ReticleStyle.entries.map { it.name to tr(if (it == ReticleStyle.CROSS) Res.string.display_cross else Res.string.display_round) }, !settingsBusy) {
        change(display.copy(reticle = ReticleStyle.valueOf(it)))
    }
    DisplayPreference(tr(Res.string.display_angle), "display-angle", display.showGimbalAngle, !settingsBusy) { change(display.copy(showGimbalAngle = it)) }
    DisplayPreference(tr(Res.string.display_arm_position), "display-arm-position", display.showArmPosition, !settingsBusy) { change(display.copy(showArmPosition = it)) }
    SettingsDropdown(tr(Res.string.display_quality), "display-quality", display.sceneQuality.name,
        SceneQuality.entries.map { it.name to tr(when (it) {
            SceneQuality.LOW -> Res.string.display_quality_low
            SceneQuality.MEDIUM -> Res.string.display_quality_medium
            SceneQuality.HIGH -> Res.string.display_quality_high
        }) }, !settingsBusy) { change(display.copy(sceneQuality = SceneQuality.valueOf(it))) }
    Text(tr(Res.string.display_quality_help), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Text(tr(Res.string.display_device), Modifier.testTag("display-device-title"), fontSize = 15.sp)
    ui.error?.let { Text(it.resolve(), Modifier.testTag("display-device-error"), color = MiuixTheme.colorScheme.error, fontSize = 14.sp) }
    if (!connection.connected) Text(tr(Res.string.display_connect), fontSize = 14.sp)
    else {
        Button(onCalibrate, Modifier.heightIn(min = 48.dp).testTag("display-calibrate"), !ui.busy) {
            Text(tr(Res.string.display_calibrate))
        }
        Text(tr(Res.string.display_headlights), fontSize = 15.sp)
        if (ui.target == DisplayTarget.HEADLIGHTS) {
            Text(tr(if (ui.writing) Res.string.display_writing else Res.string.display_reading), Modifier.testTag("display-device-busy"), fontSize = 14.sp)
            if (!ui.writing) Button(model.robotDisplay::cancelRead, Modifier.heightIn(min = 48.dp).testTag("display-cancel-read")) { Text(tr(Res.string.cancel)) }
        } else {
            ui.headlights?.let { reading ->
                Text(tr(Res.string.display_lights_current, reading.left, reading.right), Modifier.testTag("display-lights-current"), fontSize = 14.sp)
                if (ui.confirmed == DisplayTarget.HEADLIGHTS) Text(tr(Res.string.display_confirmed), Modifier.testTag("display-confirmed"), fontSize = 14.sp)
                if (reading.left in 0..100 && reading.right in 0..100) {
                    var draft by remember(reading, ui.connection) { mutableIntStateOf(reading.left) }
                    val changeBrightness: (Float) -> Unit = { draft = it.roundToInt() }
                    Text("$draft%", fontSize = 14.sp)
                    Slider(draft.toFloat(), changeBrightness, valueRange = 0f..100f, enabled = !ui.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("display-lights-slider")
                            .adjustableValue(tr(Res.string.display_headlights), draft.toFloat(), 0f..100f, "$draft%",
                                !ui.busy, steps = 99, onChange = changeBrightness))
                    Text(tr(Res.string.display_lights_scope), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Button({ model.robotDisplay.setHeadlights(draft) }, Modifier.heightIn(min = 48.dp).testTag("display-lights-apply"),
                        !ui.busy && (draft != reading.left || draft != reading.right)) { Text(tr(Res.string.display_lights_apply)) }
                } else Text(tr(Res.string.display_lights_range), fontSize = 14.sp)
            }
            Button(model.robotDisplay::refreshHeadlights, Modifier.heightIn(min = 48.dp).testTag("display-lights-read"), !ui.busy) {
                Text(tr(Res.string.display_lights_read))
            }
        }
    }
}

@Composable
private fun DisplayPreference(label: String, tag: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = change), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        Checkbox(if (checked) ToggleableState.On else ToggleableState.Off, null, enabled = enabled)
    }
}
