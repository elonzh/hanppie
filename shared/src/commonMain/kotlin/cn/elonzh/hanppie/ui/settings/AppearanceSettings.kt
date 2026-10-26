package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

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
        SettingsDropdown(tr(Res.string.night_mode), "night-mode", settings.nightMode.name,
            NightMode.entries.map { it.name to it.label() }) { value ->
            controller.update(settings.copy(nightMode = NightMode.valueOf(value)))
        }
    }
}

@Composable
internal fun SettingsDropdown(
    label: String,
    tag: String,
    selected: String,
    values: List<Pair<String, String>>,
    enabled: Boolean = true,
    onOpen: () -> Unit = {},
    change: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = values.firstOrNull { it.first == selected }?.second ?: selected
    Box {
        BasicComponent(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("$tag-selector")
            .semantics(mergeDescendants = true) { if (!enabled) disabled() },
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp), enabled = enabled,
            onClick = { if (!expanded) onOpen(); expanded = true }, role = Role.Button,
            endActions = {
                Text(selectedLabel, Modifier.widthIn(max = 200.dp), fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    Modifier.padding(start = 12.dp).size(18.dp))
            }) {
            Text(label, fontSize = 15.sp)
        }
        WindowListPopup(expanded, onDismissRequest = { expanded = false }) {
            ListPopupColumn {
                values.forEachIndexed { index, (id, title) ->
                    Box(Modifier.heightIn(min = 48.dp).testTag("$tag-$id"),
                        propagateMinConstraints = true) {
                        DropdownImpl(DropdownItem(title), values.size, selected == id, index,
                            onSelectedIndexChange = { expanded = false; change(id) })
                    }
                }
            }
        }
    }
}
