package cn.elonzh.hanppie.ui.robot.device

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
import cn.elonzh.hanppie.ui.app.ConsoleState
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ConnectionStatusChip(state: ConsoleState, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val label = when {
        state.connected -> state.battery?.let { "$it%" } ?: tr(Res.string.connected)
        state.connecting -> tr(Res.string.connecting)
        else -> tr(Res.string.disconnected)
    }
    val indicator = when {
        state.connected -> Color(0xff32aa78)
        state.connecting -> colors.primary
        else -> Color(0xffaab1bb)
    }
    Row(Modifier.widthIn(max = 144.dp).heightIn(min = 48.dp).testTag("connection-status")
        .clickable(onClick = onClick).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(indicator, RoundedCornerShape(50)))
        Text(label,
            Modifier.weight(1f, fill = false),
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariantSummary)
        WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, colors.onSurfaceVariantSummary, Modifier.size(16.dp))
    }
}

@Composable
internal fun ConnectionDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, Modifier.weight(1f), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
