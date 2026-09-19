package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.selected
import cn.elonzh.hanppie.ui.app.PlatformBackHandler
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SettingsPage(model: ConsoleController, modifier: Modifier = Modifier, onSpeechSettings: (() -> Unit)? = null) {
    val control by model.controlSettings.collectAsState()
    val chat by model.chat.state.collectAsState()
    val settingsBusy by model.settingsBusy.collectAsState()
    val settingsMessage by model.settingsMessage.collectAsState()
    val appearance = LocalAppearance.current
    var capturing by remember { mutableStateOf<ControlAction?>(null) }
    var selectedCategory by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
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
    BoxWithConstraints(modifier.fillMaxWidth().testTag("settings-page")
        .focusRequester(shortcutFocus).onPreviewKeyEvent { event ->
            val action = capturing ?: return@onPreviewKeyEvent false
            if (event.key == Key.Escape) { capturing = null; return@onPreviewKeyEvent true }
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
        }.focusable()) {
        // A 240 dp category list plus a 480 dp form and 16 dp gutter fit side by side.
        val categoryWidth = 240.dp * LocalDensity.current.fontScale
        val wide = maxWidth >= categoryWidth + 480.dp * LocalDensity.current.fontScale + 16.dp
        val activeCategory = selectedCategory ?: if (wide) SettingsCategory.GENERAL else null
        val backToCategories = { capturing = null; selectedCategory = null }
        PlatformBackHandler(enabled = !wide && selectedCategory != null && !confirmDefaults && capturing == null,
            onBack = backToCategories)
        PlatformBackHandler(enabled = capturing != null, onBack = { capturing = null })
        Row(Modifier.fillMaxSize().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (wide || activeCategory == null) {
                Column((if (wide) Modifier.width(categoryWidth) else Modifier.fillMaxWidth()).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsCategory.entries.forEach { category ->
                            val summary = when (category) {
                                SettingsCategory.GENERAL -> "${tr(Res.string.language)} · ${appearance.settings.nightMode.label()}"
                                SettingsCategory.MODEL -> tr(Res.string.settings_model_summary)
                                SettingsCategory.CONTROL -> tr(Res.string.control_settings_summary)
                                SettingsCategory.LIGHTS -> tr(Res.string.settings_lights_summary)
                                SettingsCategory.SHORTCUTS -> tr(Res.string.keyboard_shortcuts_summary)
                            }
                            SettingsCategoryRow(category, summary, wide && activeCategory == category) {
                                capturing = null
                                selectedCategory = category
                            }
                        }
                    }
                    if (activeCategory == null) settingsMessage?.let { Text(it.resolve(), fontSize = 13.sp) }
                    Button({ confirmDefaults = true }, enabled = !settingsBusy && !chat.running,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics { contentDescription = "restore-default-settings" }) {
                        Text(tr(Res.string.restore_defaults))
                    }
                }
            }
            if (activeCategory != null) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!wide) WorkbenchIconButton(tr(Res.string.back), WorkbenchGlyph.BACK,
                            backToCategories, tag = "settings-back")
                        Text(tr(activeCategory.title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                    key(activeCategory) {
                        Column(Modifier.weight(1f).fillMaxWidth().testTag("settings-detail-${activeCategory.id}")
                            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (activeCategory) {
                                SettingsCategory.GENERAL -> GeneralSettingsContent(model, onSpeechSettings)
                                SettingsCategory.MODEL -> ModelSettingsContent(model)
                                SettingsCategory.CONTROL -> ControlSettingsContent(model)
                                SettingsCategory.LIGHTS -> LightsSettingsContent(model)
                                SettingsCategory.SHORTCUTS -> ShortcutsSettingsContent(model, capturing) { capturing = it; shortcutFocus.requestFocus() }
                            }
                        }
                    }
                    if (activeCategory != SettingsCategory.GENERAL) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            settingsMessage?.let { Text(it.resolve(), fontSize = 13.sp) }
                            Button(model::saveSettings, colors = ButtonDefaults.buttonColorsPrimary(),
                                enabled = !settingsBusy && !chat.running,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-save")) {
                                Text(tr(Res.string.save_settings))
                            }
                        }
                    } else settingsMessage?.let { Text(it.resolve(), fontSize = 13.sp) }
                }
            }
        }
    }
}

private enum class SettingsCategory(val id: String, val title: org.jetbrains.compose.resources.StringResource,
                                    val glyph: WorkbenchGlyph) {
    GENERAL("general", Res.string.general_settings, WorkbenchGlyph.SETTINGS),
    MODEL("model", Res.string.model_service, WorkbenchGlyph.CHAT),
    CONTROL("control", Res.string.control, WorkbenchGlyph.CROSSHAIR),
    LIGHTS("lights", Res.string.remote_led_colors, WorkbenchGlyph.RECORD),
    SHORTCUTS("shortcuts", Res.string.keyboard_shortcuts, WorkbenchGlyph.CODE),
}

@Composable
private fun SettingsCategoryRow(category: SettingsCategory, summary: String, selected: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("settings-category-${category.id}")
        .clip(RoundedCornerShape(HanppieDesignTokens.CardRadius))
        .semantics { this.selected = selected }
        .clickable(onClick = onClick),
        colors = CardDefaults.defaultColors(color = if (selected) MiuixTheme.colorScheme.secondaryContainer
            else MiuixTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkbenchIcon(category.glyph, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr(category.title), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(summary, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(18.dp))
        }
    }
}
