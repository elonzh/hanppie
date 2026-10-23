package cn.elonzh.hanppie.ui.robot.remote.input

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    modifier: Modifier = Modifier, start: (AimInputMode) -> Unit) {
    if (!input.mouseAvailable && !input.gyroAvailable) return
    Column(modifier.widthIn(max = 330.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.mode == AimInputMode.MOUSE) {
            Text(tr(Res.string.aim_mouse_active), Modifier.background(HanppieDesignTokens.RemoteHudSurface)
                .padding(horizontal = 12.dp, vertical = 10.dp), fontSize = 14.sp,
                color = HanppieDesignTokens.RemoteHudContent)
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (input.mouseAvailable) Button({ start(AimInputMode.MOUSE) },
                Modifier.heightIn(min = 48.dp).testTag("aim-mouse-toggle"),
                enabled = enabled, colors = ButtonDefaults.buttonColors(color = HanppieDesignTokens.RemoteHudSurface,
                    contentColor = HanppieDesignTokens.RemoteHudContent)) {
                Text(tr(Res.string.aim_mouse_start), fontSize = 14.sp)
            }
            if (input.gyroAvailable) Button({ start(if (state.mode == AimInputMode.GYROSCOPE) AimInputMode.OFF else AimInputMode.GYROSCOPE) },
                Modifier.heightIn(min = 48.dp).testTag("aim-gyro-toggle").semantics { selected = state.mode == AimInputMode.GYROSCOPE },
                enabled = enabled, colors = if (state.mode == AimInputMode.GYROSCOPE) ButtonDefaults.buttonColorsPrimary()
                else ButtonDefaults.buttonColors(color = HanppieDesignTokens.RemoteHudSurface, contentColor = HanppieDesignTokens.RemoteHudContent)) {
                Text(tr(if (state.mode == AimInputMode.GYROSCOPE) Res.string.aim_gyro_stop else Res.string.aim_gyro_start), fontSize = 14.sp)
            }
        }
        val label = when {
            state.problem != null -> when (state.problem) {
                AimInputProblem.POINTER_UNAVAILABLE -> Res.string.aim_pointer_failed
                AimInputProblem.SENSOR_UNAVAILABLE -> Res.string.aim_sensor_failed
                AimInputProblem.SENSOR_STALE -> Res.string.aim_sensor_stale
                AimInputProblem.ORIENTATION_CHANGED -> Res.string.aim_orientation_changed
            }
            state.mode != AimInputMode.OFF && !state.ready -> Res.string.aim_preparing
            state.mode == AimInputMode.MOUSE -> Res.string.aim_mouse_hint
            state.mode == AimInputMode.GYROSCOPE -> Res.string.aim_gyro_hint
            else -> null
        }
        label?.let { Text(tr(it), Modifier.background(HanppieDesignTokens.RemoteHudSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp).testTag("aim-input-status"), fontSize = 12.sp,
            color = HanppieDesignTokens.RemoteHudContent) }
    }
}
