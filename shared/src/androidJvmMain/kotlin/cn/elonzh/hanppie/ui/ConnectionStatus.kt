package cn.elonzh.hanppie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ConnectionStatusChip(state: ConsoleState, onClick: () -> Unit) {
    Row(Modifier.widthIn(max = 124.dp).heightIn(min = 48.dp).testTag("connection-status")
        .clickable(onClick = onClick).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(if (state.connected) Color(0xff32aa78) else Color(0xffaab1bb), RoundedCornerShape(50)))
        Text(if (state.connected) tr(Res.string.connected) else state.status, Modifier.weight(1f, fill = false),
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(16.dp))
    }
}

@Composable
internal fun ConnectionDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, Modifier.weight(1f), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
