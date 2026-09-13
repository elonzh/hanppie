package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.agent.provider.ModelTestStage
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.color.api.toHsv
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SettingsPage(model: ConsoleController, modifier: Modifier = Modifier, onSpeechSettings: (() -> Unit)? = null) {
    val config by model.modelSettings.collectAsState()
    val modelCatalog by model.modelCatalogState.collectAsState()
    val modelTest by model.modelTestState.collectAsState()
    val control by model.controlSettings.collectAsState()
    val chat by model.chat.state.collectAsState()
    val settingsBusy by model.settingsBusy.collectAsState()
    val settingsMessage by model.settingsMessage.collectAsState()
    val appearance = LocalAppearance.current
    var capturing by remember { mutableStateOf<ControlAction?>(null) }
    var expandedSection by rememberSaveable {
        mutableStateOf(if (model.modelSettings.value.apiKey.isBlank()) "model" else "general")
    }
    var confirmDefaults by remember { mutableStateOf(false) }
    val shortcutFocus = remember { FocusRequester() }
    WorkbenchDialog(show = confirmDefaults, onDismissRequest = { confirmDefaults = false },
        title = tr(Res.string.restore_default_settings_question),
        summary = tr(Res.string.restore_default_settings_summary)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ confirmDefaults = false }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(tr(Res.string.cancel))
            }
            Button({
                confirmDefaults = false
                model.voiceInput.cancel()
                Localization.select("system")
                appearance.update(AppearanceSettings())
                model.restoreDefaultSettings()
            }, Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(tr(Res.string.restore_defaults))
            }
        }
    }
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
        }.focusable(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fun toggleSection(id: String) {
                expandedSection = if (expandedSection == id) "" else id
                if (id != "shortcuts" || expandedSection != id) capturing = null
            }
            SettingsSection(
                title = tr(Res.string.general_settings),
                summary = "${tr(Res.string.language)} · ${appearance.settings.nightMode.label()}",
                tag = "general",
                expanded = expandedSection == "general",
                onToggle = { toggleSection("general") },
            ) {
                SettingsDropdown(tr(Res.string.language), "language", Localization.choice,
                    listOf("system" to tr(Res.string.system_default), "zh" to "简体中文", "en" to "English")) { id ->
                    model.voiceInput.cancel()
                    Localization.select(id)
                }
                AppearanceSetting()
                if (onSpeechSettings != null) Button(onSpeechSettings) { Text(tr(Res.string.speech_services)) }
            }

            SettingsSection(
                title = tr(Res.string.model_service),
                summary = "${config.provider.displayName} · ${config.model}",
                tag = "model",
                expanded = expandedSection == "model",
                onToggle = { toggleSection("model") },
            ) {
            SettingsDropdown(tr(Res.string.model_provider), "model-provider", config.provider.name,
                ModelCatalog.providers.map { it.name to it.displayName }) { id ->
                val provider = ModelProviderPreset.valueOf(id)
                model.settingsMessage.value = null
                model.modelSettings.value = if (provider == ModelProviderPreset.CUSTOM) {
                    config.copy(provider = provider)
                } else ModelCatalog.defaults(provider).copy(apiKey = config.apiKey)
            }
            TextField(config.endpoint, { model.settingsMessage.value = null; model.modelSettings.value = config.copy(endpoint = it) }, label = tr(Res.string.api_endpoint), modifier = Modifier.fillMaxWidth(), enabled = !chat.running && !settingsBusy, singleLine = true)
            val discoveredModels = modelCatalog.models.takeIf {
                modelCatalog.provider == config.provider && modelCatalog.endpoint == config.endpoint
            }.orEmpty()
            val availableModels = (
                ModelCatalog.models[config.provider].orEmpty().map { it.id } +
                    discoveredModels + config.model
            ).filter(String::isNotBlank).distinct()
            if (config.provider != ModelProviderPreset.CUSTOM) {
                SettingsDropdown(tr(Res.string.model), "model-preset", config.model,
                    availableModels.map { it to it }, onOpen = model::loadModelCatalog) { id ->
                    model.settingsMessage.value = null
                    model.modelSettings.value = config.copy(model = id)
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
            }
            TextField(config.model, { model.settingsMessage.value = null; model.modelSettings.value = config.copy(model = it) }, label = tr(Res.string.custom_model_id), modifier = Modifier.fillMaxWidth(), enabled = !chat.running && !settingsBusy, singleLine = true)
            TextField(config.apiKey, { model.settingsMessage.value = null; model.modelSettings.value = config.copy(apiKey = it) }, label = tr(Res.string.api_key), modifier = Modifier.fillMaxWidth(), enabled = !chat.running && !settingsBusy, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            val llModel = config.llModel
            Text(tr(Res.string.model_capabilities_value,
                llModel.contextLength?.toString() ?: tr(Res.string.unknown),
                llModel.maxOutputTokens?.toString() ?: tr(Res.string.unknown),
                llModel.capabilities.orEmpty().joinToString { it.id }),
                fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(model::testModelSettings, enabled = !modelTest.running && !chat.running && !settingsBusy && config.apiKey.isNotBlank()) {
                    Text(if (modelTest.running) tr(Res.string.testing_model_configuration) else tr(Res.string.test_model_configuration))
                }
                modelTest.message?.let { Text(it, fontSize = 12.sp,
                    color = if (modelTest.success == false) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            }
            Text(tr(Res.string.model_test_cost_notice), fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            modelTest.stages.forEach { result ->
                Text("${if (result.passed) "✓" else "!"} ${modelTestStageLabel(result.stage)} · ${result.message}",
                    fontSize = 12.sp,
                    color = if (result.passed) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.error)
            }
            }

            SettingsSection(
                title = tr(Res.string.control),
                summary = tr(Res.string.control_settings_summary),
                tag = "control",
                expanded = expandedSection == "control",
                onToggle = { toggleSection("control") },
            ) {
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
                Text(tr(Res.string.remote_led_colors), fontSize = 15.sp)
                Text(tr(Res.string.remote_led_colors_summary), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteLedColorPicker(tr(Res.string.remote_led_standby), "remote-led-standby", control.remoteLeds.standby,
                        Modifier.widthIn(min = 250.dp, max = 340.dp).weight(1f)) { color ->
                        model.controlSettings.value = control.copy(remoteLeds = control.remoteLeds.copy(standby = color))
                    }
                    RemoteLedColorPicker(tr(Res.string.remote_led_active), "remote-led-active", control.remoteLeds.active,
                        Modifier.widthIn(min = 250.dp, max = 340.dp).weight(1f)) { color ->
                        model.controlSettings.value = control.copy(remoteLeds = control.remoteLeds.copy(active = color))
                    }
                    RemoteLedColorPicker(tr(Res.string.remote_led_recording), "remote-led-recording", control.remoteLeds.recording,
                        Modifier.widthIn(min = 250.dp, max = 340.dp).weight(1f)) { color ->
                        model.controlSettings.value = control.copy(remoteLeds = control.remoteLeds.copy(recording = color))
                    }
                    RemoteLedColorPicker(tr(Res.string.remote_led_talking), "remote-led-talking", control.remoteLeds.talking,
                        Modifier.widthIn(min = 250.dp, max = 340.dp).weight(1f)) { color ->
                        model.controlSettings.value = control.copy(remoteLeds = control.remoteLeds.copy(talking = color))
                    }
                }
            }

            SettingsSection(
                title = tr(Res.string.keyboard_shortcuts),
                summary = tr(Res.string.keyboard_shortcuts_summary),
                tag = "shortcuts",
                expanded = expandedSection == "shortcuts",
                onToggle = { toggleSection("shortcuts") },
            ) {
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
                            capturing = if (capturing == action) null else action
                            shortcutFocus.requestFocus()
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ confirmDefaults = true }, enabled = !settingsBusy && !chat.running,
                    modifier = Modifier.semantics { contentDescription = "restore-default-settings" }) {
                    Text(tr(Res.string.restore_defaults))
                }
                Button(model::saveSettings, colors = ButtonDefaults.buttonColorsPrimary(), enabled = !settingsBusy && !chat.running) {
                    Text(tr(Res.string.save_settings))
                }
                settingsMessage?.let { Text(it.resolve(), fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    summary: String,
    tag: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("settings-section-$tag"),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
        Column {
            Row(Modifier.fillMaxWidth().testTag("settings-section-$tag-toggle").clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 17.sp)
                    Text(summary, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1)
                }
                WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    Modifier.size(18.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f })
            }
            if (expanded) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
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

private fun modelTestStageLabel(stage: ModelTestStage): String = tr(when (stage) {
    ModelTestStage.LOCAL -> Res.string.model_test_local
    ModelTestStage.CATALOG -> Res.string.model_test_catalog
    ModelTestStage.STREAMING -> Res.string.model_test_streaming
    ModelTestStage.TOOL_CALL -> Res.string.model_test_tool_call
})
