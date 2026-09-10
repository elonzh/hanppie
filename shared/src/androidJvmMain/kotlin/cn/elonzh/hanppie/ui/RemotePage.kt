package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*

@Composable internal expect fun RemoteOrientation(onBack: (() -> Unit)?)

@Composable internal expect fun RobotVideo(model: ConsoleModel, controls: RemoteMediaController, modifier: Modifier)

@Composable
internal fun RemotePage(
    model: ConsoleModel,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onPushToTalkStart: (() -> Unit)? = null,
    onPushToTalkStop: (() -> Unit)? = null,
) {
    RemoteOrientation(onBack)
    var keyboardHints by remember { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    val connected by model.state.collectAsState()
    val enabled by model.remoteEnabled.collectAsState()
    val gelSelected by model.gelSelected.collectAsState()
    val gear by model.driveGear.collectAsState()
    val control by model.controlSettings.collectAsState()
    val talking by model.talking.collectAsState()
    val talkBusy by model.talkBusy.collectAsState()
    val mediaControls = remember { RemoteMediaController() }
    var left by remember { mutableStateOf(Offset.Zero) }
    var right by remember { mutableStateOf(Offset.Zero) }
    var keys by remember { mutableStateOf(setOf<Key>()) }
    val focus = remember { FocusRequester() }
    val currentLeft by rememberUpdatedState(left)
    val currentRight by rememberUpdatedState(right)
    val currentKeys by rememberUpdatedState(keys)
    val currentGear by rememberUpdatedState(gear)
    val currentControl by rememberUpdatedState(control)
    val creeping = Key.ShiftLeft in keys || Key.ShiftRight in keys
    DisposableEffect(model) { onDispose { (onPushToTalkStop ?: model::endPushToTalk)(); model.leaveRemote(); model.stopMedia() } }
    LaunchedEffect(enabled) {
        if (enabled) focus.requestFocus()
        else { keys = emptySet(); left = Offset.Zero; right = Offset.Zero }
    }
    LaunchedEffect(enabled) {
        while (enabled) {
            val shortcuts = currentControl.shortcuts
            val shiftHeld = Key.ShiftLeft in currentKeys || Key.ShiftRight in currentKeys
            fun axis(positive: ControlAction, negative: ControlAction) =
                (if (shortcuts.isHeld(positive, currentKeys, shiftHeld)) 1 else 0) -
                    (if (shortcuts.isHeld(negative, currentKeys, shiftHeld)) 1 else 0)
            val translation = DriveSpeed.translation(
                (-currentLeft.y + axis(ControlAction.Forward, ControlAction.Backward)).coerceIn(-1f,1f).toDouble(),
                (currentLeft.x + axis(ControlAction.StrafeRight, ControlAction.StrafeLeft)).coerceIn(-1f,1f).toDouble(),
                currentGear, shortcuts.isHeld(ControlAction.Creep, currentKeys, shiftHeld), currentControl)
            val rotation = axis(ControlAction.RotateClockwise, ControlAction.RotateCounterclockwise) *
                DriveSpeed.rotation(currentGear, shortcuts.isHeld(ControlAction.Creep, currentKeys, shiftHeld), currentControl)
            model.drive(translation.first, translation.second, rotation,
                (-currentRight.y + axis(ControlAction.GimbalUp, ControlAction.GimbalDown)).coerceIn(-1f,1f).toDouble() * currentControl.gimbalSpeed,
                (currentRight.x + axis(ControlAction.GimbalRight, ControlAction.GimbalLeft)).coerceIn(-1f,1f).toDouble() * currentControl.gimbalSpeed)
            delay(50)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("remote-surface").background(colors.background).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.type == PointerType.Touch && it.pressed }) keyboardHints = false
            }
        }
    }.focusRequester(focus).onFocusChanged {
        if (!it.hasFocus) { keys = emptySet(); left = Offset.Zero; right = Offset.Zero; model.haltRemote() }
    }.onPreviewKeyEvent {
        val supported = control.shortcuts.supports(it.key)
        if (it.type == KeyEventType.KeyDown) keyboardHints = true
        if (supported) {
            if (it.type == KeyEventType.KeyUp) {
                if (control.shortcuts[ControlAction.PushToTalk].key.matches(it.key))
                    (onPushToTalkStop ?: model::endPushToTalk)()
                keys = keys - it.key
            }
            else if (it.type == KeyEventType.KeyDown) {
                if (it.key !in keys) when (control.shortcuts.edgeAction(it.key,
                        it.isShiftPressed || Key.ShiftLeft in keys || Key.ShiftRight in keys)) {
                    ControlAction.Fire -> model.fireSelected()
                    ControlAction.SwitchAmmo -> model.switchAmmo()
                    ControlAction.Photo -> mediaControls.takePhoto()
                    ControlAction.Recording -> mediaControls.toggleRecording()
                    ControlAction.PushToTalk -> (onPushToTalkStart ?: model::beginPushToTalk)()
                    ControlAction.RobotMicrophone -> mediaControls.toggleRobotMicrophone()
                    ControlAction.Gear1 -> model.selectGear(1)
                    ControlAction.Gear2 -> model.selectGear(2)
                    ControlAction.Gear3 -> model.selectGear(3)
                    ControlAction.Gear4 -> model.selectGear(4)
                    ControlAction.Gear5 -> model.selectGear(5)
                    ControlAction.Stop -> model.haltRemote()
                    else -> Unit
                }
                if (it.key == Key.Escape) model.haltRemote()
                keys = keys + it.key
            }
        }
        supported
    }.focusable()) {
        val landscapeReady = maxWidth >= maxHeight
        LaunchedEffect(landscapeReady) { if (!landscapeReady) model.haltRemote() }
        RobotVideo(model, mediaControls, Modifier.fillMaxSize())
        Canvas(Modifier.align(Alignment.Center).size(36.dp)) {
            drawLine(Color.White.copy(alpha=.7f), Offset(0f,center.y), Offset(size.width,center.y), 2f)
            drawLine(Color.White.copy(alpha=.7f), Offset(center.x,0f), Offset(center.x,size.height), 2f)
            drawCircle(Color.White.copy(alpha=.7f), 4f, style=androidx.compose.ui.graphics.drawscope.Stroke(1f))
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { back -> HudButton(tr(Res.string.back_to_console), "←", action = back) }
            Card(colors = CardDefaults.defaultColors(color = colors.surfaceContainer.copy(alpha = .9f)), insideMargin = PaddingValues(0.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (connected.connected) "S1 · ${connected.battery ?: "—"}%" else connected.status,
                        style = MiuixTheme.textStyles.footnote1)
                    SignalIndicator(connected.signalQuality)
                    HeadingIndicator(connected.gimbal?.yawDegrees)
                }
            }
            Spacer(Modifier.weight(1f))
            if (connected.executionUncertain) HudButton(tr(Res.string.stop_script), tr(Res.string.stop_script), action = model::stop)
            Button(model::haltRemote, colors = ButtonDefaults.buttonColors(
                color = colors.errorContainer, contentColor = colors.onErrorContainer),
                modifier = Modifier.semantics { contentDescription = tr(Res.string.stop_remote_control) }) {
                Text(tr(Res.string.stop_remote_short))
            }
        }
        if (!landscapeReady) {
            Text(tr(Res.string.rotate_for_remote), Modifier.align(Alignment.Center).padding(24.dp),
                color = colors.onSurface, style = MiuixTheme.textStyles.subtitle)
            return@BoxWithConstraints
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Stick(tr(Res.string.chassis), enabled, { keyboardHints = false; focus.requestFocus() },
                control.joystickDeadZone) { left = it }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HudButton(tr(Res.string.shift_down), "−", gear > 1) { model.shiftGear(-1); if (enabled) focus.requestFocus() }
                    Text(if (creeping) tr(Res.string.gear_value_creep, gear) else tr(Res.string.gear_value, gear),
                        color = colors.onSurface, style = MiuixTheme.textStyles.footnote1)
                    HudButton(tr(Res.string.shift_up), "+", gear < DriveSpeed.gearCount) { model.shiftGear(1); if (enabled) focus.requestFocus() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    HudButton(tr(Res.string.switch_ammo), if (gelSelected) tr(Res.string.gel) else tr(Res.string.ir), action = model::switchAmmo)
                    Button(model::fireSelected, enabled = enabled && !connected.busy,
                        modifier = Modifier.semantics { contentDescription = if (gelSelected) tr(Res.string.fire_one_gel_bead) else tr(Res.string.fire_infrared) }) {
                        Text(tr(Res.string.fire))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (enabled) tr(Res.string.active) else tr(Res.string.standby),
                        color = if (enabled) colors.onTertiaryContainer else colors.onSurfaceVariantSummary, fontSize = 12.sp)
                    if (talking) Text(tr(Res.string.talking), color = colors.primary, fontSize = 12.sp)
                    else if (talkBusy) Text(tr(Res.string.sending_talk), color = colors.onSurfaceVariantSummary, fontSize = 12.sp)
                    Switch(checked = enabled, enabled = connected.connected && !connected.busy,
                        modifier = Modifier.semantics { contentDescription = tr(Res.string.enable_remote_control) },
                        onCheckedChange = { if (it) { focus.requestFocus(); model.enableRemote() } else model.haltRemote() })
                }
                if (keyboardHints) Text(keyboardHint(control.shortcuts),
                    Modifier.widthIn(max = 420.dp).testTag("keyboard-hints"), color = colors.onSurfaceVariantSummary, fontSize = 11.sp)
            }
            Stick("${tr(Res.string.gimbal)} · ${control.gimbalSpeed}°/s", enabled,
                { keyboardHints = false; focus.requestFocus() }, control.joystickDeadZone, tr(Res.string.gimbal)) { right = it }
        }
    }
}

private fun keyboardHint(shortcuts: ControlShortcuts): String = listOf(tr(
    Res.string.control_hint_motion_value,
    shortcuts[ControlAction.Forward].label,
    shortcuts[ControlAction.StrafeLeft].label,
    shortcuts[ControlAction.Backward].label,
    shortcuts[ControlAction.StrafeRight].label,
    shortcuts[ControlAction.RotateCounterclockwise].label,
    shortcuts[ControlAction.RotateClockwise].label,
    shortcuts[ControlAction.Gear1].label,
    shortcuts[ControlAction.Gear5].label,
), tr(
    Res.string.control_hint_actions_value,
    shortcuts[ControlAction.GimbalUp].label,
    shortcuts[ControlAction.Fire].label,
    shortcuts[ControlAction.SwitchAmmo].label,
    shortcuts[ControlAction.Photo].label,
    shortcuts[ControlAction.Recording].label,
    shortcuts[ControlAction.PushToTalk].label,
    shortcuts[ControlAction.RobotMicrophone].label,
)).joinToString(" · ")

@Composable
private fun SignalIndicator(quality: Int?) {
    val colors = MiuixTheme.colorScheme
    val bars = when {
        quality == null || quality <= 0 -> 0
        quality < 20 -> 1
        quality < 30 -> 2
        quality < 40 -> 3
        else -> 4
    }
    val description = quality?.let { tr(Res.string.signal_strength_value, it) }
        ?: tr(Res.string.signal_strength_unknown)
    Row(Modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(28.dp, 18.dp)) {
            val width = 4.dp.toPx()
            val gap = 3.dp.toPx()
            repeat(4) { index ->
                val height = (5 + index * 4).dp.toPx()
                drawRoundRect(
                    color = if (index < bars) colors.primary else colors.dividerLine,
                    topLeft = Offset(index * (width + gap), size.height - height),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(width / 2, width / 2),
                )
            }
        }
        Text(quality?.toString() ?: "—", color = colors.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1)
    }
}

@Composable
private fun HeadingIndicator(relativeYaw: Double?) {
    val colors = MiuixTheme.colorScheme
    val degrees = relativeYaw?.takeIf { it.isFinite() }?.let { kotlin.math.round(it).toInt() }
    val angle = degrees?.let { if (it > 0) "+$it°" else "$it°" } ?: "—"
    val description = if (degrees == null) tr(Res.string.chassis_gimbal_angle_unknown)
        else tr(Res.string.chassis_gimbal_angle_value, angle)
    Row(Modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(52.dp, 38.dp)) {
            val bodySize = Size(24.dp.toPx(), 31.dp.toPx())
            val bodyTopLeft = Offset(center.x - bodySize.width / 2, center.y - bodySize.height / 2)
            drawRoundRect(colors.primary.copy(alpha = .20f), bodyTopLeft, bodySize,
                CornerRadius(5.dp.toPx(), 5.dp.toPx()))
            drawRoundRect(colors.primary, bodyTopLeft, bodySize,
                CornerRadius(5.dp.toPx(), 5.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            drawLine(colors.primary, center, Offset(center.x, bodyTopLeft.y - 3.dp.toPx()),
                2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(colors.surfaceContainerHigh, 5.dp.toPx(), center)
            drawCircle(colors.onTertiaryContainer, 3.dp.toPx(), center)
            relativeYaw?.takeIf { it.isFinite() }?.let { yaw ->
                val radians = yaw * kotlin.math.PI / 180
                val direction = Offset(kotlin.math.sin(radians).toFloat(), -kotlin.math.cos(radians).toFloat())
                val tip = center + direction * 17.dp.toPx()
                drawLine(colors.onTertiaryContainer, center, tip, 3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(colors.onTertiaryContainer, 2.5.dp.toPx(), tip)
            }
        }
        Text(angle, color = if (degrees == null) colors.onSurfaceVariantSummary else colors.onSurface,
            fontSize = 11.sp)
    }
}

@Composable private fun Stick(label: String, enabled: Boolean, claimFocus: () -> Unit,
                              deadZone: Double = .12, semanticLabel: String = label, update: (Offset) -> Unit) {
    val colors = MiuixTheme.colorScheme
    var position by remember(enabled) { mutableStateOf(Offset.Zero) }
    val callback by rememberUpdatedState(update)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(132.dp).semantics { contentDescription = tr(Res.string.value_joystick,semanticLabel) }
            .pointerInput(enabled) {
                if (enabled) awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed=false)
                    down.consume(); claimFocus()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            pointer.consume()
                            if (!pointer.pressed) break
                            val p = (pointer.position-Offset(size.width/2f,size.height/2f))/(size.width*.36f)
                            val length = p.getDistance()
                            position = when { length < deadZone.toFloat() -> Offset.Zero; length > 1 -> p/length; else -> p }
                            callback(position)
                        }
                    } finally { position=Offset.Zero; callback(Offset.Zero) }
                }
            }) {
            drawCircle(colors.surfaceContainer.copy(alpha = .82f))
            drawCircle(Color.White.copy(alpha=.3f), style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            drawCircle(if (enabled) colors.primary else colors.outline, size.width*.18f,
                center + position * (size.width*.30f))
        }
        Text(label, color=Color.White)
    }
}

@Composable internal fun HudButton(label: String, text: String, enabled: Boolean = true, action: () -> Unit) {
    Button(onClick = action, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = .94f))) {
        Text(text, fontSize = 14.sp)
    }
}

@Composable internal fun HudIconButton(label: String, text: String, enabled: Boolean = true, action: () -> Unit) {
    Button(onClick = action, enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
        insideMargin = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = .94f))) {
        Text(text, fontSize = 16.sp)
    }
}
