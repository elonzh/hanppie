package cn.elonzh.hanppie.ui.settings

import ai.koog.prompt.llm.LLModel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.action_backward
import cn.elonzh.hanppie.resources.action_fire
import cn.elonzh.hanppie.resources.action_forward
import cn.elonzh.hanppie.resources.action_gear_1
import cn.elonzh.hanppie.resources.action_gear_2
import cn.elonzh.hanppie.resources.action_gear_3
import cn.elonzh.hanppie.resources.action_gear_4
import cn.elonzh.hanppie.resources.action_gear_5
import cn.elonzh.hanppie.resources.action_gimbal_down
import cn.elonzh.hanppie.resources.action_gimbal_left
import cn.elonzh.hanppie.resources.action_gimbal_right
import cn.elonzh.hanppie.resources.action_gimbal_up
import cn.elonzh.hanppie.resources.action_photo
import cn.elonzh.hanppie.resources.action_push_to_talk
import cn.elonzh.hanppie.resources.action_recording
import cn.elonzh.hanppie.resources.action_robot_microphone
import cn.elonzh.hanppie.resources.action_rotate_clockwise
import cn.elonzh.hanppie.resources.action_rotate_counterclockwise
import cn.elonzh.hanppie.resources.action_stop
import cn.elonzh.hanppie.resources.action_strafe_left
import cn.elonzh.hanppie.resources.action_strafe_right
import cn.elonzh.hanppie.resources.action_switch_ammo
import cn.elonzh.hanppie.resources.api_endpoint
import cn.elonzh.hanppie.resources.api_key
import cn.elonzh.hanppie.resources.cancel
import cn.elonzh.hanppie.resources.cancel_current_run
import cn.elonzh.hanppie.resources.choose_model
import cn.elonzh.hanppie.resources.color_apply
import cn.elonzh.hanppie.resources.color_brightness
import cn.elonzh.hanppie.resources.color_hue
import cn.elonzh.hanppie.resources.color_saturation
import cn.elonzh.hanppie.resources.control_shortcuts
import cn.elonzh.hanppie.resources.conversation_shortcuts
import cn.elonzh.hanppie.resources.conversations
import cn.elonzh.hanppie.resources.custom_model_id
import cn.elonzh.hanppie.resources.degrees_per_second
import cn.elonzh.hanppie.resources.degrees_per_second_value
import cn.elonzh.hanppie.resources.filter_models
import cn.elonzh.hanppie.resources.focus_message_input
import cn.elonzh.hanppie.resources.gimbal_sensitivity
import cn.elonzh.hanppie.resources.insert_line_break
import cn.elonzh.hanppie.resources.joystick_dead_zone
import cn.elonzh.hanppie.resources.language
import cn.elonzh.hanppie.resources.loading_model_catalog
import cn.elonzh.hanppie.resources.meters_per_second
import cn.elonzh.hanppie.resources.model_capability_image
import cn.elonzh.hanppie.resources.model_capability_structured
import cn.elonzh.hanppie.resources.model_capability_thinking
import cn.elonzh.hanppie.resources.model_capability_tool_choice
import cn.elonzh.hanppie.resources.model_capability_tools
import cn.elonzh.hanppie.resources.model_context_value
import cn.elonzh.hanppie.resources.model_output_value
import cn.elonzh.hanppie.resources.model_provider
import cn.elonzh.hanppie.resources.motion_parameters
import cn.elonzh.hanppie.resources.new_chat
import cn.elonzh.hanppie.resources.no_matching_models
import cn.elonzh.hanppie.resources.press_a_key
import cn.elonzh.hanppie.resources.remote_led_active
import cn.elonzh.hanppie.resources.remote_led_colors_summary
import cn.elonzh.hanppie.resources.remote_led_recording
import cn.elonzh.hanppie.resources.remote_led_standby
import cn.elonzh.hanppie.resources.remote_led_talking
import cn.elonzh.hanppie.resources.send
import cn.elonzh.hanppie.resources.settings
import cn.elonzh.hanppie.resources.speaker_volume
import cn.elonzh.hanppie.resources.speaker_volume_summary
import cn.elonzh.hanppie.resources.speech_services
import cn.elonzh.hanppie.resources.system_default
import cn.elonzh.hanppie.resources.test_model_configuration
import cn.elonzh.hanppie.resources.testing_model_configuration
import cn.elonzh.hanppie.resources.thinking_depth
import cn.elonzh.hanppie.resources.video_resolution
import cn.elonzh.hanppie.robot.media.VideoResolution
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HsvHueSlider
import top.yukonga.miuix.kmp.basic.HsvSaturationSlider
import top.yukonga.miuix.kmp.basic.HsvValueSlider
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.color.api.toHsv
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GeneralSettingsContent(model: ConsoleController, onSpeechSettings: (() -> Unit)?) {
    SettingsDropdown(tr(Res.string.language), "language", Localization.choice,
        listOf("system" to tr(Res.string.system_default), "zh" to "简体中文", "en" to "English")) { id ->
        model.voiceInput.cancel()
        Localization.select(id)
    }
    AppearanceSetting()
    DataDirectorySetting()
    if (onSpeechSettings != null) Button(onSpeechSettings) { Text(tr(Res.string.speech_services)) }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ModelSettingsContent(model: ConsoleController) {
    val config by model.modelSettings.collectAsState()
    val modelCatalog by model.modelCatalogState.collectAsState()
    val modelTest by model.modelTestState.collectAsState()
    val chat by model.chat.state.collectAsState()
    val settingsBusy by model.settingsBusy.collectAsState()
    SettingsDropdown(tr(Res.string.model_provider), "model-provider", config.provider.name,
        ModelCatalog.providers.map { it.name to it.displayName }) { id ->
        val provider = ModelProviderPreset.valueOf(id)
        model.settingsMessage.value = null
        model.modelSettings.value = if (provider == ModelProviderPreset.CUSTOM) {
            config.copy(provider = provider)
        } else ModelCatalog.defaults(provider).copy(apiKey = config.apiKey)
    }
    TextField(config.endpoint, { model.settingsMessage.value = null; model.modelSettings.value = config.copy(endpoint = it) }, label = tr(Res.string.api_endpoint), modifier = Modifier.fillMaxWidth(), enabled = !chat.running && !settingsBusy, singleLine = true)
    TextField(config.apiKey, { model.settingsMessage.value = null; model.modelSettings.value = config.copy(apiKey = it) }, label = tr(Res.string.api_key), modifier = Modifier.fillMaxWidth(), enabled = !chat.running && !settingsBusy, singleLine = true,
        visualTransformation = PasswordVisualTransformation())
    val discoveredModels = modelCatalog.models.takeIf {
        modelCatalog.provider == config.provider && modelCatalog.endpoint == config.endpoint
    }.orEmpty()
    val availableModels = (
        ModelCatalog.models[config.provider].orEmpty().map { it.id } +
            discoveredModels + config.model
    ).filter(String::isNotBlank).distinct()
    var modelPickerOpen by remember { mutableStateOf(false) }
    var modelFilter by remember { mutableStateOf("") }
    // One row for the model: the id the provider will receive, plus a picker that loads and filters
    // the catalog instead of repeating the id in a second control.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(config.model, { model.settingsMessage.value = null; model.modelSettings.value = config.copy(model = it) }, label = tr(Res.string.custom_model_id), modifier = Modifier.weight(1f), enabled = !chat.running && !settingsBusy, singleLine = true)
        WorkbenchIconButton(
            label = tr(Res.string.choose_model),
            glyph = WorkbenchGlyph.CHEVRON_RIGHT,
            onClick = { model.settingsMessage.value = null; model.loadModelCatalog(); modelPickerOpen = true },
            enabled = !chat.running && !settingsBusy,
            tag = "model-picker",
        )
    }
    if (modelCatalog.provider == config.provider && modelCatalog.endpoint == config.endpoint) {
        val catalogMessage = if (modelCatalog.loading) {
            tr(Res.string.loading_model_catalog)
        } else {
            modelCatalog.message
        }
        catalogMessage?.let {
            Text(it, fontSize = 12.sp,
                color = if (modelCatalog.failed) MiuixTheme.colorScheme.error
                else MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
    WorkbenchDialog(
        show = modelPickerOpen,
        onDismissRequest = { modelPickerOpen = false },
        title = tr(Res.string.choose_model),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(modelFilter, { modelFilter = it }, label = tr(Res.string.filter_models),
                modifier = Modifier.fillMaxWidth().testTag("model-filter"),
                singleLine = true)
            val matching = availableModels.filter {
                modelFilter.isBlank() || it.contains(modelFilter.trim(), ignoreCase = true)
            }
            if (matching.isEmpty()) {
                Text(tr(Res.string.no_matching_models), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    matching.forEach { id ->
                        ModelOption(id, selected = id == config.model) {
                            model.settingsMessage.value = null
                            model.modelSettings.value = config.copy(model = id)
                            modelPickerOpen = false
                        }
                    }
                }
            }
        }
    }
    SettingsDropdown(tr(Res.string.thinking_depth), "thinking-depth", config.thinkingDepth.name,
        ThinkingDepth.entries.map { depth -> depth.name to tr(depth.label) }) { id ->
        model.settingsMessage.value = null
        model.modelSettings.value = config.copy(thinkingDepth = ThinkingDepth.valueOf(id))
    }
    // Model facts read as a short summary. Provider capability identifiers and per-stage test detail
    // are diagnostics: they belong in the log, not in front of the user.
    ModelSummary(config.llModel)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(model::testModelSettings, enabled = !modelTest.running && !chat.running && !settingsBusy && config.apiKey.isNotBlank()) {
            Text(if (modelTest.running) tr(Res.string.testing_model_configuration) else tr(Res.string.test_model_configuration))
        }
        modelTest.message?.let { message ->
            Text(
                (if (modelTest.success == false) "✗ " else "✓ ") + message,
                fontSize = 13.sp,
                color = if (modelTest.success == false) MiuixTheme.colorScheme.error
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MediaSettingsContent(model: ConsoleController) {
    val media by model.mediaSettings.collectAsState()
    val chat by model.chat.state.collectAsState()
    val settingsBusy by model.settingsBusy.collectAsState()
    val enabled = !chat.running && !settingsBusy

    SettingsDropdown(
        label = tr(Res.string.video_resolution),
        tag = "video-resolution",
        selected = media.videoResolution.name,
        values = VideoResolution.entries.map { it.name to it.label },
    ) { id ->
        model.settingsMessage.value = null
        model.mediaSettings.value = media.copy(videoResolution = VideoResolution.valueOf(id))
    }

    Text(tr(Res.string.speaker_volume), fontSize = 15.sp)
    Card(
        modifier = Modifier.fillMaxWidth().testTag("speaker-volume-card"),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tr(Res.string.speaker_volume_summary), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("${media.speakerVolume}%", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = media.speakerVolume.toFloat(),
                onValueChange = {
                    model.settingsMessage.value = null
                    model.mediaSettings.value = media.copy(speakerVolume = it.roundToInt().coerceIn(0, 100))
                },
                valueRange = 0f..100f,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("speaker-volume-slider"),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ControlSettingsContent(model: ConsoleController) {
    val control by model.controlSettings.collectAsState()
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
        CompactNumberField(control.joystickDeadZone, tr(Res.string.joystick_dead_zone), Modifier.fillMaxWidth()) { value ->
            runCatching { control.copy(joystickDeadZone = value) }.onSuccess { model.controlSettings.value = it }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun LightsSettingsContent(model: ConsoleController) {
    val control by model.controlSettings.collectAsState()
    Text(tr(Res.string.remote_led_colors_summary), fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteLedColorPicker(tr(Res.string.remote_led_standby), "remote-led-standby", control.remoteLeds.standby,
            Modifier.width(340.dp)) { color ->
            model.controlSettings.value = control.copy(remoteLeds = control.remoteLeds.copy(standby = color))
        }
        RemoteLedColorPicker(tr(Res.string.remote_led_active), "remote-led-active", control.remoteLeds.active,
            Modifier.width(340.dp)) { color ->
            model.controlSettings.value = control.copy(remoteLeds = control.remoteLeds.copy(active = color))
        }
        RemoteLedColorPicker(tr(Res.string.remote_led_recording), "remote-led-recording", control.remoteLeds.recording,
            Modifier.width(340.dp)) { color ->
            model.controlSettings.value = control.copy(remoteLeds = control.remoteLeds.copy(recording = color))
        }
        RemoteLedColorPicker(tr(Res.string.remote_led_talking), "remote-led-talking", control.remoteLeds.talking,
            Modifier.width(340.dp)) { color ->
            model.controlSettings.value = control.copy(remoteLeds = control.remoteLeds.copy(talking = color))
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ShortcutsSettingsContent(model: ConsoleController, capturing: ControlAction?, onCapture: (ControlAction?) -> Unit) {
    val control by model.controlSettings.collectAsState()
    Text(tr(Res.string.conversation_shortcuts), fontSize = 15.sp)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShortcutInfoRow(tr(Res.string.send), "Enter", Modifier.widthIn(min = 250.dp, max = 340.dp).weight(1f))
        ShortcutInfoRow(tr(Res.string.insert_line_break), "Shift+Enter", Modifier.widthIn(min = 250.dp, max = 340.dp).weight(1f))
        ShortcutInfoRow(tr(Res.string.new_chat), "⌘/Ctrl+N", Modifier.widthIn(min = 250.dp, max = 340.dp).weight(1f))
        ShortcutInfoRow(tr(Res.string.conversations), "⌘/Ctrl+B", Modifier.widthIn(min = 250.dp, max = 340.dp).weight(1f))
        ShortcutInfoRow(tr(Res.string.focus_message_input), "⌘/Ctrl+L", Modifier.widthIn(min = 250.dp, max = 340.dp).weight(1f))
        ShortcutInfoRow(tr(Res.string.settings), "⌘/Ctrl+,", Modifier.widthIn(min = 250.dp, max = 340.dp).weight(1f))
        ShortcutInfoRow(tr(Res.string.cancel_current_run), "Esc", Modifier.widthIn(min = 250.dp, max = 340.dp).weight(1f))
    }
    Text(tr(Res.string.control_shortcuts), fontSize = 15.sp)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ControlAction.entries.forEach { action ->
            ShortcutRow(actionLabel(action), control.shortcuts[action].label, capturing == action,
                Modifier.widthIn(min = 250.dp, max = 340.dp).weight(1f)) {
                onCapture(if (capturing == action) null else action)
                }
        }
    }
}

@Composable
internal fun RemoteLedColorPicker(label: String, tag: String, value: RobotLedColor, modifier: Modifier,
                                  onValid: (RobotLedColor) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Button({ open = true }, modifier.heightIn(min = 56.dp).semantics { contentDescription = tag }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(28.dp).background(rgbColor(value.hex), RoundedCornerShape(8.dp)))
            Text(label, Modifier.weight(1f), fontSize = 14.sp)
            Text("›", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
    if (open) {
        val initial = remember { rgbColor(value.hex).toHsv() }
        var hue by remember { mutableFloatStateOf(initial.h) }
        var saturation by remember { mutableFloatStateOf(initial.s / 100f) }
        var brightness by remember { mutableFloatStateOf(initial.v / 100f) }
        val selected = Color.hsv(hue, saturation, brightness)
        WorkbenchDialog(show = true, onDismissRequest = { open = false }, title = label) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).background(selected, RoundedCornerShape(12.dp)))
                Column(Modifier.testTag("led-hue")) {
                    Text(tr(Res.string.color_hue), fontSize = 13.sp)
                    HsvHueSlider(hue, { hue = it * 360f })
                }
                Column(Modifier.testTag("led-saturation")) {
                    Text(tr(Res.string.color_saturation), fontSize = 13.sp)
                    HsvSaturationSlider(hue, saturation, { saturation = it })
                }
                Column(Modifier.testTag("led-brightness")) {
                    Text(tr(Res.string.color_brightness), fontSize = 13.sp)
                    HsvValueSlider(hue, saturation, brightness, { brightness = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ open = false }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(tr(Res.string.cancel))
                    }
                    Button({
                        onValid(RobotLedColor((selected.red * 255).roundToInt(),
                            (selected.green * 255).roundToInt(), (selected.blue * 255).roundToInt()))
                        open = false
                    }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("led-color-apply"),
                        colors = ButtonDefaults.buttonColorsPrimary()) {
                        Text(tr(Res.string.color_apply))
                    }
                }
            }
        }
    }
}

private fun rgbColor(hex: String): Color = Color(0xff000000L or hex.removePrefix("#").toLong(16))

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

@Composable
private fun ShortcutInfoRow(label: String, binding: String, modifier: Modifier) {
    Row(modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        Text(binding, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
    ControlAction.Fire -> Res.string.action_fire
    ControlAction.SwitchAmmo -> Res.string.action_switch_ammo
    ControlAction.Photo -> Res.string.action_photo
    ControlAction.Recording -> Res.string.action_recording
    ControlAction.PushToTalk -> Res.string.action_push_to_talk
    ControlAction.RobotMicrophone -> Res.string.action_robot_microphone
    ControlAction.Stop -> Res.string.action_stop
})

/** One selectable model id, marked when it is the configured one. */
@Composable
private fun ModelOption(id: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag("model-option-$id")
            .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent,
                RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(id, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (selected) Text("✓", fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
    }
}

/** The model as a user reads it: name, plain-language limits, and only meaningful capabilities. */
@Composable
private fun ModelSummary(model: LLModel) {
    val facts = listOfNotNull(
        model.contextLength?.let { length -> tr(Res.string.model_context_value, formatTokenCount(length)) },
        model.maxOutputTokens?.let { tokens -> tr(Res.string.model_output_value, formatTokenCount(tokens)) },
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(model.id, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (facts.isNotEmpty()) {
            Text(facts.joinToString(" · "), fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        val labels = model.capabilities.orEmpty().mapNotNull { capability -> capabilityLabel(capability.id) }.distinct()
        if (labels.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEach { label ->
                    Box(Modifier.background(MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

/** Only capabilities a user can act on get a chip; transport details such as `temperature` do not. */
private fun capabilityLabel(id: String): String? = when {
    id.contains("thinking") -> tr(Res.string.model_capability_thinking)
    id.contains("vision") -> tr(Res.string.model_capability_image)
    id.contains("tool.choice") || id.contains("tool_choice") -> tr(Res.string.model_capability_tool_choice)
    id.contains("tools") -> tr(Res.string.model_capability_tools)
    id.contains("schema") -> tr(Res.string.model_capability_structured)
    else -> null
}

/** 1_000_000 -> "1M": short enough for a one-line summary. */
private fun formatTokenCount(value: Long): String = when {
    value >= 1_000_000L && value % 1_000_000L == 0L -> "${value / 1_000_000L}M"
    value >= 1_000L && value % 1_000L == 0L -> "${value / 1_000L}K"
    else -> value.toString()
}
