package cn.elonzh.hanppie.ui.robot.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RobotHealthAlertButton(model: ConsoleController, onOpen: () -> Unit) {
    val health by model.robotHealth.state.collectAsState()
    val count = health.issues.count { it.severity >= 2 }
    if (count == 0) return
    val colors = MiuixTheme.colorScheme
    val label = tr(Res.string.health_open_alerts, count)
    Row(Modifier.width(72.dp).heightIn(min = 48.dp).testTag("health-alert-open").clip(RoundedCornerShape(12.dp))
        .background(colors.errorContainer).clickable(role = Role.Button, onClick = onOpen)
        .semantics { contentDescription = label }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        WorkbenchIcon(WorkbenchGlyph.ACTIVITY, colors.onErrorContainer, Modifier.size(20.dp))
        Text(count.toString(), color = colors.onErrorContainer, maxLines = 1)
    }
}
