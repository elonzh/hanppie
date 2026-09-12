package cn.elonzh.hanppie.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
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
