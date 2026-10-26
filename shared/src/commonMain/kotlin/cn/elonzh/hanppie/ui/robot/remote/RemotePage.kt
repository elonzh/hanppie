package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.ui.design.WorkbenchToast
import cn.elonzh.hanppie.robot.challenge.*

import cn.elonzh.hanppie.resources.camera_zoom_tracking_unavailable

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
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
import cn.elonzh.hanppie.ui.robot.remote.input.*
import kotlin.time.TimeSource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import cn.elonzh.hanppie.resources.arm_control_label
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
    onAlbum: (() -> Unit)? = null,
    onHealth: (() -> Unit)? = null,
    challengeSoundFactory: () -> ChallengeSoundPlayer = ::createChallengeSoundPlayer,
) {
    RemoteOrientation(onBack)
    var keyboardHints by remember { mutableStateOf(false) }
    val visionOcclusions = remember { mutableStateMapOf<String, Rect>() }
    var surfaceOrigin by remember { mutableStateOf(Offset.Zero) }
    fun reserveVisionSpace(id: String) = Modifier.onGloballyPositioned { visionOcclusions[id] = it.boundsInRoot() }
    val colors = MiuixTheme.colorScheme
    val connected by model.state.collectAsState()
    val remoteEnabled by model.remoteEnabled.collectAsState()
    val tracking by model.tracking.collectAsState()
    val vision by model.vision.collectAsState()
    val challenge by model.challenge.collectAsState()
    val gunCooling by model.gunCooling.collectAsState()
    var challengeSoundEnabled by remember { mutableStateOf(true) }
    var challengeChoosing by remember { mutableStateOf(false) }
    val health by model.robotHealth.state.collectAsState()
    val display by model.displaySettings.collectAsState()
    val displayDevice by model.robotDisplay.state.collectAsState()
    val zoom by model.cameraZoom.state.collectAsState()
    val arm by model.robotArm.state.collectAsState()
    var armMode by remember(arm.connection) { mutableStateOf(false) }
    val hasArmControls = arm.task.armOnline || arm.task.clawOnline
    val manualActionsEnabled = !tracking.blocksManualControl && !vision.blocksManualControl && !challenge.blocksControl && !challengeChoosing && !armMode
    val videoFrame = model.videoFrames.image(connected.connectedAddress)
    var videoFresh by remember { mutableStateOf(false) }
    val foreground by model.foregroundState.collectAsState()
    val enabled = remoteEnabled && inputEnabled && foreground
    val preferredGelSelected by model.gelSelected.collectAsState()
    val gelSelected = challenge.firing?.let { it == ChallengeFiring.GEL } ?: preferredGelSelected
    val gear by model.driveGear.collectAsState()
    val control by model.controlSettings.collectAsState()
    val gyroFactory = LocalAimGyroscope.current
    val gyroSource = remember(gyroFactory) { gyroFactory() }
    val aimClock = remember { TimeSource.Monotonic.markNow() }
    val aimInput = remember(model, gyroSource) { GimbalAimInput(gyroSource,
        nowMillis = { aimClock.elapsedNow().inWholeMilliseconds }, controlRevision = { model.remoteInputRevision }) }
    val aimState by aimInput.state.collectAsState()
    val inputLifecycle = LocalLifecycleOwner.current.lifecycle
    val inputLifecycleState by inputLifecycle.currentStateAsState()
    val talk by model.pushToTalk.state.collectAsState()
    var talkPresses by remember { mutableStateOf(emptySet<String>()) }
    fun talkPress(source: String, pressed: Boolean) {
        val previous = talkPresses
        talkPresses = if (pressed) previous + source else previous - source
        if (previous.isEmpty() && talkPresses.isNotEmpty()) (onPushToTalkStart ?: model::beginPushToTalk)()
        else if (previous.isNotEmpty() && talkPresses.isEmpty()) (onPushToTalkStop ?: model::endPushToTalk)()
    }
    fun releaseTalk() { talkPresses = emptySet(); (onPushToTalkStop ?: model::endPushToTalk)() }
    val mediaState by mediaControls.state.collectAsState()
    var left by remember { mutableStateOf(Offset.Zero) }
    var right by remember { mutableStateOf(Offset.Zero) }
    var pageFocused by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf(setOf<Key>()) }
    var physicalKeys by remember { mutableStateOf(setOf<Key>()) }
    var suppressedKeys by remember { mutableStateOf(setOf<Key>()) }
    var firePointerHeld by remember { mutableStateOf(false) }
    val pressureMode = LocalPressureTouchAvailable.current() && control.pressureFire
    val pressureAllowed = pressureMode && enabled && pageFocused && videoFresh && manualActionsEnabled &&
        !gunCooling.ownsCooling && !connected.busy && inputLifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val pressureInput = remember(model, pressureAllowed, control.pressureFireSensitivity) { PressureFireInput(control.pressureFireSensitivity) }
    val currentPressureAllowed by rememberUpdatedState(pressureAllowed)
    val focus = remember { FocusRequester() }
    val currentArmMode by rememberUpdatedState(armMode)
    val currentLeft by rememberUpdatedState(left)
    val currentRight by rememberUpdatedState(right)
    val currentKeys by rememberUpdatedState(keys)
    val currentGear by rememberUpdatedState(gear)
    val currentControl by rememberUpdatedState(control)
    val currentChallengeBlocked by rememberUpdatedState(challenge.blocksControl || challengeChoosing)
    val ledState = remoteLedState(enabled, mediaState.recording, talk.active)
    LaunchedEffect(connected.connected, ledState, control.remoteLeds) {
        if (connected.connected) model.setRemoteLed(control.remoteLeds.color(ledState))
    }
    DisposableEffect(model) { onDispose {
        model.cameraZoom.cancelTransition()
        aimInput.stop()
        model.robotArm.stopControl()
        model.stopFiring()
        releaseTalk()
        model.setRemoteLed(null)
        if (manageSession) { model.leaveRemote(); model.stopMedia() }
    } }
    DisposableEffect(aimInput) { onDispose { aimInput.close() } }
    DisposableEffect(pressureInput) { onDispose { pressureInput.retire() } }
    LaunchedEffect(enabled, foreground, armMode, challenge.blocksControl, challengeChoosing) {
        if (!enabled || !foreground || armMode || challenge.blocksControl || challengeChoosing) {
            suppressedKeys = suppressedKeys + physicalKeys
            model.pushToTalk.cancel()
            releaseTalk()
        }
    }
    DisposableEffect(aimInput, inputLifecycle, pressureInput, pressureMode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event in listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP)) model.cameraZoom.cancelTransition()
            if (event in listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP) && (pressureMode || aimInput.state.value.active || model.challenge.value.ownsRound)) {
                aimInput.stop()
                suppressedKeys = suppressedKeys + physicalKeys
                keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire()
                model.clearRemoteInputs()
            }
        }
        inputLifecycle.addObserver(observer)
        onDispose { inputLifecycle.removeObserver(observer) }
    }
    var appliedSensitivities by remember { mutableStateOf(control.inputSensitivities) }
    LaunchedEffect(control.inputSensitivities) {
        if (appliedSensitivities == control.inputSensitivities) return@LaunchedEffect
        appliedSensitivities = control.inputSensitivities
        suppressedKeys = suppressedKeys + physicalKeys
        keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire()
        model.clearRemoteInputs()
    }
    LaunchedEffect(control.aimInput) { aimInput.stop(); right = Offset.Zero; model.stopFiring() }
    LaunchedEffect(hasArmControls) {
        if (!hasArmControls && armMode) { armMode = false; model.clearRemoteInputs(); keys = emptySet(); left = Offset.Zero; right = Offset.Zero }
    }
    LaunchedEffect(enabled, connected.connectedAddress) {
        while (enabled) {
            videoFresh = model.videoFrames.isFresh(connected.connectedAddress)
            delay(100)
        }
        videoFresh = false
    }
    LaunchedEffect(tracking.active, enabled) {
        while (tracking.active) {
            if (!enabled || !model.videoFrames.isFresh(connected.connectedAddress)) {
                model.stopObjectTracking()
                break
            }
            delay(100)
        }
    }
    LaunchedEffect(vision.ownsPipeline, enabled) {
        while (vision.ownsPipeline) {
            if (!enabled || !model.videoFrames.isFresh(connected.connectedAddress)) {
                model.stopVision()
                break
            }
            delay(100)
        }
    }
    LaunchedEffect(vision.phase, vision.mode) {
        if (vision.mode == cn.elonzh.hanppie.robot.vision.VisionMode.ROBOT_AIM &&
            vision.phase != cn.elonzh.hanppie.robot.vision.VisionPhase.OFF) {
            keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire()
            model.stopFiring()
        }
    }
    LaunchedEffect(challenge.blocksControl, challengeChoosing) {
        if (challenge.blocksControl || challengeChoosing) {
            aimInput.stop()
            suppressedKeys = suppressedKeys + physicalKeys
            keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire(); keyboardHints = false
            model.stopFiring(); model.drive(0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }
    LaunchedEffect(gunCooling.ownsCooling) {
        if (gunCooling.ownsCooling) {
            aimInput.stop()
            suppressedKeys = suppressedKeys + physicalKeys
            keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire()
            model.stopFiring(); model.drive(0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }
    LaunchedEffect(challenge.ownsRound, enabled) {
        while (model.challenge.value.ownsRound) {
            if (!enabled || !model.videoFrames.isFresh(connected.connectedAddress)) { model.stopChallenge(); break }
            delay(100)
        }
    }
    LaunchedEffect(enabled) { if (!enabled) challengeChoosing = false }
    LaunchedEffect(enabled, videoFresh, zoom.busy) {
        if (zoom.busy && (!enabled || !videoFresh)) model.cameraZoom.cancelTransition()
    }
    LaunchedEffect(enabled, challengeChoosing, challenge.phase) {
        if (enabled && !challengeChoosing && challenge.phase != ChallengePhase.RESULT) {
            androidx.compose.runtime.withFrameNanos { }
            focus.requestFocus()
        }
    }
    LaunchedEffect(enabled) {
        if (enabled) focus.requestFocus()
        else { if (armMode) suppressedKeys = suppressedKeys + physicalKeys; keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire(); model.robotArm.stopControl(); model.stopFiring() }
    }
    LaunchedEffect(enabled) {
        while (enabled) {
            if (currentChallengeBlocked) { model.drive(0.0, 0.0, 0.0, 0.0, 0.0); delay(50); continue }
            if (currentArmMode) { model.drive(0.0, 0.0, 0.0, 0.0, 0.0); delay(50); continue }
            val shortcuts = currentControl.shortcuts
            val shiftHeld = Key.ShiftLeft in currentKeys || Key.ShiftRight in currentKeys
            fun axis(positive: ControlAction, negative: ControlAction) =
                (if (shortcuts.isHeld(positive, currentKeys, shiftHeld)) 1 else 0) -
                    (if (shortcuts.isHeld(negative, currentKeys, shiftHeld)) 1 else 0)
            val sensitivity = currentControl.inputSensitivities
            val translation = DriveSpeed.translation(
                sensitivity.combine(cn.elonzh.hanppie.ui.settings.InputAxis.FORWARD, -currentLeft.y.toDouble(), axis(ControlAction.Forward, ControlAction.Backward).toDouble()),
                sensitivity.combine(cn.elonzh.hanppie.ui.settings.InputAxis.LATERAL, currentLeft.x.toDouble(), axis(ControlAction.StrafeRight, ControlAction.StrafeLeft).toDouble()),
                currentGear, currentControl)
            val rotation = axis(ControlAction.RotateClockwise, ControlAction.RotateCounterclockwise) *
                DriveSpeed.rotation(currentGear, currentControl)
            val aim = aimInput.frame(currentControl.aimInput, currentControl.gimbalSpeed)
            val limit = currentControl.gimbalSpeed.toDouble()
            model.drive(translation.first, translation.second, rotation,
                (sensitivity.combine(cn.elonzh.hanppie.ui.settings.InputAxis.PITCH, -currentRight.y.toDouble(), axis(ControlAction.GimbalUp, ControlAction.GimbalDown).toDouble()) * limit + aim.pitch).coerceIn(-limit, limit),
                (sensitivity.combine(cn.elonzh.hanppie.ui.settings.InputAxis.YAW, currentRight.x.toDouble(), axis(ControlAction.GimbalRight, ControlAction.GimbalLeft).toDouble()) * limit + aim.yaw).coerceIn(-limit, limit))
            delay(50)
        }
    }
    val currentShiftHeld = Key.ShiftLeft in currentKeys || Key.ShiftRight in currentKeys
    val fireKeyHeld = currentControl.shortcuts.isHeld(ControlAction.Fire, currentKeys, currentShiftHeld)
    val shouldFire = enabled && manualActionsEnabled && !gunCooling.ownsCooling &&
        (fireKeyHeld || firePointerHeld || (pressureAllowed && pressureInput.isHeld(model.remoteInputRevision)))
    LaunchedEffect(shouldFire) {
        if (shouldFire) model.startFiring() else model.stopFiring()
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("remote-surface").background(if (videoContent == null) colors.background else Color.Transparent)
        .pointerInput(pressureInput) {
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (currentPressureAllowed && pageFocused && inputLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            model.isForeground && model.videoFrames.isFresh(model.state.value.connectedAddress))
                            pressureInput.update(event.changes.mapNotNull(::pressureContact), model.remoteInputRevision)
                        else pressureInput.retire()
                    }
                }
            } finally { pressureInput.retire() }
        }.pointerInput(Unit) {
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
            aimInput.stop()
            if (armMode) suppressedKeys = suppressedKeys + physicalKeys
            keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire()
            model.robotArm.stopControl()
            model.stopFiring()
            model.drive(0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }.onPreviewKeyEvent {
        val freshDown = it.type == KeyEventType.KeyDown && it.key !in physicalKeys
        val awaitingRelease = it.key in suppressedKeys
        if (it.type == KeyEventType.KeyUp) { physicalKeys = physicalKeys - it.key; suppressedKeys = suppressedKeys - it.key }
        else if (it.type == KeyEventType.KeyDown) physicalKeys = physicalKeys + it.key
        if (awaitingRelease) return@onPreviewKeyEvent true
        if (!inputEnabled || !foreground) return@onPreviewKeyEvent false
        if (it.key == Key.Escape && aimState.active) {
            if (it.type == KeyEventType.KeyDown) {
                aimInput.stop(); suppressedKeys = suppressedKeys + physicalKeys
                keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire()
                model.clearRemoteInputs(); focus.requestFocus()
            }
            return@onPreviewKeyEvent true
        }
        if (armMode) {
            if (it.key == Key.Escape) {
                if (it.type == KeyEventType.KeyDown) {
                    suppressedKeys = suppressedKeys + physicalKeys
                    model.clearRemoteInputs(); armMode = false; keys = emptySet(); focus.requestFocus()
                }
                return@onPreviewKeyEvent true
            }
            return@onPreviewKeyEvent false
        }
        val supported = control.shortcuts.supports(it.key)
        if (((challenge.blocksControl || challengeChoosing) && supported && it.key != Key.Escape) ||
            (gunCooling.ownsCooling && control.shortcuts[ControlAction.Fire].key.matches(it.key))) {
            if (it.type == KeyEventType.KeyDown) suppressedKeys = suppressedKeys + it.key
            return@onPreviewKeyEvent true
        }
        if (supported && enabled && it.type == KeyEventType.KeyDown) {
            keyboardHints = true
            left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire()
        }
        if (supported) {
            if (it.type == KeyEventType.KeyUp) {
                if (control.shortcuts[ControlAction.PushToTalk].key.matches(it.key))
                    talkPress("keyboard", false)
                keys = keys - it.key
            }
            else if (it.type == KeyEventType.KeyDown) {
                if (it.key !in keys) when (control.shortcuts.edgeAction(it.key,
                        it.isShiftPressed || Key.ShiftLeft in keys || Key.ShiftRight in keys)) {
                    ControlAction.Fire -> Unit
                    ControlAction.SwitchAmmo -> model.switchAmmo()
                    ControlAction.Photo -> if (enabled) mediaControls.takePhoto()
                    ControlAction.Recording -> if (enabled) mediaControls.toggleRecording()
                    ControlAction.PushToTalk -> if (enabled && freshDown) talkPress("keyboard", true)
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
    }.focusable().onGloballyPositioned { surfaceOrigin = it.positionInRoot() }) {
        val landscapeReady = maxWidth >= maxHeight
        LaunchedEffect(enabled, foreground, connected.connectedAddress, connected.busy, landscapeReady,
            manualActionsEnabled, vision.ownsGimbal, pageFocused) {
            if (!enabled || !foreground || connected.busy || !landscapeReady || !manualActionsEnabled || vision.ownsGimbal || !pageFocused)
                aimInput.stop()
        }
        val compactHud = maxWidth < 800.dp
        LaunchedEffect(landscapeReady) { if (!landscapeReady) { firePointerHeld = false; pressureInput.retire(); model.stopFiring(); model.haltRemote() } }
        LaunchedEffect(landscapeReady, foreground, connected.connected) {
            keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire(); model.stopFiring()
            if (landscapeReady && foreground) focus.requestFocus()
        }
        LaunchedEffect(landscapeReady, foreground, pageFocused, connected.connected, inputEnabled) {
            if (manageSession && inputEnabled && landscapeReady && foreground && pageFocused && connected.connected) {
                snapshotFlow { connected.busy }.first { !it }
                if (!model.remoteEnabled.value) model.enableRemote()
            }
        }
        if (videoContent == null) RobotVideo(model, mediaControls, Modifier.fillMaxSize()) else videoContent()
        if (tracking.active && videoFrame != null) ObjectTrackingTargets(tracking, videoFrame.width, videoFrame.height,
            enabled,
            { x, y -> model.selectTrackingTarget(x, y); focus.requestFocus() },
            { model.confirmObjectTracking(); focus.requestFocus() },
            { model.stopObjectTracking(); focus.requestFocus() }, display.fillVideo)
        if (vision.phase == cn.elonzh.hanppie.robot.vision.VisionPhase.ACTIVE && videoFresh && videoFrame != null)
            VisionTargets(vision, videoFrame.width, videoFrame.height, visionOcclusions.filterKeys {
                it !in listOf("left", "right", "ammo") || !keyboardHints && !armMode
            }.values.map { it.translate(-surfaceOrigin) }, fillVideo = display.fillVideo)
        if (!tracking.blocksManualControl && !armMode) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val viewport = VideoViewport.forImage(with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() },
                videoFrame?.width ?: 1280, videoFrame?.height ?: 720, display.fillVideo)
            val offset = viewport.sightOffset(if (challenge.ownsRound) challenge.sight else displayDevice.sight?.zoomOut, zoom.hundredths ?: 100)
            RemoteCrosshair(model, Modifier.align(Alignment.Center).offset {
                androidx.compose.ui.unit.IntOffset(offset.x.toInt(), offset.y.toInt())
            })
        }
        ChallengeFeedback(challenge, vision, videoFrame?.width ?: 1280, videoFrame?.height ?: 720,
            display.fillVideo, enabled && videoFresh, challengeSoundEnabled, challengeSoundFactory)
        Row(Modifier.align(Alignment.TopStart).padding(HanppieDesignTokens.RemoteEdgePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { back -> HudIconButton(tr(Res.string.back_to_console), WorkbenchGlyph.BACK, enabled = navigationEnabled && foreground, action = back) }
            if (connected.canStop) HudIconButton(tr(Res.string.stop_script), WorkbenchGlyph.STOP, enabled = inputEnabled && foreground, action = model::stop)
            if (connected.connected && onHealth != null && navigationEnabled && foreground)
                cn.elonzh.hanppie.ui.robot.device.RobotHealthAlertButton(model, onHealth)
            if (hasArmControls) Button({
                aimInput.stop()
                keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire(); keyboardHints = false
                suppressedKeys = suppressedKeys + physicalKeys
                model.clearRemoteInputs(); armMode = !armMode; focus.requestFocus()
            }, Modifier.heightIn(min = 48.dp).widthIn(max = 156.dp).testTag("remote-arm-toggle").semantics { selected = armMode },
                enabled = enabled && !tracking.blocksManualControl && !vision.ownsPipeline && !challenge.ownsRound && !challengeChoosing && !connected.busy,
                colors = if (armMode) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(
                    color = HanppieDesignTokens.RemoteHudSurface, contentColor = HanppieDesignTokens.RemoteHudContent)) {
                Text(tr(Res.string.arm_control_label), fontSize = 14.sp)
            }
        }
        RemoteMediaHud(mediaControls, connected.connected && foreground && navigationEnabled,
            Modifier.align(Alignment.TopEnd).padding(HanppieDesignTokens.RemoteEdgePadding))
        val leftHudWidth = (if (onBack != null) 48 else 0) + (if (connected.canStop) 56 else 0) +
            (if (connected.connected && onHealth != null && navigationEnabled && foreground && health.issues.any { it.severity >= 2 }) 80 else 0)
        val armHudWidth = if (hasArmControls) 164 else 0
        val mediaHudWidth = 216
        val hudOffset = if (compactHud) ((leftHudWidth + armHudWidth - mediaHudWidth) / 2).dp else 0.dp
        Card(Modifier.align(Alignment.TopCenter).offset(x = hudOffset)
            .widthIn(max = (maxWidth - (leftHudWidth + armHudWidth + mediaHudWidth + 48).dp).coerceAtLeast(80.dp))
            .padding(top = HanppieDesignTokens.RemoteEdgePadding)
            .testTag("remote-telemetry").then(reserveVisionSpace("telemetry")),
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
                    if (display.showGimbalAngle) HeadingIndicator(connected.gimbal?.yawDegrees, Modifier.heightIn(min = 48.dp)
                        .testTag("recenter-gimbal")
                        .clickable(enabled = enabled && manualActionsEnabled && !vision.ownsGimbal && connected.gimbal != null,
                            role = Role.Button, onClickLabel = tr(Res.string.recenter_gimbal)) {
                            model.recenterGimbal(); focus.requestFocus()
                        })
                    else HudIconButton(tr(Res.string.recenter_gimbal), WorkbenchGlyph.CROSSHAIR,
                        enabled = enabled && manualActionsEnabled && !vision.ownsGimbal && connected.gimbal != null) {
                        model.recenterGimbal(); focus.requestFocus()
                    }
                }
        }
        if (!landscapeReady) {
            Text(tr(Res.string.rotate_for_remote), Modifier.align(Alignment.Center).padding(24.dp),
                color = colors.onSurface, style = MiuixTheme.textStyles.subtitle)
            return@BoxWithConstraints
        }
        val captureMaxHeight = (maxHeight - 180.dp).coerceAtLeast(80.dp)
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .padding(top = 76.dp, start = HanppieDesignTokens.RemoteEdgePadding, end = HanppieDesignTokens.RemoteEdgePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            OnboardCaptureControls(model.robotCamera, connected.connected, inputEnabled && foreground,
                captureMaxHeight,
                Modifier.widthIn(max = HanppieDesignTokens.RemoteTaskSideMaxWidth).then(reserveVisionSpace("capture")), onAlbum = onAlbum) {
                keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire()
                model.robotArm.stopControl(); model.stopFiring(); model.drive(0.0, 0.0, 0.0, 0.0, 0.0)
                releaseTalk()
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                if (challenge.ownsRound) {
                    if (challenge.phase != ChallengePhase.RESULT) ChallengeHud(challenge,
                        Modifier.then(reserveVisionSpace("challenge")), model::stopChallenge)
                }
                else if (!armMode) VisionModeControls(vision, tracking, enabled && videoFrame != null && videoFresh && !zoom.busy && (zoom.hundredths == null || zoom.hundredths == 100),
                    Modifier.then(reserveVisionSpace("vision")),
                    onStart = { mode, color ->
                        aimInput.stop()
                        keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire()
                        model.startVision(mode, color); focus.requestFocus()
                    }, onStop = { model.stopVision(); focus.requestFocus() },
                    onStartTracking = {
                        aimInput.stop()
                        keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire()
                        model.startObjectTracking(); focus.requestFocus()
                    }, onConfirmTracking = { model.confirmObjectTracking(); focus.requestFocus() },
                    onStopTracking = { model.stopObjectTracking(); focus.requestFocus() },
                    unavailableReason = if ((zoom.hundredths ?: 100) > 100) Res.string.camera_zoom_tracking_unavailable else null,
                    onChallenge = {
                        aimInput.stop(); model.clearRemoteInputs()
                        challengeChoosing = true
                    })
            }
            if (manualActionsEnabled && !vision.ownsPipeline && !challenge.ownsRound) CameraZoomControls(zoom, enabled && videoFresh && !connected.busy,
                Modifier,
                model.cameraZoom::setZoom)
        }
        ChallengeDialogs(challengeChoosing, challenge, enabled && videoFresh && !connected.busy, challengeSoundEnabled,
            { challengeSoundEnabled = it },
            (maxHeight - 144.dp).coerceAtLeast(128.dp), { challengeChoosing = false }, model::startChallenge, model::stopChallenge,
            { model.dismissChallenge(); focus.requestFocus() })
        val touchInset = (maxWidth * .06f).coerceIn(32.dp, 80.dp)
        if (armMode) cn.elonzh.hanppie.ui.robot.arm.RobotArmControls(model.robotArm,
            enabled && !connected.busy, videoFresh,
            Modifier.align(Alignment.BottomCenter).padding(start = HanppieDesignTokens.RemoteEdgePadding, end = HanppieDesignTokens.RemoteEdgePadding, bottom = 20.dp), showPosition = display.showArmPosition)
        if (!keyboardHints && !armMode) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = touchInset, bottom = 32.dp).testTag("remote-touch-left").then(reserveVisionSpace("left")),
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
                    key(control.inputSensitivities) {
                        Stick(tr(Res.string.chassis),
                            foreground && enabled && !challenge.blocksControl && !challengeChoosing, { keyboardHints = false; focus.requestFocus() }, control.joystickDeadZone,
                            tr(Res.string.chassis)) { left = it }
                    }

                }
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(end = touchInset, bottom = 32.dp).testTag("remote-touch-right").then(reserveVisionSpace("right")),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!pressureMode) HudIconButton(if (gelSelected) tr(Res.string.fire_one_gel_bead) else tr(Res.string.fire_infrared),
                        WorkbenchGlyph.CROSSHAIR, enabled = enabled && manualActionsEnabled && !gunCooling.ownsCooling && !connected.busy, selected = firePointerHeld,
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
                    if (pressureMode) Spacer(Modifier.size(48.dp))

                }
                key(control.inputSensitivities) {
                    Stick("${tr(Res.string.gimbal)} · ${control.gimbalSpeed}°/s", foreground && enabled && !challenge.blocksControl && !challengeChoosing,
                        { aimInput.stop(); keyboardHints = false; focus.requestFocus() }, control.joystickDeadZone,
                        tr(Res.string.gimbal)) { right = it }
                }
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = touchInset + 76.dp, bottom = 180.dp).testTag("remote-ammo").then(reserveVisionSpace("ammo"))) {
                HudIconButton(tr(Res.string.switch_ammo),
                    WorkbenchGlyph.PACKETS,
                    artwork = if (gelSelected) Res.drawable.official_ammo_gel else Res.drawable.official_ammo_infrared,
                    enabled = enabled && !challenge.ownsRound && !challengeChoosing, stateLabel = if (gelSelected) tr(Res.string.gel) else tr(Res.string.ir)) {
                    model.switchAmmo(); focus.requestFocus()
                }
            }
        }
        if (keyboardHints && !armMode && !challenge.ownsRound) {
            Text(keyboardHint(control.shortcuts), Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
                .widthIn(max = 380.dp).background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = .72f),
                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("keyboard-hints"), color = HanppieDesignTokens.RemoteHudMuted, fontSize = 10.sp, maxLines = 2)
        }
        if (!armMode) Column(Modifier.align(Alignment.BottomCenter).padding(bottom = if (keyboardHints) 76.dp else 28.dp)
            .then(reserveVisionSpace("aim-input")), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GunCoolingControls(gunCooling, enabled && manualActionsEnabled && !connected.busy) {
                model.coolGun(); focus.requestFocus()
            }
            Row(Modifier.widthIn(max = 420.dp), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PushToTalkControls(model.pushToTalk, talk,
                enabled && foreground && !challenge.blocksControl && !challengeChoosing,
                onPress = { pressed ->
                    if (pressed) { keyboardHints = false; focus.requestFocus() }
                    talkPress("pointer", pressed)
                }, onToggle = {
                    focus.requestFocus()
                    if (talk.recording) releaseTalk()
                    else { talkPresses = emptySet(); talkPress("accessibility", true) }
                })
            AimInputControls(aimInput, aimState,
                enabled && manualActionsEnabled && !gunCooling.ownsCooling && !vision.ownsGimbal && !connected.busy && inputLifecycleState.isAtLeast(Lifecycle.State.RESUMED),
                Modifier.weight(1f, fill = false)) inputStart@ {
                if (!inputLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@inputStart
                model.clearRemoteInputs(); keys = emptySet(); left = Offset.Zero; right = Offset.Zero; firePointerHeld = false; pressureInput.retire()
                suppressedKeys = suppressedKeys + physicalKeys
                // Transfer focus before acquisition: the button-to-cockpit transition retires old input.
                focus.requestFocus()
                if (aimState.active) aimInput.stop() else aimInput.startGyroscope()
            }
            }
        }
        WorkbenchToast(mediaState.notice?.resolve()) { mediaControls.notice(null) }

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
        Canvas(Modifier.size(HanppieDesignTokens.RemoteStick).semantics { contentDescription = tr(Res.string.value_joystick,semanticLabel); if (!enabled) disabled() }
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
            modifier = Modifier.width(HanppieDesignTokens.RemoteStick).padding(top = 6.dp)
                .background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = .8f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp).testTag("remote-stick-caption"),
            textAlign = TextAlign.Center)
    }
}

@Composable internal fun HudIconButton(label: String, symbol: WorkbenchGlyph, enabled: Boolean = true,
    selected: Boolean = false, stateLabel: String? = null, artwork: DrawableResource? = null, modifier: Modifier = Modifier,
    onPressChange: ((Boolean) -> Unit)? = null, onAccessibilityClick: (() -> Unit)? = null, action: () -> Unit = {}) {
    val colors = MiuixTheme.colorScheme
    val surface = if (selected) colors.primary else HanppieDesignTokens.RemoteHudSurface.copy(alpha = .60f)
    val ink = when {
        symbol == WorkbenchGlyph.RECORD || symbol == WorkbenchGlyph.STOP -> Color(0xffff7165)
        selected -> colors.onPrimary
        else -> HanppieDesignTokens.RemoteHudContent
    }
    val currentOnPressChange by rememberUpdatedState(onPressChange)
    val consumePress = onAccessibilityClick != null
    val holdModifier = if (onPressChange != null) {
        Modifier.pointerInput(enabled, consumePress) {
            if (enabled) awaitEachGesture {
                val pass = if (consumePress) PointerEventPass.Initial else PointerEventPass.Main
                val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
                if (consumePress) down.consume()
                currentOnPressChange?.invoke(true)
                try {
                    while (true) {
                        val event = awaitPointerEvent(pass)
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (consumePress) pointer.consume()
                        if (!pointer.pressed) break
                    }
                } finally {
                    currentOnPressChange?.invoke(false)
                }
            }
        }
    } else Modifier
    IconButton(onClick = onAccessibilityClick ?: action, enabled = enabled, cornerRadius = 24.dp,
        modifier = modifier.size(HanppieDesignTokens.TouchTarget)
            .then(holdModifier)
            .drawBehind {
                val radius = size.minDimension / 2 - 4.dp.toPx()
                if (artwork == null) {
                    drawCircle(surface.copy(alpha = if (selected) .75f else .60f), radius)
                    drawCircle(Color.White.copy(alpha = .22f), radius,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = label; stateLabel?.let { stateDescription = it }; if (!enabled) disabled()
            }, backgroundColor = Color.Transparent) {
        if (artwork != null) Image(painterResource(artwork), null, Modifier.size(40.dp))
        else WorkbenchIcon(symbol, ink, Modifier.size(20.dp))
    }
}
