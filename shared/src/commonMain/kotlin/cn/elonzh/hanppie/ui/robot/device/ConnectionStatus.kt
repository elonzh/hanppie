package cn.elonzh.hanppie.ui.robot.device

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import org.jetbrains.compose.resources.painterResource
import cn.elonzh.hanppie.ui.settings.ConnectionMode
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
internal fun ConnectionStatusChip(state: ConsoleState, contentColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary, sceneStyle: Boolean = false, connectionMode: ConnectionMode = ConnectionMode.UNKNOWN, onClick: () -> Unit) {
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
    Row(Modifier.widthIn(max = 200.dp).heightIn(min = 48.dp).testTag("connection-status")
        .clickable(onClick = onClick).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (sceneStyle && state.connected) {
            Image(painterResource(when (connectionMode) {
                ConnectionMode.ROUTER -> Res.drawable.official_connection_router
                ConnectionMode.DIRECT -> Res.drawable.official_connection_direct
                ConnectionMode.UNKNOWN -> Res.drawable.official_connection_generic
            }), tr(Res.string.connected), Modifier.size(22.dp), colorFilter = ColorFilter.tint(contentColor))
            state.battery?.let { battery ->
                BatteryIcon(battery, contentColor)
            }
        } else Box(Modifier.size(6.dp).background(indicator, RoundedCornerShape(50)))
        Text(label,
            Modifier.weight(1f, fill = false),
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = contentColor)
        if (!sceneStyle) WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, contentColor, Modifier.size(16.dp))
    }
}

@Composable
internal fun ConnectionDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, Modifier.weight(1f), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** Shared official battery artwork for the homepage and cockpit. */
@Composable
internal fun BatteryIcon(battery: Int?, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Image(painterResource(when {
        battery == null || battery <= 0 -> Res.drawable.official_battery_empty
        battery > 65 -> Res.drawable.official_battery_full
        battery > 30 -> Res.drawable.official_battery_mid
        else -> Res.drawable.official_battery_low
    }), null, modifier, colorFilter = ColorFilter.tint(color))
}
