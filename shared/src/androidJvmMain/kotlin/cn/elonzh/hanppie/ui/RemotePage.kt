package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*

@Composable internal expect fun RobotVideo(model: ConsoleModel, modifier: Modifier)

@Composable
internal fun RemotePage(model: ConsoleModel, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, expanded: Boolean = false) {
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
    BoxWithConstraints(modifier.fillMaxSize().testTag("remote-surface").background(Color(0xff101721)).focusRequester(focus).onFocusChanged {
        if (!it.hasFocus) { keys = emptySet(); left = Offset.Zero; right = Offset.Zero; model.haltRemote() }
    }.onPreviewKeyEvent {
        val supported = it.key in listOf(Key.W,Key.A,Key.S,Key.D,Key.Q,Key.E,Key.R,Key.ShiftLeft,Key.ShiftRight,Key.DirectionUp,Key.DirectionDown,Key.DirectionLeft,Key.DirectionRight,Key.Spacebar,Key.Escape)
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
        val wide = maxWidth >= 600.dp
        RobotVideo(model, Modifier.fillMaxSize())
        Canvas(Modifier.align(Alignment.Center).size(36.dp)) {
            drawLine(Color.White.copy(alpha=.7f), Offset(0f,center.y), Offset(size.width,center.y), 2f)
            drawLine(Color.White.copy(alpha=.7f), Offset(center.x,0f), Offset(center.x,size.height), 2f)
            drawCircle(Color.White.copy(alpha=.7f), 4f, style=androidx.compose.ui.graphics.drawscope.Stroke(1f))
        }
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            onBack?.let { HudButton(if(expanded) tr(Res.string.back_to_console) else tr(Res.string.fullscreen_cockpit),if(expanded) tr(Res.string.console) else tr(Res.string.fullscreen),action=it) }
            Text("S1 · ${connected.battery ?: "—"}%", Modifier.background(Color(0xcc152233)).padding(12.dp), color=Color.White)
            HudButton(tr(Res.string.disconnect_close_session), tr(Res.string.exit), action=model::disconnect)
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(16.dp), horizontalAlignment=Alignment.CenterHorizontally) {
            Canvas(Modifier.size(48.dp).semantics { contentDescription = tr(Res.string.chassis_heading_relative_to_camera) }) {
                drawCircle(Color(0xbb192b42))
                val a = -(cameraYaw ?: 0.0) * kotlin.math.PI / 180
                val direction = Offset(kotlin.math.sin(a).toFloat(), -kotlin.math.cos(a).toFloat())
                drawLine(if (enabled && cameraYaw != null) Color(0xff63e0af) else Color.Gray,
                    center - direction * 10.dp.toPx(), center + direction * 16.dp.toPx(), 4.dp.toPx())
                drawCircle(Color.White, 3.dp.toPx(), center + direction * 16.dp.toPx())
            }
            Text(if (!enabled) tr(Res.string.chassis_heading) else if (cameraYaw == null) tr(Res.string.awaiting_heading) else tr(Res.string.camera_relative), color=Color.White,fontSize=11.sp)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.align(Alignment.Start), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                HudButton(tr(Res.string.shift_down), "−", gear > 1) { model.shiftGear(-1); if(enabled) focus.requestFocus() }
                Text(if(creeping) tr(Res.string.gear_value_creep,gear) else tr(Res.string.gear_value,gear), color=if(creeping) Color(0xff63e0af) else Color.White)
                HudButton(tr(Res.string.shift_up), "+", gear < DriveSpeed.gears.size) { model.shiftGear(1); if(enabled) focus.requestFocus() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.Bottom) {
                Stick(tr(Res.string.chassis), enabled, { focus.requestFocus() }) { left=it }
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    HudButton(tr(Res.string.switch_ammo), if(gelSelected) tr(Res.string.gel) else tr(Res.string.ir), action=model::switchAmmo)
                    HudButton(if(gelSelected) tr(Res.string.fire_one_gel_bead) else tr(Res.string.fire_infrared), tr(Res.string.fire), enabled && !connected.busy, model::fireSelected)
                }
                Stick(tr(Res.string.gimbal), enabled, { focus.requestFocus() }) { right=it }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                Text(if(enabled) tr(Res.string.active) else tr(Res.string.standby),color=if(enabled) Color(0xff63e0af) else Color.White)
                if(connected.executionUncertain) HudButton(tr(Res.string.stop_script),tr(Res.string.stop_script),action=model::stop)
                HudButton(if(enabled) tr(Res.string.stop_remote_control) else tr(Res.string.enable_remote_control),if(enabled) tr(Res.string.stop_esc) else tr(Res.string.start_control),!connected.busy) {
                    if(enabled) model.haltRemote() else { focus.requestFocus(); model.enableRemote() }
                }
            }
            if(wide) Text(tr(Res.string.wasd_move_q_e_gears_shift_creep_arrows_aim),color=Color.White.copy(alpha=.65f),fontSize=11.sp)
        }
    }
}

@Composable private fun Stick(label: String, enabled: Boolean, claimFocus: () -> Unit, update: (Offset) -> Unit) {
    var position by remember(enabled) { mutableStateOf(Offset.Zero) }
    val callback by rememberUpdatedState(update)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(112.dp).semantics { contentDescription = tr(Res.string.value_joystick,label) }
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
            drawCircle(Color(0xbb192b42))
            drawCircle(Color.White.copy(alpha=.3f), style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            drawCircle(if (enabled) Color(0xff3868e8) else Color.Gray, size.width*.18f,
                center + position * (size.width*.30f))
        }
        Text(label, color=Color.White)
    }
}

@Composable internal fun HudButton(label: String, text: String, enabled: Boolean = true, action: () -> Unit) {
    Box(Modifier.semantics { contentDescription=label }
        .background(Color(0xdf192b42),RoundedCornerShape(16.dp))
        .border(1.dp,Color.White.copy(alpha=.2f),RoundedCornerShape(16.dp))
        .clickable(enabled=enabled,onClick=action)
        .padding(horizontal=14.dp,vertical=12.dp),contentAlignment=Alignment.Center) {
        Text(text,color=Color.White.copy(alpha=if(enabled) 1f else .35f),fontSize=14.sp)
    }
}
