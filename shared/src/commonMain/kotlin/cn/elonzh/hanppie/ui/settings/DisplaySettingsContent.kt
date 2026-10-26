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
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DisplaySettingsContent(model: ConsoleController, onCalibrate: () -> Unit) {
    val display by model.displaySettings.collectAsState()
    val settingsBusy by model.settingsBusy.collectAsState()
    val connection by model.state.collectAsState()
    fun change(value: DisplaySettings) { model.settingsMessage.value = null; model.displaySettings.value = value }
    SettingsGroup(tr(Res.string.settings_group_video)) {
        SettingsDropdown(tr(Res.string.display_framing), "display-framing", display.fillVideo.toString(),
            listOf("false" to tr(Res.string.display_fit), "true" to tr(Res.string.display_fill)), !settingsBusy) {
            change(display.copy(fillVideo = it.toBoolean()))
        }
    }
    SettingsGroup(tr(Res.string.settings_group_hud)) {
        SettingsDropdown(tr(Res.string.display_reticle), "display-reticle", display.reticle.name,
            ReticleStyle.entries.map { it.name to tr(if (it == ReticleStyle.CROSS) Res.string.display_cross else Res.string.display_round) }, !settingsBusy) {
            change(display.copy(reticle = ReticleStyle.valueOf(it)))
        }
        DisplayPreference(tr(Res.string.display_angle), "display-angle", display.showGimbalAngle, !settingsBusy) { change(display.copy(showGimbalAngle = it)) }
        DisplayPreference(tr(Res.string.display_arm_position), "display-arm-position", display.showArmPosition, !settingsBusy) { change(display.copy(showArmPosition = it)) }
    }
    SettingsGroup(tr(Res.string.settings_group_scene)) {
        SettingsDropdown(tr(Res.string.display_quality), "display-quality", display.sceneQuality.name,
            SceneQuality.entries.map { it.name to tr(when (it) {
                SceneQuality.LOW -> Res.string.display_quality_low
                SceneQuality.MEDIUM -> Res.string.display_quality_medium
                SceneQuality.HIGH -> Res.string.display_quality_high
            }) }, !settingsBusy) { change(display.copy(sceneQuality = SceneQuality.valueOf(it))) }

        Text(tr(Res.string.display_quality_help), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    SettingsGroup(tr(Res.string.display_device)) {
        if (!connection.connected) Text(tr(Res.string.display_connect), Modifier.padding(16.dp), fontSize = 14.sp)
        else SettingsNavigationRow(tr(Res.string.display_calibrate), tr(Res.string.settings_calibration_summary), "display-calibrate", onClick = onCalibrate)
    }
}

@Composable
private fun DisplayPreference(label: String, tag: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = change).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f).padding(start = 16.dp), fontSize = 15.sp)
        Checkbox(if (checked) ToggleableState.On else ToggleableState.Off, null, enabled = enabled)
    }
}
