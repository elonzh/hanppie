package cn.elonzh.hanppie.ui.robot.remote.input

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.gamepad.GamepadWarning
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.gamepad.label
import top.yukonga.miuix.kmp.basic.*

@Composable
internal fun GamepadDriveControls(state: GamepadDriveState, enabled: Boolean,
    modifier: Modifier = Modifier, toggle: () -> Unit) {
    Column(modifier.widthIn(max = 330.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(toggle, Modifier.heightIn(min = 48.dp).testTag("gamepad-drive-toggle"), enabled = state.active || enabled && state.fresh,
            colors = if (state.active) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(
                color = HanppieDesignTokens.RemoteHudSurface, contentColor = HanppieDesignTokens.RemoteHudContent)) {
            Text(tr(if (state.active) Res.string.gamepad_drive_stop else Res.string.gamepad_drive_start), fontSize = 14.sp)
        }
        val message = when (state.problem) {
            GamepadDriveProblem.RECENTER -> Res.string.gamepad_drive_recenter
            GamepadDriveProblem.STALE -> if (state.fresh) Res.string.gamepad_drive_recovered else Res.string.gamepad_drive_stale
            GamepadDriveProblem.OWNER_CHANGED, GamepadDriveProblem.SETTINGS_CHANGED -> Res.string.gamepad_drive_retired
            GamepadDriveProblem.NO_DATA -> if (state.fresh) Res.string.gamepad_drive_ready else Res.string.gamepad_drive_wait
            null -> if (state.active) Res.string.gamepad_drive_active else if (!state.fresh) Res.string.gamepad_drive_wait else Res.string.gamepad_drive_ready
        }
        val warning = GamepadWarning.entries.firstOrNull { it in state.warnings }
        Text(tr(message) + (warning?.let { "\n" + it.label() } ?: ""), Modifier.background(HanppieDesignTokens.RemoteHudSurface).padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("gamepad-drive-status"), fontSize = 12.sp, color = HanppieDesignTokens.RemoteHudContent)
    }
}
