package cn.elonzh.hanppie.ui.robot.display

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.display.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.app.PlatformBackHandler
import cn.elonzh.hanppie.ui.robot.remote.*
import cn.elonzh.hanppie.ui.settings.SettingsDropdown
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*

/** Calibration owns the preview and a draft, never driving, shooting, or a safety-mode override. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ReticleCalibrationPage(model: ConsoleController, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ui by model.robotDisplay.state.collectAsState()
    val connection by model.state.collectAsState()
    val display by model.displaySettings.collectAsState()
    val media by model.mediaSettings.collectAsState()
    val zoom by model.cameraZoom.state.collectAsState()
    val foreground by model.foregroundState.collectAsState()
    val controls = remember { RemoteMediaController() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(model) { model.robotDisplay.refreshSight(); focus.requestFocus() }
    LaunchedEffect(ui.busy, ui.sight == null) { if (!ui.busy) focus.requestFocus() }
    val frame = model.videoFrames.image(connection.connectedAddress)
    var draft by remember(ui.connection, ui.sight) { mutableStateOf(ui.sight?.zoomOut) }
    val point = draft
    val zoomValue = zoom.hundredths?.takeIf { it == 100 || it == 400 }
    var fresh by remember(connection.connectedAddress) { mutableStateOf(false) }
    LaunchedEffect(connection.connectedAddress) { while (true) {
        fresh = model.videoFrames.isFresh(connection.connectedAddress); delay(100)
    } }
    val adjustment = point?.let { SightAdjustment(it, zoomValue ?: 100) }
    val enabled = foreground && connection.connected && !ui.busy && !zoom.busy
    fun live(): Boolean = model.videoFrames.isFresh(connection.connectedAddress) && model.cameraZoom.state.value.hundredths == zoomValue
    fun move(x: Int, y: Int) {
        if (enabled && fresh && zoomValue != null && adjustment?.adjustable == true && live()) draft = adjustment.move(x, y)
    }
    val leave = { model.robotDisplay.cancelRead(); onBack() }
    PlatformBackHandler(true, leave)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event in listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP)) model.cameraZoom.cancelTransition()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.robotDisplay.cancelRead(); model.cameraZoom.cancelTransition() }
    }
    LaunchedEffect(connection.connected) { if (!connection.connected) leave() }
    LaunchedEffect(foreground, connection.connected, fresh, zoom.busy) {
        if (zoom.busy && (!foreground || !connection.connected || !fresh)) model.cameraZoom.cancelTransition()
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black).clipToBounds().testTag("display-calibration-page")
        .focusRequester(focus).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.Escape -> { leave(); true }
                Key.W -> { move(0, 1); true }; Key.S -> { move(0, -1); true }
                Key.A -> { move(-1, 0); true }; Key.D -> { move(1, 0); true }
                else -> false
            }
        }.focusable()) {
        CompositionLocalProvider(LocalVideoPreview provides true, LocalVideoInputEnabled provides false,
            LocalVideoStreamEnabled provides foreground) { RobotVideo(model, controls, Modifier.fillMaxSize()) }
        val density = LocalDensity.current
        val viewport = VideoViewport.forImage(with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() },
            frame?.width ?: media.videoResolution.width, frame?.height ?: media.videoResolution.height, display.fillVideo)
        val offset = viewport.sightOffset(point, zoomValue ?: 100)
        StaticReticle(display.reticle, Modifier.align(Alignment.Center).offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }
            .testTag("display-calibration-reticle"))

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().testTag("display-calibration-header").background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = .86f))
            .padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                HudIconButton(tr(Res.string.display_sight_exit), WorkbenchGlyph.BACK, action = leave)
                Text(tr(Res.string.display_calibrate), color = HanppieDesignTokens.RemoteHudContent, fontSize = 18.sp)
                if (point != null) {
                    Button({ draft = SightPoint() }, Modifier.heightIn(min = 48.dp).testTag("display-sight-zero"), enabled) { Text(tr(Res.string.display_sight_zero)) }
                    Button({ if (live()) model.robotDisplay.setSight(SightCalibration(point, point)) },
                        Modifier.heightIn(min = 48.dp).testTag("display-sight-apply"),
                        enabled && fresh && zoomValue != null && adjustment?.adjustable == true && ui.sight != SightCalibration(point, point),
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.display_sight_apply)) }
                }
                if (!ui.busy) Button(model.robotDisplay::refreshSight, Modifier.heightIn(min = 48.dp).testTag("display-sight-read"), enabled) {
                    Text(tr(Res.string.display_sight_read))
                }
                if (ui.busy && !ui.writing) Button(model.robotDisplay::cancelRead, Modifier.heightIn(min = 48.dp)) { Text(tr(Res.string.cancel)) }
            }
            val message = ui.error?.resolve() ?: zoom.error?.resolve() ?: when {
                ui.busy -> tr(if (ui.writing) Res.string.display_writing else Res.string.display_reading)
                !fresh || zoomValue == null -> tr(Res.string.display_sight_video_wait)
                adjustment?.adjustable == false -> tr(Res.string.display_sight_range)
                ui.sight?.let { it.zoomIn != it.zoomOut } == true -> tr(Res.string.display_sight_pairs_differ)
                ui.confirmed == DisplayTarget.SIGHT && point != null && ui.sight == SightCalibration(point, point) -> tr(Res.string.display_confirmed)
                else -> tr(Res.string.display_sight_help)
            }
            Text(message, Modifier.testTag("display-calibration-status"), color = HanppieDesignTokens.RemoteHudContent, fontSize = 13.sp)
        }
        FlowRow(Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("display-calibration-adjustments")
            .background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = .86f)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            if (adjustment != null) {
                listOf(Triple(Res.string.display_sight_left, -1, 0), Triple(Res.string.display_sight_right, 1, 0),
                    Triple(Res.string.display_sight_up, 0, 1), Triple(Res.string.display_sight_down, 0, -1)).forEach { (label, x, y) ->
                    val glyph = when {
                        x < 0 -> WorkbenchGlyph.ARROW_LEFT
                        x > 0 -> WorkbenchGlyph.ARROW_RIGHT
                        y > 0 -> WorkbenchGlyph.ARROW_UP
                        else -> WorkbenchGlyph.ARROW_DOWN
                    }
                    WorkbenchIconButton(tr(label), glyph, { move(x, y) },
                        enabled = enabled && adjustment.adjustable && zoomValue != null && fresh,
                        tag = "display-sight-step-$x-$y")
                }
                Text(tr(Res.string.display_sight_offset, adjustment.xSteps, adjustment.ySteps), color = HanppieDesignTokens.RemoteHudContent, fontSize = 13.sp)
            }
            Box(Modifier.widthIn(max = 260.dp)) {
                SettingsDropdown(tr(Res.string.display_picture_zoom), "display-calibration-zoom", (zoomValue ?: -1).toString(),
                    (if (zoomValue == null) listOf("-1" to tr(Res.string.display_zoom_unknown)) else emptyList()) +
                        listOf("100" to tr(Res.string.display_sight_normal), "400" to tr(Res.string.display_sight_zoom)), enabled) {
                    if (it != "-1") model.cameraZoom.setZoom(it.toInt())
                }
            }
        }
    }
}
