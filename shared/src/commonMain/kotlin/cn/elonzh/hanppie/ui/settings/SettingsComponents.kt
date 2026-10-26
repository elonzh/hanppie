package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A named set of related settings. Rows share one surface rather than looking like actions. */
@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun SettingsNavigationRow(title: String, summary: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(summary, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                minLines = 2, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(18.dp))
    }
}

/** Persistent, actionable feedback stays beside its task, with an explicit visual and semantic role. */
@Composable
internal fun SettingsNotice(message: String, modifier: Modifier = Modifier, error: Boolean = false) {
        Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .background(if (error) MiuixTheme.colorScheme.errorContainer else MiuixTheme.colorScheme.secondaryContainer,
                RoundedCornerShape(cn.elonzh.hanppie.ui.design.HanppieDesignTokens.ControlRadius)).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top) {
            WorkbenchIcon(WorkbenchGlyph.INFO,
                if (error) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onSecondaryContainer,
                Modifier.size(20.dp))
            Text(message, Modifier.weight(1f), fontSize = 14.sp,
                color = if (error) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onSecondaryContainer)
        }
}
