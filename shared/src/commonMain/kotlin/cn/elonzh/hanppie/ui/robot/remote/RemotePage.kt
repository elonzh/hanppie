package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.ui.robot.telemetry.signalQualityLabel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import cn.elonzh.hanppie.resources.official_ammo_infrared
import cn.elonzh.hanppie.resources.official_ammo_gel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.back_to_console
import cn.elonzh.hanppie.resources.chassis
import cn.elonzh.hanppie.resources.control_hint_actions_value
import cn.elonzh.hanppie.resources.control_hint_motion_value
import cn.elonzh.hanppie.resources.fire_infrared
import cn.elonzh.hanppie.resources.fire_one_gel_bead
import cn.elonzh.hanppie.resources.gear_value
import cn.elonzh.hanppie.resources.gel
import cn.elonzh.hanppie.resources.gimbal
import cn.elonzh.hanppie.resources.ir
import cn.elonzh.hanppie.resources.microphone_preparing
import cn.elonzh.hanppie.resources.not_connected
import cn.elonzh.hanppie.resources.rotate_for_remote
import cn.elonzh.hanppie.resources.sending_talk
import cn.elonzh.hanppie.resources.signal_strength_unknown
import cn.elonzh.hanppie.resources.signal_strength_value
import cn.elonzh.hanppie.resources.stop_script
import cn.elonzh.hanppie.resources.switch_ammo
import cn.elonzh.hanppie.resources.talking
import cn.elonzh.hanppie.resources.value_joystick
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.robot.device.BatteryIcon
import cn.elonzh.hanppie.resources.recenter_gimbal
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
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
    mediaControls: RemoteMediaController = remember { RemoteMediaController() },
    videoContent: (@Composable () -> Unit)? = null,
    inputEnabled: Boolean = true,
    navigationEnabled: Boolean = inputEnabled,
    manageSession: Boolean = true,
) {
    RemoteOrientation(onBack)
    var keyboardHints by remember { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    val connected by model.state.collectAsState()
    val remoteEnabled by model.remoteEnabled.collectAsState()
    val foreground by model.foregroundState.collectAsState()
    val enabled = remoteEnabled && inputEnabled && foreground
    val gelSelected by model.gelSelected.collectAsState()
    val gear by model.driveGear.collectAsState()
    val control by model.controlSettings.collectAsState()
    val talking by model.talking.collectAsState()
    val microphoneReady by model.microphoneReady.collectAsState()
    val talkBusy by model.talkBusy.collectAsState()
    val mediaState by mediaControls.state.collectAsState()
    var left by remember { mutableStateOf(Offset.Zero) }
    var right by remember { mutableStateOf(Offset.Zero) }
    var pageFocused by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf(setOf<Key>()) }
    var firePointerHeld by remember { mutableStateOf(false) }
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
        model.stopFiring()
        (onPushToTalkStop ?: model::endPushToTalk)()
        model.setRemoteLed(null)
        if (manageSession) { model.leaveRemote(); model.stopMedia() }
    } }
    LaunchedEffect(enabled) {
        if (enabled) focus.requestFocus()
        else { keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; model.stopFiring() }
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
    val currentShiftHeld = Key.ShiftLeft in currentKeys || Key.ShiftRight in currentKeys
    val fireKeyHeld = currentControl.shortcuts.isHeld(ControlAction.Fire, currentKeys, currentShiftHeld)
    val shouldFire = enabled && (fireKeyHeld || firePointerHeld)
    LaunchedEffect(shouldFire) {
        if (shouldFire) model.startFiring() else model.stopFiring()
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("remote-surface").background(if (videoContent == null) colors.background else Color.Transparent).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.changes.any { it.changedToUpIgnoreConsumed() && !it.isConsumed }) {
                    keyboardHints = false
                    focus.requestFocus()
                }
            }
        }
    }.focusRequester(focus).onFocusChanged {
        pageFocused = it.hasFocus
        if (!it.hasFocus) {
            keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false
            model.stopFiring()
            model.drive(0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }.onPreviewKeyEvent {
        if (!inputEnabled || !foreground) return@onPreviewKeyEvent false
        val supported = control.shortcuts.supports(it.key)
        if (supported && enabled && it.type == KeyEventType.KeyDown) {
            keyboardHints = true
            left = Offset.Zero; right = Offset.Zero; firePointerHeld = false
        }
        if (supported) {
            if (it.type == KeyEventType.KeyUp) {
                if (control.shortcuts[ControlAction.PushToTalk].key.matches(it.key))
                    (onPushToTalkStop ?: model::endPushToTalk)()
                keys = keys - it.key
            }
            else if (it.type == KeyEventType.KeyDown) {
                if (it.key !in keys) when (control.shortcuts.edgeAction(it.key,
                        it.isShiftPressed || Key.ShiftLeft in keys || Key.ShiftRight in keys)) {
                    ControlAction.Fire -> Unit
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
        LaunchedEffect(landscapeReady) { if (!landscapeReady) { firePointerHeld = false; model.stopFiring(); model.haltRemote() } }
        LaunchedEffect(landscapeReady, foreground, connected.connected) {
            keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; model.stopFiring()
            if (landscapeReady && foreground) focus.requestFocus()
        }
        LaunchedEffect(landscapeReady, foreground, pageFocused, connected.connected, inputEnabled) {
            if (manageSession && inputEnabled && landscapeReady && foreground && pageFocused && connected.connected) {
                snapshotFlow { connected.busy }.first { !it }
                if (!model.remoteEnabled.value) model.enableRemote()
            }
        }
        if (videoContent == null) RobotVideo(model, mediaControls, Modifier.fillMaxSize()) else videoContent()
        RemoteCrosshair(model, Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.TopStart).padding(HanppieDesignTokens.RemoteEdgePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { back -> HudIconButton(tr(Res.string.back_to_console), WorkbenchGlyph.BACK, enabled = navigationEnabled && foreground, action = back) }
            if (connected.canStop) HudIconButton(tr(Res.string.stop_script), WorkbenchGlyph.STOP, enabled = inputEnabled && foreground, action = model::stop)
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
                Row(Modifier.padding(horizontal = if (compactHud) 8.dp else 12.dp, vertical = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        BatteryIcon(connected.battery, HanppieDesignTokens.RemoteHudContent)
                        Text(if (connected.connected) "${connected.battery ?: "—"}%" else "—",
                            modifier = Modifier.width(34.dp), maxLines = 1,
                            color = HanppieDesignTokens.RemoteHudContent, style = MiuixTheme.textStyles.footnote1)
                    }
                    SignalIndicator(connected.signalQuality)
                    HeadingIndicator(connected.gimbal?.yawDegrees, Modifier.heightIn(min = 48.dp)
                        .testTag("recenter-gimbal")
                        .clickable(enabled = enabled && connected.gimbal != null, onClickLabel = tr(Res.string.recenter_gimbal)) {
                            model.recenterGimbal(); focus.requestFocus()
                        })
                }
        }
        if (!landscapeReady) {
            Text(tr(Res.string.rotate_for_remote), Modifier.align(Alignment.Center).padding(24.dp),
                color = colors.onSurface, style = MiuixTheme.textStyles.subtitle)
            return@BoxWithConstraints
        }
        val touchInset = (maxWidth * .06f).coerceIn(32.dp, 80.dp)
        if (!keyboardHints) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = touchInset, bottom = 32.dp).testTag("remote-touch-left"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.testTag("remote-gear-bar").drawBehind {
                    val height = 28.dp.toPx()
                    drawRoundRect(HanppieDesignTokens.RemoteHudSurface.copy(alpha = .55f),
                        topLeft = Offset(0f, (size.height - height) / 2), size = Size(size.width, height),
                        cornerRadius = CornerRadius(height / 2))
                }) {
                    repeat(DriveSpeed.gearCount) { index ->
                        val value = index + 1
                        Box(Modifier.size(32.dp, 48.dp).clickable(enabled = enabled, role = Role.RadioButton) {
                            model.selectGear(value); focus.requestFocus()
                        }.semantics { contentDescription = tr(Res.string.gear_value, value) }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(24.dp).background(
                                if (gear == value) colors.primary.copy(alpha = .7f) else Color.Transparent,
                                CircleShape), contentAlignment = Alignment.Center) {
                                Text(value.toString(), color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stick(tr(Res.string.chassis),
                        foreground && enabled, { keyboardHints = false; focus.requestFocus() }, control.joystickDeadZone,
                        tr(Res.string.chassis)) { left = it }

                }
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(end = touchInset, bottom = 32.dp).testTag("remote-touch-right"),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HudIconButton(if (gelSelected) tr(Res.string.fire_one_gel_bead) else tr(Res.string.fire_infrared),
                        WorkbenchGlyph.CROSSHAIR, enabled = enabled && !connected.busy, selected = firePointerHeld,
                        onPressChange = { held ->
                            firePointerHeld = held
                            if (held) {
                                keyboardHints = false
                                focus.requestFocus()
                            }
                        },
                        action = {
                            model.fireSelected()
                            focus.requestFocus()
                        })

                }
                Stick("${tr(Res.string.gimbal)} · ${control.gimbalSpeed}°/s", foreground && enabled,
                    { keyboardHints = false; focus.requestFocus() }, control.joystickDeadZone,
                    tr(Res.string.gimbal)) { right = it }
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = touchInset + 76.dp, bottom = 180.dp).testTag("remote-ammo")) {
                HudIconButton(tr(Res.string.switch_ammo),
                    WorkbenchGlyph.PACKETS,
                    artwork = if (gelSelected) Res.drawable.official_ammo_gel else Res.drawable.official_ammo_infrared,
                    enabled = enabled, stateLabel = if (gelSelected) tr(Res.string.gel) else tr(Res.string.ir)) {
                    model.switchAmmo(); focus.requestFocus()
                }
            }
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
private fun SignalIndicator(quality: Int?) {
    val label = signalQualityLabel(quality)
    val description = tr(Res.string.signal_strength_value, label)
    val glyph = when {
        quality == null || quality !in 0..255 -> WorkbenchGlyph.SIGNAL_UNKNOWN
        quality < 20 -> WorkbenchGlyph.SIGNAL_LOW
        quality < 40 -> WorkbenchGlyph.SIGNAL_MEDIUM
        else -> WorkbenchGlyph.SIGNAL
    }
    Box(Modifier.size(20.dp).testTag("remote-signal").semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        WorkbenchIcon(glyph, HanppieDesignTokens.RemoteHudContent, Modifier.size(20.dp))
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
            drawCircle(Brush.radialGradient(listOf(Color(0xff34414d).copy(alpha = .48f),
                HanppieDesignTokens.RemoteHudSurface.copy(alpha = .58f)), radius = radius))
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

@Composable internal fun HudIconButton(label: String, symbol: WorkbenchGlyph, enabled: Boolean = true,
    selected: Boolean = false, stateLabel: String? = null, artwork: DrawableResource? = null, onPressChange: ((Boolean) -> Unit)? = null, action: () -> Unit = {}) {
    val colors = MiuixTheme.colorScheme
    val surface = if (selected) colors.primary else HanppieDesignTokens.RemoteHudSurface.copy(alpha = .60f)
    val ink = when {
        symbol == WorkbenchGlyph.RECORD || symbol == WorkbenchGlyph.STOP -> Color(0xffff7165)
        selected -> colors.onPrimary
        else -> HanppieDesignTokens.RemoteHudContent
    }
    val currentOnPressChange by rememberUpdatedState(onPressChange)
    val holdModifier = if (onPressChange != null) {
        Modifier.pointerInput(enabled) {
            if (enabled) awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                currentOnPressChange?.invoke(true)
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) break
                    }
                } finally {
                    currentOnPressChange?.invoke(false)
                }
            }
        }
    } else Modifier
    IconButton(onClick = action, enabled = enabled, cornerRadius = 24.dp,
        modifier = Modifier.size(HanppieDesignTokens.TouchTarget)
            .then(holdModifier)
            .drawBehind {
                val radius = size.minDimension / 2 - 4.dp.toPx()
                if (artwork == null) {
                    drawCircle(surface.copy(alpha = if (selected) .75f else .60f), radius)
                    drawCircle(Color.White.copy(alpha = .22f), radius,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
            }
            .semantics { contentDescription = label; stateLabel?.let { stateDescription = it }; if (!enabled) disabled() }, backgroundColor = Color.Transparent) {
        if (artwork != null) Image(painterResource(artwork), null, Modifier.size(40.dp))
        else WorkbenchIcon(symbol, ink, Modifier.size(20.dp))
    }
}
