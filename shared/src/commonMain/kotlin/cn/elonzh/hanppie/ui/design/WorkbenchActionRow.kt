package cn.elonzh.hanppie.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One labelled action inside a management dialog or context menu. */
@Composable
internal fun WorkbenchActionRow(
    label: String,
    glyph: WorkbenchGlyph,
    tag: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WorkbenchIcon(
                glyph,
                if (danger) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                Modifier.size(20.dp),
            )
            Text(
                label,
                fontSize = 15.sp,
                color = if (danger) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}
