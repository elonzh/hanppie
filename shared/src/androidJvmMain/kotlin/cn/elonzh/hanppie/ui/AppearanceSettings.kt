package cn.elonzh.hanppie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

internal fun ThemePreset.label(): String = tr(when (this) {
    ThemePreset.NATIVE -> Res.string.theme_native
    ThemePreset.CHAPPIE -> Res.string.theme_chappie
    ThemePreset.OCEAN -> Res.string.theme_ocean
    ThemePreset.FOREST -> Res.string.theme_forest
    ThemePreset.CUSTOM -> Res.string.theme_custom
})

internal fun NightMode.label(): String = tr(when (this) {
    NightMode.SYSTEM -> Res.string.system_default
    NightMode.LIGHT -> Res.string.appearance_light
    NightMode.DARK -> Res.string.appearance_dark
})

@Composable
internal fun AppearanceSetting() {
    val controller = LocalAppearance.current
    val settings = controller.settings
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr(Res.string.appearance), fontSize = 18.sp)
        SettingsDropdown(tr(Res.string.theme), "theme", settings.preset.name,
            ThemePreset.entries.map { it.name to it.label() }) { value ->
            controller.update(settings.copy(preset = ThemePreset.valueOf(value)))
        }
        SettingsDropdown(tr(Res.string.night_mode), "night-mode", settings.nightMode.name,
            NightMode.entries.map { it.name to it.label() }) { value ->
            controller.update(settings.copy(nightMode = NightMode.valueOf(value)))
        }
        if (settings.preset == ThemePreset.CUSTOM) CustomPaletteEditor(controller)
    }
}

@Composable
internal fun SettingsDropdown(label: String, tag: String, selected: String, values: List<Pair<String, String>>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { contentDescription = "$tag-selector" }) {
            Text(label, Modifier.weight(1f), fontSize = 15.sp)
            Text(values.first { it.first == selected }.second, fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.width(12.dp))
            Text("⌄")
        }
        WindowListPopup(expanded, onDismissRequest = { expanded = false }) {
            ListPopupColumn {
                values.forEachIndexed { index, (id, title) ->
                    Box(Modifier.semantics(mergeDescendants = true) { contentDescription = "$tag-$id" }) {
                        DropdownImpl(DropdownItem(title), values.size, selected == id, index,
                            onSelectedIndexChange = { expanded = false; change(id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomPaletteEditor(controller: AppearanceController) {
    val saved = controller.settings.custom
    var lightAccent by rememberSaveable(saved.lightAccent) { mutableStateOf(saved.lightAccent) }
    var lightBackground by rememberSaveable(saved.lightBackground) { mutableStateOf(saved.lightBackground) }
    var darkAccent by rememberSaveable(saved.darkAccent) { mutableStateOf(saved.darkAccent) }
    var darkBackground by rememberSaveable(saved.darkBackground) { mutableStateOf(saved.darkBackground) }
    val draft = CustomPalette(lightAccent, lightBackground, darkAccent, darkBackground)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).testTag("palette-editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ColorField(tr(Res.string.light_accent), "custom-light-accent", lightAccent) { lightAccent = it }
            ColorField(tr(Res.string.light_background), "custom-light-background", lightBackground) { lightBackground = it }
            ColorField(tr(Res.string.dark_accent), "custom-dark-accent", darkAccent) { darkAccent = it }
            ColorField(tr(Res.string.dark_background), "custom-dark-background", darkBackground) { darkBackground = it }
            if (!draft.isValid()) Text(tr(Res.string.color_hex_hint), fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({
                    lightAccent = saved.lightAccent; lightBackground = saved.lightBackground
                    darkAccent = saved.darkAccent; darkBackground = saved.darkBackground
                }, Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "revert-palette" }, enabled = draft != saved) {
                    Text(tr(Res.string.revert_palette), fontSize = 14.sp)
                }
                Button({ controller.update(controller.settings.copy(custom = draft)) },
                    Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "save-palette" },
                    enabled = draft.isValid() && draft != saved, colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(tr(Res.string.save_palette), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun ColorField(label: String, tag: String, value: String, change: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextField(value, { if (it.length <= 7) change(it.uppercase()) }, label = label, singleLine = true,
            modifier = Modifier.weight(1f).semantics { contentDescription = tag })
        val color = if (Regex("#[0-9A-Fa-f]{6}").matches(value)) rgbColor(value) else MiuixTheme.colorScheme.secondary
        Box(Modifier.size(32.dp).background(color, RoundedCornerShape(8.dp)))
    }
}
