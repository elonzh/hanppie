package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.HanppieBrandAssets
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.settings.ControlAction
import cn.elonzh.hanppie.ui.settings.ControlShortcuts
import cn.elonzh.hanppie.ui.settings.edgeAction
import cn.elonzh.hanppie.ui.settings.isHeld
import cn.elonzh.hanppie.ui.settings.supports
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.painterResource
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal expect fun RemoteOrientation(onBack: (() -> Unit)?)

@Composable internal expect fun RobotVideo(model: ConsoleController, controls: RemoteMediaController, modifier: Modifier)

@Composable
internal fun RemotePage(
    model: ConsoleController,
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
    val microphoneReady by model.microphoneReady.collectAsState()
    val talkBusy by model.talkBusy.collectAsState()
    val mediaControls = remember { RemoteMediaController() }
    val mediaState by mediaControls.state.collectAsState()
    var left by remember { mutableStateOf(Offset.Zero) }
    var right by remember { mutableStateOf(Offset.Zero) }
    val foreground by model.foregroundState.collectAsState()
    var pageFocused by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf(setOf<Key>()) }
    val focus = remember { FocusRequester() }
    val currentLeft by rememberUpdatedState(left)
    val currentRight by rememberUpdatedState(right)
    val currentKeys by rememberUpdatedState(keys)
    val currentGear by rememberUpdatedState(gear)
    val currentControl by rememberUpdatedState(control)
    val ledState = remoteLedState(enabled, mediaState.recording, talking || talkBusy)
    LaunchedEffect(connected.connected, ledState, control.remoteLeds) {
        if (connected.connected) model.setRemoteLed(control.remoteLeds.color(ledState))
    }
    DisposableEffect(model) { onDispose {
        (onPushToTalkStop ?: model::endPushToTalk)()
        model.setRemoteLed(null)
        model.leaveRemote()
        model.stopMedia()
    } }
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
                currentGear, currentControl)
            val rotation = axis(ControlAction.RotateClockwise, ControlAction.RotateCounterclockwise) *
                DriveSpeed.rotation(currentGear, currentControl)
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
        pageFocused = it.hasFocus
        if (!it.hasFocus) {
            keys = emptySet(); left = Offset.Zero; right = Offset.Zero
            model.haltRemote()
        }
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
                    ControlAction.Fire -> if (enabled) model.fireSelected()
                    ControlAction.SwitchAmmo -> model.switchAmmo()
                    ControlAction.Photo -> if (enabled) mediaControls.takePhoto()
                    ControlAction.Recording -> if (enabled) mediaControls.toggleRecording()
                    ControlAction.PushToTalk -> if (enabled) (onPushToTalkStart ?: model::beginPushToTalk)()
                    ControlAction.RobotMicrophone -> if (enabled) mediaControls.toggleRobotMicrophone()
                    ControlAction.Gear1 -> model.selectGear(1)
                    ControlAction.Gear2 -> model.selectGear(2)
                    ControlAction.Gear3 -> model.selectGear(3)
                    ControlAction.Gear4 -> model.selectGear(4)
                    ControlAction.Gear5 -> model.selectGear(5)
                    ControlAction.Stop -> if (onBack != null) onBack() else model.haltRemote()
                    else -> Unit
                }
                if (it.key == Key.Escape && control.shortcuts.edgeAction(it.key, it.isShiftPressed) != ControlAction.Stop)
                    if (onBack != null) onBack() else model.haltRemote()
                keys = keys + it.key
            }
        }
        supported
    }.focusable()) {
        val landscapeReady = maxWidth >= maxHeight
        val compactHud = maxWidth < 800.dp
        LaunchedEffect(landscapeReady) { if (!landscapeReady) model.haltRemote() }
        LaunchedEffect(landscapeReady, foreground, connected.connected) {
            keys = emptySet(); left = Offset.Zero; right = Offset.Zero
            if (landscapeReady && foreground) focus.requestFocus()
        }
        LaunchedEffect(landscapeReady, foreground, pageFocused, connected.connected) {
            if (landscapeReady && foreground && pageFocused && connected.connected) {
                snapshotFlow { connected.busy }.first { !it }
                if (!model.remoteEnabled.value) model.enableRemote()
            }
        }
        RobotVideo(model, mediaControls, Modifier.fillMaxSize())
        Canvas(Modifier.align(Alignment.Center).size(24.dp)) {
            // Open center preserves the target; a dark halo remains visible over bright video.
            val gap = 5.dp.toPx()
            val edge = 10.dp.toPx()
            val segments = listOf(
                Offset(center.x - edge, center.y) to Offset(center.x - gap, center.y),
                Offset(center.x + gap, center.y) to Offset(center.x + edge, center.y),
                Offset(center.x, center.y - edge) to Offset(center.x, center.y - gap),
                Offset(center.x, center.y + gap) to Offset(center.x, center.y + edge))
            segments.forEach { (a, b) ->
                drawLine(Color.Black.copy(alpha = .55f), a, b, 3.dp.toPx(), StrokeCap.Round)
                drawLine(Color.White.copy(alpha = .85f), a, b, 1.dp.toPx(), StrokeCap.Round)
            }
        }
        Row(Modifier.align(Alignment.TopStart).padding(HanppieDesignTokens.RemoteEdgePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { back -> HudIconButton(tr(Res.string.back_to_console), WorkbenchGlyph.BACK, action = back) }
            if (connected.canStop) HudIconButton(tr(Res.string.stop_script), WorkbenchGlyph.STOP, action = model::stop)
        }
        val leftHudWidth = (if (onBack != null) 48 else 0) + (if (connected.canStop) 56 else 0)
        val mediaHudWidth = 216
        val hudOffset = if (compactHud) ((leftHudWidth - mediaHudWidth) / 2).dp else 0.dp
        Card(Modifier.align(Alignment.TopCenter).offset(x = hudOffset)
            .widthIn(max = (maxWidth - (leftHudWidth + mediaHudWidth + 48).dp).coerceAtLeast(80.dp))
            .padding(top = HanppieDesignTokens.RemoteEdgePadding)
            .testTag("remote-telemetry"),
            colors = CardDefaults.defaultColors(color = HanppieDesignTokens.RemoteHudSurface.copy(alpha = HanppieDesignTokens.RemoteHudAlpha)),
            insideMargin = PaddingValues(0.dp)) {
                Row(Modifier.padding(horizontal = if (compactHud) 8.dp else 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (compactHud) 6.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(HanppieBrandAssets.expression(ledState)), null,
                        Modifier.size(if (compactHud) 28.dp else 40.dp, if (compactHud) 14.dp else 20.dp))
                    Text(if (connected.connected) "S1 · ${connected.battery ?: "—"}%" else tr(Res.string.not_connected),
                        color = HanppieDesignTokens.RemoteHudContent, style = MiuixTheme.textStyles.footnote1)
                    SignalIndicator(connected.signalQuality, compactHud)
                    HeadingIndicator(connected.gimbal?.yawDegrees)
                }
        }
        if (!landscapeReady) {
            Text(tr(Res.string.rotate_for_remote), Modifier.align(Alignment.Center).padding(24.dp),
                color = colors.onSurface, style = MiuixTheme.textStyles.subtitle)
            return@BoxWithConstraints
        }
        Column(Modifier.align(Alignment.BottomStart).padding(HanppieDesignTokens.RemoteEdgePadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = .82f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = .18f), CircleShape).padding(2.dp)) {
                repeat(DriveSpeed.gearCount) { index ->
                    val value = index + 1
                    val gearLabel = tr(Res.string.gear_value, value)
                    IconButton(onClick = { model.selectGear(value); focus.requestFocus() },
                        modifier = Modifier.size(HanppieDesignTokens.TouchTarget).semantics {
                            contentDescription = gearLabel
                        }, cornerRadius = 24.dp,
                        backgroundColor = if (gear == value) colors.primary else Color.Transparent) {
                        Text(value.toString(), color = if (gear == value) colors.onPrimary else HanppieDesignTokens.RemoteHudContent,
                            fontSize = 14.sp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stick(tr(Res.string.chassis),
                    foreground && enabled, { keyboardHints = false; focus.requestFocus() }, control.joystickDeadZone,
                    tr(Res.string.chassis)) { left = it }

            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(HanppieDesignTokens.RemoteEdgePadding),
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HudIconButton(if (gelSelected) tr(Res.string.fire_one_gel_bead) else tr(Res.string.fire_infrared),
                    WorkbenchGlyph.CROSSHAIR, enabled = enabled && !connected.busy, selected = true,
                    action = model::fireSelected)
                HudButton(tr(Res.string.switch_ammo), if (gelSelected) tr(Res.string.gel) else tr(Res.string.ir), symbol = WorkbenchGlyph.PACKETS) {
                    model.switchAmmo(); focus.requestFocus()
                }
            }
            Stick("${tr(Res.string.gimbal)} · ${control.gimbalSpeed}°/s", foreground && enabled,
                { keyboardHints = false; focus.requestFocus() }, control.joystickDeadZone,
                tr(Res.string.gimbal)) { right = it }
        }
        if (talking || talkBusy) Text(if (talking) tr(if (microphoneReady) Res.string.talking else Res.string.microphone_preparing) else tr(Res.string.sending_talk),
            Modifier.align(Alignment.BottomCenter).padding(bottom = 54.dp), color = HanppieDesignTokens.RemoteHudContent,
            fontSize = 12.sp)
        if (keyboardHints) {
            Text(keyboardHint(control.shortcuts), Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
                .widthIn(max = 380.dp).background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = .72f),
                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("keyboard-hints"), color = HanppieDesignTokens.RemoteHudMuted, fontSize = 10.sp, maxLines = 2)
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
private fun SignalIndicator(quality: Int?, compact: Boolean = false) {
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
        Canvas(Modifier.size(if (compact) 20.dp else 28.dp, if (compact) 16.dp else 18.dp)) {
            val width = (if (compact) 3.dp else 4.dp).toPx()
            val gap = (if (compact) 2.dp else 3.dp).toPx()
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
        Text(quality?.toString() ?: "—", color = HanppieDesignTokens.RemoteHudMuted,
            style = MiuixTheme.textStyles.footnote1)
    }
}

@Composable private fun Stick(label: String, enabled: Boolean, claimFocus: () -> Unit,
                              deadZone: Double = .12, semanticLabel: String = label, update: (Offset) -> Unit) {
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
            val radius = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Color(0xff34414d).copy(alpha = .84f),
                HanppieDesignTokens.RemoteHudSurface.copy(alpha = .9f)), radius = radius))
            drawCircle(Color.White.copy(alpha = .32f), radius - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            drawCircle(Color.White.copy(alpha = .10f), radius * .76f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            for (direction in listOf(Offset(0f, -1f), Offset(1f, 0f), Offset(0f, 1f), Offset(-1f, 0f))) {
                drawCircle(HanppieDesignTokens.RemoteHudMuted, 1.5.dp.toPx(), center + direction * radius * .85f)
            }
            val thumbCenter = center + position * (size.width * .30f)
            val thumbRadius = size.width * .17f
            drawCircle(Color.Black.copy(alpha = .22f), thumbRadius + 2.dp.toPx(), thumbCenter + Offset(0f, 2.dp.toPx()))
            drawCircle(Brush.linearGradient(listOf(Color(0xff829eac), Color(0xff496776)),
                start = thumbCenter - Offset(0f, thumbRadius), end = thumbCenter + Offset(0f, thumbRadius)), thumbRadius, thumbCenter)
            drawCircle(Color(0xffb8cbd4).copy(alpha = if (enabled) .8f else .45f), thumbRadius, thumbCenter,
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        Text(label, color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable internal fun HudButton(label: String, text: String, enabled: Boolean = true,
                                   selected: Boolean = false, modifier: Modifier = Modifier, symbol: WorkbenchGlyph? = null, action: () -> Unit) {
    Button(onClick = action, enabled = enabled,
        modifier = modifier.heightIn(min = HanppieDesignTokens.TouchTarget).semantics { contentDescription = label },
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            color = (if (selected) MiuixTheme.colorScheme.primary else HanppieDesignTokens.RemoteHudSurface).copy(alpha = .94f),
            contentColor = if (selected) MiuixTheme.colorScheme.onPrimary else HanppieDesignTokens.RemoteHudContent)) {
        symbol?.let {
            WorkbenchIcon(it, if (selected) MiuixTheme.colorScheme.onPrimary else HanppieDesignTokens.RemoteHudContent,
                Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 14.sp)
    }
}

@Composable internal fun HudIconButton(label: String, symbol: WorkbenchGlyph, enabled: Boolean = true,
    selected: Boolean = false, action: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val surface = if (selected && enabled) colors.primary else HanppieDesignTokens.RemoteHudSurface.copy(alpha = .88f)
    val ink = when {
        symbol == WorkbenchGlyph.RECORD || symbol == WorkbenchGlyph.STOP -> Color(0xffff7165)
        selected && enabled -> colors.onPrimary
        else -> HanppieDesignTokens.RemoteHudContent
    }
    IconButton(onClick = action, enabled = enabled, cornerRadius = 24.dp,
        modifier = Modifier.size(HanppieDesignTokens.TouchTarget)
            .border(1.dp, Color.White.copy(alpha = .22f), CircleShape)
            .semantics { contentDescription = label }, backgroundColor = surface) {
        WorkbenchIcon(symbol, ink.copy(alpha = if (enabled) 1f else .45f))
    }
}
