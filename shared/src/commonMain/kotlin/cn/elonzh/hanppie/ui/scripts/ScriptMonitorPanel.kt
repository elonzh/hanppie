package cn.elonzh.hanppie.ui.scripts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.app.ConsoleState
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.remote.RemoteMediaController
import cn.elonzh.hanppie.ui.robot.remote.RobotVideo
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Monitoring panel: the live robot view and the telemetry that describes the same run.
 *
 * Both answer "what is the robot doing right now", so they share one panel instead of living in
 * separate tabs; the media session is opened only while this panel is composed.
 */
@Composable
internal fun ScriptMonitorPanel(
    model: ConsoleController,
    state: ConsoleState,
    showVideo: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // A 16:9 view is worth its height only when the console below it still has room to log.
        if (showVideo) ScriptVideoView(model, Modifier.fillMaxWidth())
        // The strip sits under the view: walking the eye from the picture to its telemetry reads better
        // than the reverse, and with no frame counter it keeps a stable length instead of wrapping.
        ScriptStatusStrip(state)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ScriptStatusStrip(state: ConsoleState) {
    FlowRow(
        Modifier.fillMaxWidth().testTag("script-status-strip"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusChip(WorkbenchGlyph.BATTERY, tr(Res.string.script_status_battery_value, state.battery?.let { "$it%" } ?: "--"))
        StatusChip(WorkbenchGlyph.SIGNAL, tr(Res.string.script_status_signal_value, state.signalQuality?.let { "$it%" } ?: "--"))
        state.gimbal?.let { gimbal ->
            StatusChip(
                WorkbenchGlyph.CROSSHAIR,
                tr(
                    Res.string.script_status_gimbal_value,
                    gimbal.yawDegrees.roundToTenths(),
                    gimbal.pitchDegrees.roundToTenths(),
                ),
            )
        }
    }
}

@Composable
private fun StatusChip(glyph: WorkbenchGlyph, label: String) {
    Row(
        Modifier.background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WorkbenchIcon(glyph, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(14.dp))
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
    }
}

/** Live robot view; the cockpit component owns the media session and stops it when this leaves. */
@Composable
internal fun ScriptVideoView(model: ConsoleController, modifier: Modifier) {
    val state by model.state.collectAsState()
    val controls = remember { RemoteMediaController() }
    Box(
        modifier.aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(HanppieDesignTokens.CardRadius))
            .background(Color.Black)
            .testTag("script-video"),
    ) {
        if (state.connected) {
            RobotVideo(model, controls, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tr(Res.string.disconnected), color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

private fun Double.roundToTenths(): String = ((this * 10).roundToInt() / 10.0).toString().let {
    if (it.endsWith(".0")) it.dropLast(2) else it
}
