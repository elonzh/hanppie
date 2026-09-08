package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

@Composable internal expect fun RobotVideo(model: ConsoleModel, modifier: Modifier)

@Composable
internal fun RemotePage(model: ConsoleModel, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    RemoteOrientation(onBack)
    var keyboardHints by remember { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    val connected by model.state.collectAsState()
    val enabled by model.remoteEnabled.collectAsState()
    val cameraYaw by model.cameraYaw.collectAsState()
    val gelSelected by model.gelSelected.collectAsState()
    val gear by model.driveGear.collectAsState()
    var left by remember { mutableStateOf(Offset.Zero) }
    var right by remember { mutableStateOf(Offset.Zero) }
    var keys by remember { mutableStateOf(setOf<Key>()) }
    val focus = remember { FocusRequester() }
    val currentLeft by rememberUpdatedState(left)
    val currentRight by rememberUpdatedState(right)
    val currentKeys by rememberUpdatedState(keys)
    val currentGear by rememberUpdatedState(gear)
    val creeping = Key.ShiftLeft in keys || Key.ShiftRight in keys
    DisposableEffect(model) { onDispose { model.leaveRemote(); model.stopMedia() } }
    LaunchedEffect(enabled) {
        if (enabled) focus.requestFocus()
        else { keys = emptySet(); left = Offset.Zero; right = Offset.Zero }
    }
    LaunchedEffect(enabled) {
        while (enabled) {
            fun axis(positive: Key, negative: Key) = (if (positive in currentKeys) 1 else 0) - (if (negative in currentKeys) 1 else 0)
            val translation = DriveSpeed.translation(
                (-currentLeft.y + axis(Key.W, Key.S)).coerceIn(-1f,1f).toDouble(),
                (currentLeft.x + axis(Key.D, Key.A)).coerceIn(-1f,1f).toDouble(), currentGear,
                Key.ShiftLeft in currentKeys || Key.ShiftRight in currentKeys)
            model.drive(translation.first, translation.second, 0.0,
                (-currentRight.y + axis(Key.DirectionUp, Key.DirectionDown)).coerceIn(-1f,1f).toDouble() * 30,
                (currentRight.x + axis(Key.DirectionRight, Key.DirectionLeft)).coerceIn(-1f,1f).toDouble() * 30)
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
        val supported = it.key in listOf(Key.W,Key.A,Key.S,Key.D,Key.Q,Key.E,Key.R,Key.ShiftLeft,Key.ShiftRight,Key.DirectionUp,Key.DirectionDown,Key.DirectionLeft,Key.DirectionRight,Key.Spacebar,Key.Escape)
        if (it.type == KeyEventType.KeyDown) keyboardHints = true
        if (supported) {
            if (it.type == KeyEventType.KeyUp) keys = keys - it.key
            else if (it.type == KeyEventType.KeyDown) {
                if (it.key == Key.Spacebar && it.key !in keys) model.fireSelected()
                if (it.key == Key.R && it.key !in keys) model.switchAmmo()
                if (it.key == Key.Q && it.key !in keys) model.shiftGear(-1)
                if (it.key == Key.E && it.key !in keys) model.shiftGear(1)
                if (it.key == Key.Escape) model.haltRemote()
                keys = keys + it.key
            }
        }
        supported
    }.focusable()) {
        val landscapeReady = maxWidth >= maxHeight
        LaunchedEffect(landscapeReady) { if (!landscapeReady) model.haltRemote() }
        RobotVideo(model, Modifier.fillMaxSize())
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
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("S1 · ${connected.battery ?: "—"}%", style = MiuixTheme.textStyles.footnote1)
                    Text("${connected.packets} RX", style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary)
                    Canvas(Modifier.size(24.dp).semantics { contentDescription = tr(Res.string.chassis_heading_relative_to_camera) }) {
                        val a = -(cameraYaw ?: 0.0) * kotlin.math.PI / 180
                        val direction = Offset(kotlin.math.sin(a).toFloat(), -kotlin.math.cos(a).toFloat())
                        drawCircle(colors.dividerLine, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                        drawLine(if (cameraYaw != null && enabled) colors.onTertiaryContainer else colors.outline,
                            center - direction * 5.dp.toPx(), center + direction * 9.dp.toPx(), 3.dp.toPx())
                    }
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
            Stick(tr(Res.string.chassis), enabled, { keyboardHints = false; focus.requestFocus() }) { left = it }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HudButton(tr(Res.string.shift_down), "−", gear > 1) { model.shiftGear(-1); if (enabled) focus.requestFocus() }
                    Text(if (creeping) tr(Res.string.gear_value_creep, gear) else tr(Res.string.gear_value, gear),
                        color = colors.onSurface, style = MiuixTheme.textStyles.footnote1)
                    HudButton(tr(Res.string.shift_up), "+", gear < DriveSpeed.gears.size) { model.shiftGear(1); if (enabled) focus.requestFocus() }
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
                    Switch(checked = enabled, enabled = !connected.busy,
                        modifier = Modifier.semantics { contentDescription = tr(Res.string.enable_remote_control) },
                        onCheckedChange = { if (it) { focus.requestFocus(); model.enableRemote() } else model.haltRemote() })
                }
                if (keyboardHints) Text(tr(Res.string.wasd_move_q_e_gears_shift_creep_arrows_aim),
                    Modifier.widthIn(max = 420.dp).testTag("keyboard-hints"), color = colors.onSurfaceVariantSummary, fontSize = 11.sp)
            }
            Stick(tr(Res.string.gimbal), enabled, { keyboardHints = false; focus.requestFocus() }) { right = it }
        }
    }
}

@Composable private fun Stick(label: String, enabled: Boolean, claimFocus: () -> Unit, update: (Offset) -> Unit) {
    val colors = MiuixTheme.colorScheme
    var position by remember(enabled) { mutableStateOf(Offset.Zero) }
    val callback by rememberUpdatedState(update)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(132.dp).semantics { contentDescription = tr(Res.string.value_joystick,label) }
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
                            position = when { length < .12f -> Offset.Zero; length > 1 -> p/length; else -> p }
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
