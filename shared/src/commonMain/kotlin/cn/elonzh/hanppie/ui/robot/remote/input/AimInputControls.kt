package cn.elonzh.hanppie.ui.robot.remote.input

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun AimInputControls(input: GimbalAimInput, state: AimInputState, enabled: Boolean,
    modifier: Modifier = Modifier, onToggle: () -> Unit) {
    if (!input.gyroAvailable) return
    Column(modifier.widthIn(max = 330.dp * LocalDensity.current.fontScale), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onToggle,
            Modifier.heightIn(min = 48.dp).testTag("aim-gyro-toggle").semantics { selected = state.active },
            enabled = enabled, colors = if (state.active) ButtonDefaults.buttonColorsPrimary()
            else ButtonDefaults.buttonColors(color = HanppieDesignTokens.RemoteHudSurface, contentColor = HanppieDesignTokens.RemoteHudContent)) {
            Text(tr(if (state.active) Res.string.aim_gyro_stop else Res.string.aim_gyro_start), fontSize = 14.sp)
        }
        val label = when {
            state.problem != null -> when (state.problem) {
                AimInputProblem.SENSOR_UNAVAILABLE -> Res.string.aim_sensor_failed
                AimInputProblem.SENSOR_STALE -> Res.string.aim_sensor_stale
                AimInputProblem.ORIENTATION_CHANGED -> Res.string.aim_orientation_changed
            }
            state.active && !state.ready -> Res.string.aim_preparing
            state.active -> Res.string.aim_gyro_hint
            else -> null
        }
        label?.let { Text(tr(it), Modifier.heightIn(max = 80.dp).verticalScroll(rememberScrollState())
            .background(HanppieDesignTokens.RemoteHudSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp).testTag("aim-input-status"), fontSize = 12.sp,
            color = HanppieDesignTokens.RemoteHudContent) }
    }
}
