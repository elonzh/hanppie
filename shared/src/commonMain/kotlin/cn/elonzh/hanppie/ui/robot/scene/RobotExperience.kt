package cn.elonzh.hanppie.ui.robot.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.app.ConsoleState
import cn.elonzh.hanppie.ui.app.PlatformBackHandler
import cn.elonzh.hanppie.ui.robot.device.DevicePage
import cn.elonzh.hanppie.ui.robot.remote.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/** Scene, decoder and cockpit keep their identities for the whole round trip. */
@Composable
internal fun RobotExperience(
    model: ConsoleController, state: ConsoleState, compact: Boolean, modifier: Modifier,
    onConnectionDetails: () -> Unit,
    onNavigate: (Int) -> Unit, onCockpitChanged: (Boolean) -> Unit,
    onPushToTalkStart: (() -> Unit)? = null, onPushToTalkStop: (() -> Unit)? = null,
    video: @Composable (RemoteMediaController) -> Unit = { RobotVideo(model, it, Modifier.fillMaxSize()) },
) {
    var requested by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(false) }
    var preparationFailed by remember { mutableStateOf(false) }
    var interactive by remember { mutableStateOf(false) }
    val travel = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    val hud = remember { Animatable(0f) }
    val media = remember { RemoteMediaController() }
    val foreground by model.foregroundState.collectAsState()
    val ready by media.state.collectAsState()
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { while (true) { now = Clock.System.now().toEpochMilliseconds(); delay(100) } }
    val mediaStarted = state.connected && foreground && !state.scriptRunPhase.mayBeExecuting
    val currentReady by rememberUpdatedState(ready.videoReady && now - ready.videoFrameAtEpochMillis <= 500)
    val exit: () -> Unit = {
        interactive = false
        model.haltRemote()
        requested = false
    }
    PlatformBackHandler(active, exit)
    DisposableEffect(model) {
        onDispose { if (active) { model.leaveRemote(); model.stopMedia(); onCockpitChanged(false) } }
    }
    LaunchedEffect(requested, state.connected, foreground) {
        if (requested && state.connected && foreground) {
            active = true
            preparationFailed = false
            // The homepage already decodes live video; only the explicit action enables control.
            model.enableRemote()
            val remoteReady = withTimeoutOrNull(5_000) { model.remoteEnabled.first { it } } != null
            if (!remoteReady) { preparationFailed = true; requested = false; return@LaunchedEffect }
            val videoReady = withTimeoutOrNull(5_000) { snapshotFlow { currentReady }.first { it } } != null
            if (!videoReady) { preparationFailed = true; requested = false; return@LaunchedEffect }
            onCockpitChanged(true)
            coroutineScope {
                launch { travel.animateTo(1f, tween(1_350)) }
                launch { delay(1_000); reveal.animateTo(1f, tween(600)) }
            }
            hud.animateTo(1f, tween(400))
            interactive = true
        } else if (active) {
            requested = false
            interactive = false
            model.haltRemote()
            coroutineScope {
                launch { hud.animateTo(0f, tween(220)) }
                launch { travel.animateTo(0f, tween(1_150)) }
                launch {
                    reveal.animateTo(0f, tween(600))
                    model.leaveRemote()
                }
            }
            active = false
            onCockpitChanged(false)
        }
    }
    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSceneEntryProgress provides travel.value) {
            RobotScene(RobotSceneState.from(state, now), Modifier.fillMaxSize(), horizontalFocus = 0f)
        }
        if (mediaStarted) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value }) {
                CompositionLocalProvider(LocalVideoHudAlpha provides hud.value, LocalVideoPreview provides !active) { video(media) }
            }
        }
        if (active) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = hud.value }) {
                RemotePage(model, onBack = exit, onPushToTalkStart = onPushToTalkStart,
                    onPushToTalkStop = onPushToTalkStop, mediaControls = media, videoContent = {},
                    inputEnabled = interactive, manageSession = false)
            }
        }
        if (travel.value < 1f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - travel.value * 3f).coerceIn(0f, 1f) }) {
                DevicePage(model, state, compact, Modifier.fillMaxSize(),
                    { if (!active && state.connected) requested = true }, onConnectionDetails, showScene = false, preparing = active && travel.value == 0f,
                    preparationFailed = preparationFailed, onNavigate = onNavigate)
            }
        }
        if (active && !interactive) {
            Box(Modifier.fillMaxSize().testTag("scene-entry-transition").pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            })

        }
    }
}
