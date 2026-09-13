package cn.elonzh.hanppie.ui.robot.device

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.app.ConsoleState
import cn.elonzh.hanppie.ui.design.HanppieBrandAssets
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.design.navigationIcons
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.robot.product.RobotModel
import org.jetbrains.compose.resources.painterResource
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DevicePage(model: ConsoleController, state: ConsoleState, compact: Boolean, modifier: Modifier,
    onManualConnect: () -> Unit, onRemote: () -> Unit, onNavigate: (Int) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    LazyColumn(modifier.fillMaxWidth().testTag("device-page"), verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp),
                colors = CardDefaults.defaultColors(color = colors.surfaceContainer)) {
                if (compact) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    DeviceIdentity(state, Modifier.fillMaxWidth(), compact = true)
                    DeviceActions(model, state, onManualConnect, onRemote)
                } else Row(Modifier.padding(32.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    DeviceIdentity(state, Modifier.weight(1f), compact = false)
                    Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        DeviceActions(model, state, onManualConnect, onRemote)
                    }
                }
            }
        }
        items(state.devices) { device ->
            Button({ model.connect(device.ip, device.appId) }, Modifier.fillMaxWidth().heightIn(min = 56.dp),
                enabled = !state.connected && !state.busy) {
                WorkbenchIcon(WorkbenchGlyph.CONNECT)
                Text("RoboMaster  ·  ${device.ip}", Modifier.weight(1f).padding(horizontal = 12.dp))
                Text(tr(Res.string.connect))
            }
        }
        item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth / fontScale < 300.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeviceMetric(WorkbenchGlyph.BATTERY, tr(Res.string.battery), state.battery?.let { "$it%" } ?: "—", Modifier.fillMaxWidth(), inline = true)
                        DeviceMetric(WorkbenchGlyph.SIGNAL, tr(Res.string.signal), state.signalQuality?.toString() ?: "—", Modifier.fillMaxWidth(), inline = true)
                        DeviceMetric(WorkbenchGlyph.FILE_TEXT, tr(Res.string.script), state.scriptStatus, Modifier.fillMaxWidth(), inline = true)
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DeviceMetric(WorkbenchGlyph.BATTERY, tr(Res.string.battery), state.battery?.let { "$it%" } ?: "—", Modifier.weight(1f))
                    DeviceMetric(WorkbenchGlyph.SIGNAL, tr(Res.string.signal), state.signalQuality?.toString() ?: "—", Modifier.weight(1f))
                    DeviceMetric(WorkbenchGlyph.FILE_TEXT, tr(Res.string.script), state.scriptStatus, Modifier.weight(1f))
                }
            }
        }
        if (!state.connected) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                WorkbenchIcon(WorkbenchGlyph.CONNECT, colors.onSurfaceVariantSummary, Modifier.size(20.dp))
                Text(tr(Res.string.connect_your_phone_or_computer_to_the_same_wi), fontSize = 13.sp,
                    color = colors.onSurfaceVariantSummary)
            }
        }
        item {
            Text(tr(Res.string.workspace), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp),
                colors = CardDefaults.defaultColors(color = colors.surfaceContainer)) {
                Column {
                    listOf(1 to Res.string.script_library, 2 to Res.string.diagnostics_workspace, 3 to Res.string.assistant_workspace).forEach { (index, title) ->
                        BasicComponent(title = tr(title), onClick = { onNavigate(index) },
                            startAction = {
                                Icon(navigationIcons[index], null, Modifier.padding(end = 16.dp).size(24.dp),
                                    tint = colors.onSurfaceVariantSummary)
                            }, endActions = { WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, colors.onSurfaceVariantSummary) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceIdentity(state: ConsoleState, modifier: Modifier, compact: Boolean) {
    val colors = MiuixTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("DJI", fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = .6.sp, maxLines = 1,
                color = colors.onSurfaceVariantSummary)
            val productName = when (state.robotProduct.model) {
                RobotModel.UNKNOWN -> "RoboMaster"
                RobotModel.ROBOMASTER_S1 -> "RoboMaster S1"
                RobotModel.ROBOMASTER_EP -> "RoboMaster EP"
            }
            Text(productName, fontSize = if (compact) 30.sp else 48.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, color = colors.onSurface)
            Text(state.connectedAddress?.takeIf { state.connected } ?: tr(Res.string.not_connected),
                fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
        }
        // Brand identity in the connection overview; never presented as a picture of the physical robot.
        Box(Modifier.size(if (compact) 100.dp else 128.dp).background(colors.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center) {
            Image(painterResource(HanppieBrandAssets.avatar), null, Modifier.fillMaxSize().padding(10.dp))
        }
    }
}

@Composable
private fun DeviceActions(model: ConsoleController, state: ConsoleState, onManual: () -> Unit, onRemote: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.connected) CockpitEntry(onRemote)
        if (state.connected || state.reconnecting || state.statusMessage.resource == Res.string.connection_lost) {
            Button(model::disconnect, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("disconnect-robot"), enabled = !state.busy) {
                Text(tr(Res.string.disconnect_close_session))
            }
        } else {
            Button(model::discover, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("discover-robot"), enabled = !state.busy,
                colors = ButtonDefaults.buttonColorsPrimary()) {
                WorkbenchIcon(WorkbenchGlyph.SEARCH, MiuixTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp)); Text(tr(if (state.busy) Res.string.searching else Res.string.find_robots))
            }
            Button(onManual, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("manual-connect")) {
                Text(tr(Res.string.manual_connection))
            }
        }

    }
}

@Composable
private fun CockpitEntry(onRemote: () -> Unit) {
        Button(onRemote, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("enter-remote"),
            colors = ButtonDefaults.buttonColorsPrimary()) {
            WorkbenchIcon(WorkbenchGlyph.CROSSHAIR, MiuixTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp)); Text(tr(Res.string.fullscreen_cockpit))
        }
}

@Composable
private fun DeviceMetric(symbol: WorkbenchGlyph, label: String, value: String, modifier: Modifier, inline: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    Card(modifier, insideMargin = PaddingValues(0.dp), colors = CardDefaults.defaultColors(color = colors.surfaceContainer)) {
        if (inline) Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkbenchIcon(symbol, colors.onSurfaceVariantSummary, Modifier.size(20.dp))
            Text(label, Modifier.weight(1f), fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        } else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkbenchIcon(symbol, colors.onSurfaceVariantSummary, Modifier.size(20.dp))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(label, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
        }
    }
}
