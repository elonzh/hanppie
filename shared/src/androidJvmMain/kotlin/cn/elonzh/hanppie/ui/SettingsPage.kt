package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SettingsPage(model: ConsoleModel, modifier: Modifier = Modifier, onSpeechSettings: (() -> Unit)? = null) {
    val config by model.modelSettings.collectAsState()
    val autoRead by model.autoReadReplies.collectAsState()
    val control by model.controlSettings.collectAsState()
    val chat by model.chat.state.collectAsState()
    val settingsBusy by model.settingsBusy.collectAsState()
    val settingsMessage by model.settingsMessage.collectAsState()
    var capturing by remember { mutableStateOf<ControlAction?>(null) }
    var shortcutsExpanded by remember { mutableStateOf(false) }
    val shortcutFocus = remember { FocusRequester() }
    Column(modifier.fillMaxWidth().testTag("settings-page").verticalScroll(rememberScrollState())
        .focusRequester(shortcutFocus).onPreviewKeyEvent { event ->
            val action = capturing ?: return@onPreviewKeyEvent false
            val key = ControlKey.from(event.key) ?: return@onPreviewKeyEvent true
            if (key == ControlKey.Shift && event.type == KeyEventType.KeyDown) return@onPreviewKeyEvent true
            if (event.type != KeyEventType.KeyDown && !(key == ControlKey.Shift && event.type == KeyEventType.KeyUp))
                return@onPreviewKeyEvent true
            val next = KeyBinding(key, event.isShiftPressed && key != ControlKey.Shift)
            val previous = control.shortcuts[action]
            val conflict = ControlAction.entries.firstOrNull { it != action && control.shortcuts[it] == next }
            var shortcuts = control.shortcuts.bind(action, next)
            if (conflict != null) shortcuts = shortcuts.bind(conflict, previous)
            model.controlSettings.value = control.copy(shortcuts = shortcuts)
            model.settingsMessage.value = null
            capturing = null
            true
        }.focusable(),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsDropdown(tr(Res.string.language), "language", Localization.choice,
            listOf("system" to tr(Res.string.system_default), "zh" to "简体中文", "en" to "English")) { id ->
            model.voiceInput.cancel(); model.replySpeaker.stop()
            Localization.select(id); model.speech.languageChanged()
        }
        AppearanceSetting()
        Text(tr(Res.string.control), fontSize = 18.sp)
        SettingsDropdown(tr(Res.string.gimbal_sensitivity), "gimbal-sensitivity", control.gimbalSpeed.toString(),
            listOf(15, 30, 45, 60, 90, 120).map { it.toString() to tr(Res.string.degrees_per_second_value, it) }) { value ->
            model.settingsMessage.value = null
            model.controlSettings.value = control.copy(gimbalSpeed = value.toInt())
        }
        Text(tr(Res.string.motion_parameters), fontSize = 15.sp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            control.translationSpeeds.indices.forEach { index ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactNumberField(control.creepMultiplier, tr(Res.string.creep_multiplier), Modifier.weight(1f)) { value ->
                    runCatching { control.copy(creepMultiplier = value) }.onSuccess { model.controlSettings.value = it }
                }
                CompactNumberField(control.joystickDeadZone, tr(Res.string.joystick_dead_zone), Modifier.weight(1f)) { value ->
                    runCatching { control.copy(joystickDeadZone = value) }.onSuccess { model.controlSettings.value = it }
                }
            }
        }
        Card(Modifier.fillMaxWidth().clickable {
            shortcutsExpanded = !shortcutsExpanded
            if (!shortcutsExpanded) capturing = null
        }, colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(tr(Res.string.control_shortcuts), Modifier.weight(1f), fontSize = 15.sp)
                Text(if (shortcutsExpanded) "⌃" else "⌄", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (shortcutsExpanded) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlAction.entries.forEach { action ->
                    ShortcutRow(actionLabel(action), control.shortcuts[action].label, capturing == action,
                        Modifier.widthIn(min = 250.dp, max = 340.dp).weight(1f)) {
                        capturing = if (capturing == action) null else action
                        shortcutFocus.requestFocus()
                    }
                }
            }
        }
        Text(tr(Res.string.model_service), fontSize = 18.sp)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(config.endpoint, { model.settingsMessage.value = null; model.modelSettings.value = config.copy(endpoint = it) }, label = tr(Res.string.api_endpoint), modifier = Modifier.fillMaxWidth(), enabled = !chat.running && !settingsBusy, singleLine = true)
            TextField(config.model, { model.settingsMessage.value = null; model.modelSettings.value = config.copy(model = it) }, label = tr(Res.string.model), modifier = Modifier.fillMaxWidth(), enabled = !chat.running && !settingsBusy, singleLine = true)
            TextField(config.apiKey, { model.settingsMessage.value = null; model.modelSettings.value = config.copy(apiKey = it) }, label = tr(Res.string.api_key), modifier = Modifier.fillMaxWidth(), enabled = !chat.running && !settingsBusy, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(modifier = Modifier.semantics { contentDescription = tr(Res.string.read_assistant_replies_automatically) },
                    checked = autoRead, enabled = !settingsBusy, onCheckedChange = {
                        if (!settingsBusy) model.autoReadReplies.value = !autoRead
                        model.settingsMessage.value = null
                        if (autoRead) model.replySpeaker.stop()
                    })
                Text(tr(Res.string.read_replies_aloud), Modifier.padding(start = 12.dp), fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                if (onSpeechSettings != null) Button(onSpeechSettings) { Text(tr(Res.string.speech_services)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(model::saveSettings, colors = ButtonDefaults.buttonColorsPrimary(), enabled = !settingsBusy && !chat.running) { Text(tr(Res.string.save_settings)) }
                settingsMessage?.let { Text(it.resolve(), fontSize = 13.sp) }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CompactNumberField(value: Double, label: String, modifier: Modifier, onValid: (Double) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.toString().removeSuffix(".0")) }
    TextField(draft, { text ->
        draft = text
        text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(onValid)
    }, label = label, modifier = modifier, singleLine = true)
}

@Composable
private fun ShortcutRow(label: String, binding: String, capturing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier, colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 13.sp)
            Button(onClick, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                colors = if (capturing) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                Text(if (capturing) tr(Res.string.press_a_key) else binding, fontSize = 12.sp)
            }
        }
    }
}

private fun actionLabel(action: ControlAction): String = tr(when (action) {
    ControlAction.Forward -> Res.string.action_forward
    ControlAction.Backward -> Res.string.action_backward
    ControlAction.StrafeLeft -> Res.string.action_strafe_left
    ControlAction.StrafeRight -> Res.string.action_strafe_right
    ControlAction.RotateCounterclockwise -> Res.string.action_rotate_counterclockwise
    ControlAction.RotateClockwise -> Res.string.action_rotate_clockwise
    ControlAction.GimbalUp -> Res.string.action_gimbal_up
    ControlAction.GimbalDown -> Res.string.action_gimbal_down
    ControlAction.GimbalLeft -> Res.string.action_gimbal_left
    ControlAction.GimbalRight -> Res.string.action_gimbal_right
    ControlAction.Gear1 -> Res.string.action_gear_1
    ControlAction.Gear2 -> Res.string.action_gear_2
    ControlAction.Gear3 -> Res.string.action_gear_3
    ControlAction.Gear4 -> Res.string.action_gear_4
    ControlAction.Gear5 -> Res.string.action_gear_5
    ControlAction.Creep -> Res.string.action_creep
    ControlAction.Fire -> Res.string.action_fire
    ControlAction.SwitchAmmo -> Res.string.action_switch_ammo
    ControlAction.Photo -> Res.string.action_photo
    ControlAction.Recording -> Res.string.action_recording
    ControlAction.PushToTalk -> Res.string.action_push_to_talk
    ControlAction.RobotMicrophone -> Res.string.action_robot_microphone
    ControlAction.Stop -> Res.string.action_stop
})
