package cn.elonzh.hanppie.ui.robot.arm

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.extensions.arm.ArmObservation
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.i18n.tr
import org.jetbrains.compose.resources.StringResource
import top.yukonga.miuix.kmp.basic.*

@Composable
internal fun RobotArmControls(controller: RobotArmController, enabled: Boolean, videoFresh: Boolean,
    modifier: Modifier = Modifier, showPosition: Boolean = false) {
    val ui by controller.state.collectAsState()
    val task = ui.task
    val observing = task.observation == ArmObservation.ACTIVE && !ui.busy
    val waitingForReports = observing && (task.armOnline && !task.positionFresh || task.clawOnline && !task.clawFresh)
    Card(modifier.widthIn(max = 660.dp).testTag("remote-arm-controls"), insideMargin = PaddingValues(12.dp),
        colors = CardDefaults.defaultColors(color = HanppieDesignTokens.RemoteHudSurface)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (waitingForReports) tr(Res.string.arm_reports_wait) else ui.error?.resolve() ?: tr(when {
                !enabled -> Res.string.arm_control_paused
                !videoFresh -> Res.string.arm_video_wait
                !observing -> Res.string.arm_observation_required
                task.position?.blocked == true || task.clawWarnings?.let { it.openBlocked || it.closeBlocked || it.moveUpBlocked || it.moveDownBlocked } == true -> Res.string.arm_blocked
                task.position?.let { it.limitX || it.limitY } == true || task.clawWarnings?.let { it.moveUpLimit || it.moveDownLimit } == true -> Res.string.arm_limit
                task.armOnline && task.binding?.installed != true -> Res.string.arm_binding_invalid
                task.controlActive -> Res.string.arm_moving
                else -> Res.string.arm_hold_help
            }), Modifier.testTag("remote-arm-status"), fontSize = 13.sp, color = HanppieDesignTokens.RemoteHudContent)
            if (task.observation == ArmObservation.OFF || task.observation == ArmObservation.FAILED)
                Button({ controller.refresh(ui.connection) }, Modifier.heightIn(min = 48.dp).testTag("arm-retry-status"),
                    enabled = enabled && !ui.busy) { Text(tr(Res.string.arm_retry_status), fontSize = 14.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val canArm = enabled && videoFresh && observing && task.armOnline && task.binding?.installed == true && task.positionFresh
                val canClaw = enabled && videoFresh && observing && task.clawOnline && task.clawFresh
                ArmControlPair(controller, ui, ArmDirection.FORWARD, ArmDirection.BACKWARD, canArm, Modifier.weight(1f))
                ArmControlPair(controller, ui, ArmDirection.UP, ArmDirection.DOWN, canArm, Modifier.weight(1f))
                ArmControlPair(controller, ui, ArmDirection.OPEN, ArmDirection.CLOSE, canClaw, Modifier.weight(1f))
            }
            if (showPosition && task.armOnline) Text(tr(Res.string.arm_position_raw,
                task.position?.xRaw?.toString() ?: "—", task.position?.yRaw?.toString() ?: "—", task.positionAgeSeconds?.toString() ?: "—"),
                Modifier.testTag("remote-arm-position"), fontSize = 12.sp, color = HanppieDesignTokens.RemoteHudContent)
            if (task.clawOnline) Text(tr(if (task.clawFresh) when (task.clawStatusRaw) {
                1 -> Res.string.arm_claw_opened
                2 -> Res.string.arm_claw_closed
                else -> Res.string.arm_claw_ready
            } else Res.string.arm_claw_wait), fontSize = 12.sp, color = HanppieDesignTokens.RemoteHudContent)
        }
    }
}

@Composable
private fun ArmControlPair(controller: RobotArmController, ui: ArmUiState, first: ArmDirection, second: ArmDirection,
    enabled: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ArmHoldButton(controller, ui.connection, first, enabled, first in ui.held, Modifier.fillMaxWidth())
        ArmHoldButton(controller, ui.connection, second, enabled, second in ui.held, Modifier.fillMaxWidth())
    }
}

/** Miuix button retains click semantics; pointer and keyboard hold lifetimes own separate opaque tokens. */
@Composable
private fun ArmHoldButton(controller: RobotArmController, connection: Long, direction: ArmDirection,
    enabled: Boolean, pressed: Boolean, modifier: Modifier) {
    var keyboardHeld by remember { mutableStateOf(false) }
    var keyboardToken by remember { mutableStateOf<Long?>(null) }
    var pointerToken by remember { mutableStateOf<Long?>(null) }
    fun releaseKeys(clearHeld: Boolean = false) { controller.release(keyboardToken); keyboardToken = null; if (clearHeld) keyboardHeld = false }
    DisposableEffect(controller, connection) { onDispose { controller.release(keyboardToken); controller.release(pointerToken) } }
    LaunchedEffect(enabled) { if (!enabled) { releaseKeys(); controller.release(pointerToken); pointerToken = null } }
    Button({ controller.pulse(direction, connection) }, modifier.heightIn(min = 48.dp)
        .testTag("arm-hold-" + direction.name.lowercase())
        .onFocusChanged { if (!it.isFocused) releaseKeys() }
        .onPreviewKeyEvent {
            if (!enabled || it.key !in setOf(Key.Enter, Key.Spacebar)) false
            else when (it.type) {
                KeyEventType.KeyDown -> { if (!keyboardHeld) { keyboardHeld = true; keyboardToken = controller.press(direction, connection) }; true }
                KeyEventType.KeyUp -> { releaseKeys(clearHeld = true); true }
                else -> false
            }
        }.pointerInput(enabled, connection) {
            if (enabled) awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial); down.consume()
                val token = controller.press(direction, connection); pointerToken = token
                try { waitForUpOrCancellation(PointerEventPass.Initial)?.consume() }
                finally { controller.release(token); if (pointerToken == token) pointerToken = null }
            }
        }, enabled = enabled, colors = if (pressed) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
        Text(tr(armDirectionName(direction)), fontSize = 14.sp)
    }
}

internal fun armDirectionName(direction: ArmDirection): StringResource = when (direction) {
    ArmDirection.FORWARD -> Res.string.arm_forward
    ArmDirection.BACKWARD -> Res.string.arm_backward
    ArmDirection.UP -> Res.string.arm_up
    ArmDirection.DOWN -> Res.string.arm_down
    ArmDirection.OPEN -> Res.string.arm_open
    ArmDirection.CLOSE -> Res.string.arm_close
}
