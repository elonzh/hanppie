package cn.elonzh.hanppie.ui.robot.scene

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.tr
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Filament
import io.github.erkko68.filament.compose.*
import io.github.erkko68.filament.compose.scene.*
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.*

/** The GPU surface is replaceable in layout tests; the dedicated render tests use the real engine. */
internal typealias SceneRenderer = @Composable (RobotSceneState, Float, Float, Float, Float, () -> Unit, () -> Unit) -> Unit
internal val LocalRobotSceneRenderer = staticCompositionLocalOf<SceneRenderer> {
    { state, azimuth, elevation, distance, focus, ready, error ->
        RobotSceneRenderer(state, azimuth, elevation, distance, focus, ready, error)
    }
}

internal val LocalSceneEntryProgress = staticCompositionLocalOf { 0f }

/** A read-only scene: gestures change the viewing camera, never the robot. */
@Composable
internal fun RobotScene(state: RobotSceneState, modifier: Modifier = Modifier, horizontalFocus: Float = 0f) {
    var azimuth by remember { mutableFloatStateOf(28f) }
    var elevation by remember { mutableFloatStateOf(16f) }
    var distance by remember { mutableFloatStateOf(3.1f) }
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember(attempt) { mutableStateOf(false) }
    var ready by remember(attempt, state.appearance) { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(lifecycle) {
        if (!lifecycle.isAtLeast(Lifecycle.State.STARTED)) ready = false
    }
    val description = tr(Res.string.scene_accessibility)
    val leftLabel = tr(Res.string.scene_rotate_left)
    val rightLabel = tr(Res.string.scene_rotate_right)
    val zoomInLabel = tr(Res.string.scene_zoom_in)
    val zoomOutLabel = tr(Res.string.scene_zoom_out)
    val reset: () -> Unit = { azimuth = 28f; elevation = 16f; distance = 3.1f }
    fun zoom(factor: Float) { distance = (distance * factor).coerceIn(1.6f, 4f) }
    val status = when {
        failed -> tr(Res.string.scene_unavailable)
        !ready -> tr(Res.string.scene_loading)
        else -> tr(when (state.poseStatus) {
            PoseStatus.PREVIEW -> Res.string.scene_preview
            PoseStatus.WAITING -> Res.string.scene_pose_waiting
            PoseStatus.LIVE -> Res.string.scene_pose_live
            PoseStatus.STALE -> Res.string.scene_pose_stale
            PoseStatus.STATIC -> Res.string.scene_static
        })
    }
    Box(modifier.testTag("robot-scene")) {
        Box(Modifier.fillMaxSize()
            .testTag("robot-scene-viewport")
            .semantics {
                contentDescription = description
                stateDescription = status
                customActions = listOf(
                    CustomAccessibilityAction(leftLabel) { azimuth -= 20f; true },
                    CustomAccessibilityAction(rightLabel) { azimuth += 20f; true },
                    CustomAccessibilityAction(zoomInLabel) { zoom(0.85f); true },
                    CustomAccessibilityAction(zoomOutLabel) { zoom(1.15f); true },
                )
            }
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                    Key.DirectionLeft -> { azimuth -= 10f; true }
                    Key.DirectionRight -> { azimuth += 10f; true }
                    Key.DirectionUp -> { elevation = (elevation + 5f).coerceAtMost(65f); true }
                    Key.DirectionDown -> { elevation = (elevation - 5f).coerceAtLeast(8f); true }
                    Key.Equals, Key.NumPadAdd -> { zoom(0.9f); true }
                    Key.Minus, Key.NumPadSubtract -> { zoom(1.1f); true }
                    Key.MoveHome -> { reset(); true }
                    else -> false
                }
            }.focusable()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            zoom(exp(event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat() * 0.06f))
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, scale, _ ->
                    azimuth -= pan.x * 0.35f
                    elevation = (elevation + pan.y * 0.2f).coerceIn(8f, 65f)
                    zoom(1f / scale)
                }
            }, contentAlignment = Alignment.Center) {
            if (!failed && lifecycle.isAtLeast(Lifecycle.State.STARTED)) key(attempt) {
                LocalRobotSceneRenderer.current(state, azimuth, elevation, distance, horizontalFocus,
                    { ready = true }, { failed = true })
            }
        }
        if (failed) Button({ attempt++ }, Modifier.align(Alignment.Center).padding(16.dp).heightIn(min = 48.dp)) {
            Text(tr(Res.string.scene_retry))
        }
    }
}

@Composable
private fun RobotSceneRenderer(
    state: RobotSceneState, azimuth: Float, elevation: Float, distance: Float, horizontalFocus: Float,
    onReady: () -> Unit, onError: () -> Unit,
) {
    val engineResult = remember { runCatching { Filament.init(); Engine.create() } }
    val engine = engineResult.getOrNull()
    if (engine == null) {
        LaunchedEffect(Unit) { onError() }
        return
    }
    DisposableEffect(engine) { onDispose { engine.destroy() } }
    val camera = rememberCameraState(initialTarget = Position(0f, 0.48f, 0f),
        initialProjection = Projection.Perspective(fovDegrees = 35.0, near = 0.05, far = 180.0))
    var targetYaw by remember { mutableFloatStateOf(state.yaw) }
    LaunchedEffect(state.yaw) { targetYaw = nearestYaw(targetYaw, state.yaw) }
    val yaw = animateFloatAsState(targetYaw, tween(100)).value
    val pitch = animateFloatAsState(state.pitch, tween(100)).value
    var targetChassisYaw by remember { mutableFloatStateOf(state.chassisYaw ?: 0f) }
    LaunchedEffect(state.chassisYaw) { targetChassisYaw = nearestYaw(targetChassisYaw, state.chassisYaw ?: 0f) }
    val chassisYaw = animateFloatAsState(targetChassisYaw, tween(100)).value
    val wheels = List(4) { index ->
        var target by remember { mutableFloatStateOf(state.wheelAngles?.get(index) ?: 0f) }
        LaunchedEffect(state.wheelAngles?.get(index)) { target = nearestYaw(target, state.wheelAngles?.get(index) ?: 0f) }
        animateFloatAsState(target, tween(100)).value
    }
    val entry = LocalSceneEntryProgress.current
    val cameraPose = sceneCamera(azimuth, elevation, distance, horizontalFocus, entry, yaw, pitch, chassisYaw, state.appearance)
    SideEffect {
        camera.target = cameraPose.target
        camera.eye = cameraPose.eye
        camera.projection = Projection.Perspective(fovDegrees = cameraPose.fov, near = .025, far = 180.0)
    }
    val environment = rememberKTXEnvironment(engine = engine, initialIntensity = 30_000f,
        onError = { onError() }) { Res.readBytes("files/scene/studio-ibl.ktx") }
    val workshop = rememberGltfAsset(engine = engine, onError = { onError() }) {
        Res.readBytes("files/models/workshop.glb")
    }
    var workshopReady by remember { mutableStateOf(false) }
    var modelReady by remember(state.appearance) { mutableStateOf(false) }
    LaunchedEffect(modelReady, workshopReady, environment.indirectLightState.reflections) {
        if (modelReady && workshopReady && environment.indirectLightState.reflections != null) onReady()
    }
    val scene = rememberFilamentScene(engine = engine, indirectLightState = environment.indirectLightState) {
        GltfInstance(asset = workshop, castShadows = true, onCreate = { workshopReady = true })
        DirectionalLight(direction = Direction(-0.5f, -1f, -0.6f),
            intensity = LightIntensity.LuminousPower(65_000f), shadow = ShadowConfig(mapSize = 2048, normalBias = 0.005f, shadowFar = 8f, bulbRadius = 1.5f))
        PointLight(position = Position(2f, 1.5f, 2f), color = LinearColor(.85f, .92f, 1f),
            intensity = LightIntensity.LuminousPower(12_000f), falloff = 8f)
        key(state.appearance) {
            val asset = rememberGltfAsset(engine = engine, onError = { onError() }) {
                Res.readBytes("files/models/${state.appearance.asset}.glb")
            }
            val halfHeading = -chassisYaw * PI.toFloat() / 360f
            GltfInstance(asset = asset, rotation = Rotation(0f, sin(halfHeading), 0f, cos(halfHeading)), castShadows = true, onCreate = {
                modelReady = true
            }, onUpdate = {
                if (state.appearance == RobotAppearance.TURRET) {
                    // Independent pose curves authored with the GLB. No automatic animation playback.
                    instance.animator.applyAnimation(0, ((yaw % 360f) + 360f) / 90f)
                    instance.animator.applyAnimation(1, (pitch + 180f) / 90f)
                }
                val wheelOffset = if (state.appearance == RobotAppearance.TURRET) 2 else 0
                wheels.forEachIndexed { index, angle -> instance.animator.applyAnimation(wheelOffset + index, ((angle % 360f + 360f) % 360f) / 90f) }
                instance.animator.updateBoneMatrices()
            })
        }
    }
    // Alpha-capable surfaces use TextureView on Android, preserving Compose clipping and transitions.
    FilamentView(scene = scene, cameraState = camera, modifier = Modifier.fillMaxSize(), transparent = true, screenSpaceRefractionEnabled = true, shadows = Shadows.Pcss(),
        postProcessing = PostProcessing(antiAliasing = AntiAliasing(fxaaEnabled = true, msaaEnabled = true),
            ambientOcclusion = AmbientOcclusion()))
}
