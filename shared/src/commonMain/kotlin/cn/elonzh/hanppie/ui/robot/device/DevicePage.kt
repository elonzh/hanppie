package cn.elonzh.hanppie.ui.robot.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.app.ConsoleState
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.scene.RobotScene
import cn.elonzh.hanppie.ui.robot.scene.RobotSceneState
import kotlinx.coroutines.delay
import kotlin.time.Clock
import top.yukonga.miuix.kmp.basic.Text

/** Home-only visual vocabulary; other workbench pages retain their component theme. */
@Composable
internal fun DevicePage(model: ConsoleController, state: ConsoleState, compact: Boolean, modifier: Modifier,
    onRemote: () -> Unit, onConnectionDetails: () -> Unit = {},
    showScene: Boolean = true, preparing: Boolean = false,
    onNavigate: (Int) -> Unit = {}) {
    val preferences by model.connectionPreferences.collectAsState()
    val connectionMode = preferences.robots.firstOrNull { it.ip == state.connectedAddress }?.mode ?: cn.elonzh.hanppie.ui.settings.ConnectionMode.UNKNOWN
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(state.connected) {
        while (state.connected) { now = Clock.System.now().toEpochMilliseconds(); delay(250) }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("device-page")) {
        val dense = compact || maxHeight < 480.dp
        val inset = if (dense) 16.dp else 28.dp
        if (showScene) RobotScene(RobotSceneState.from(state, now), Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            Color.Black.copy(alpha = .42f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .52f)))))
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = inset, vertical = if (dense) 10.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("HANPPIE", fontSize = 10.sp, letterSpacing = 3.sp, color = Color.White.copy(alpha = .65f))
                Text(state.productName, fontSize = if (dense) 18.sp else 22.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionStatusChip(state, Color.White.copy(alpha = .78f), sceneStyle = true, connectionMode = connectionMode, onClick = onConnectionDetails)
                HomeLink(tr(Res.string.settings), WorkbenchGlyph.SETTINGS, iconOnly = true) { onNavigate(4) }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = if (dense) 16.dp else 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (dense) 8.dp else 20.dp)) {
                HomeLink(tr(Res.string.script), WorkbenchGlyph.CODE) { onNavigate(1) }
                HomeAction(
                    label = when { state.connected -> tr(Res.string.fullscreen_cockpit)
                        state.connecting -> tr(Res.string.connecting); else -> tr(Res.string.automatic_connection) },
                    symbol = if (state.connected) WorkbenchGlyph.CROSSHAIR else WorkbenchGlyph.CONNECT,
                    tag = if (state.connected) "enter-remote" else "auto-connect",
                    enabled = !preparing && !state.connecting && !state.busy,
                    onClick = if (state.connected) onRemote else model::discover)
                HomeLink(tr(Res.string.chat), WorkbenchGlyph.CHAT) { onNavigate(3) }
            }

        }

    }
}

@Composable
private fun HomeLink(label: String, symbol: WorkbenchGlyph, modifier: Modifier = Modifier, iconOnly: Boolean = false, onClick: () -> Unit) {
    Row(modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WorkbenchIcon(symbol, Color.White.copy(alpha = .72f), Modifier.size(17.dp))
        if (!iconOnly) Text(label, color = Color.White.copy(alpha = .82f), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun HomeAction(label: String, symbol: WorkbenchGlyph, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.widthIn(min = 184.dp).heightIn(min = 48.dp).testTag(tag).clip(RoundedCornerShape(22.dp))
        .background(Color(0xffe97635).copy(alpha = .95f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
        WorkbenchIcon(symbol, Color(0xff201a16), Modifier.size(18.dp))
        Text(label, color = Color(0xff201a16), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
