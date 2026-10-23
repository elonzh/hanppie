package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.tracking.TrackingState
import cn.elonzh.hanppie.robot.vision.*
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.i18n.tr
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun VisionModeControls(vision: VisionState, tracking: TrackingState, enabled: Boolean, modifier: Modifier,
    onStart: (VisionMode) -> Unit, onStop: () -> Unit, onStartTracking: () -> Unit,
    onConfirmTracking: () -> Unit, onStopTracking: () -> Unit,
    unavailableReason: org.jetbrains.compose.resources.StringResource? = null) {
    var choosing by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, tracking.active, vision.phase) { if (!enabled || tracking.active || vision.phase != VisionPhase.OFF) choosing = false }
    if (vision.phase in listOf(VisionPhase.OFF, VisionPhase.CLOSED)) {
        Column(modifier.widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ObjectTrackingControls(tracking, enabled, Modifier, onStartTracking, onConfirmTracking, onStopTracking, unavailableReason) {
                HudTextButton(tr(Res.string.vision_tools), enabled, "vision-tools") { choosing = !choosing }
            }
            if (choosing) Row(Modifier.background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = HanppieDesignTokens.RemoteHudAlpha),
                RoundedCornerShape(12.dp)).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudTextButton(tr(Res.string.vision_robot_aim), enabled, "vision-start-aim") { choosing = false; onStart(VisionMode.ROBOT_AIM) }
                HudTextButton(tr(Res.string.vision_markers), enabled, "vision-start-markers") { choosing = false; onStart(VisionMode.MARKERS) }
            }
        }
        return
    }
    val mode = vision.mode ?: VisionMode.MARKERS
    val title = tr(if (mode == VisionMode.ROBOT_AIM) Res.string.vision_robot_aim else Res.string.vision_markers)
    val status = when (vision.phase) {
        VisionPhase.CHECKING -> Res.string.vision_checking
        VisionPhase.STARTING -> Res.string.vision_starting
        VisionPhase.STOPPING -> Res.string.vision_stopping
        VisionPhase.ACTIVE -> if (vision.rectangles.isEmpty()) Res.string.vision_searching else Res.string.vision_detecting
        else -> when (vision.problem) {
            VisionProblem.RESOURCE_BUSY -> Res.string.vision_resource_busy
            VisionProblem.REJECTED -> Res.string.vision_rejected
            VisionProblem.MODE_MISMATCH -> Res.string.vision_mode_mismatch
            VisionProblem.TARGET_LOST -> Res.string.vision_target_lost
            VisionProblem.DEVICE_ERROR -> Res.string.vision_device_error
            VisionProblem.UNSUPPORTED_REPORT -> Res.string.vision_unsupported_report
            VisionProblem.STOP_UNCONFIRMED -> Res.string.vision_stop_unconfirmed
            else -> Res.string.vision_timed_out
        }
    }
    var details by remember(vision.problem) { mutableStateOf(false) }
    Column(modifier.widthIn(max = 320.dp)
        .background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = HanppieDesignTokens.RemoteHudAlpha), RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 4.dp).testTag("vision-controls"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, Modifier.padding(top = 4.dp), color = HanppieDesignTokens.RemoteHudContent, fontSize = 14.sp)
        Text(tr(status), Modifier.testTag("vision-status").semantics { liveRegion = LiveRegionMode.Polite },
            color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp)
        if (details) Text(tr(Res.string.vision_report_details,
            vision.mask?.toString() ?: "—", vision.report?.status?.toString() ?: "—",
            vision.report?.error?.toString() ?: "—", vision.responseCode?.toString() ?: "—"),
            Modifier.testTag("vision-details"), color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!vision.ownsPipeline) HudTextButton(tr(Res.string.vision_retry), enabled, "vision-retry") { onStart(mode) }
            HudTextButton(tr(when {
                vision.problem == VisionProblem.STOP_UNCONFIRMED -> Res.string.vision_retry_stop
                !vision.ownsPipeline -> Res.string.close
                else -> Res.string.vision_stop
            }), vision.phase != VisionPhase.STOPPING, "vision-stop", action = onStop)
            if (vision.phase == VisionPhase.ERROR) HudTextButton(tr(Res.string.vision_details), true, "vision-show-details") { details = !details }
        }
    }
}

/** Passive overlays: marker taps do not start the conflicting Lab PID aim path. */
@Composable
internal fun VisionTargets(state: VisionState, videoWidth: Int, videoHeight: Int, occlusions: List<Rect> = emptyList()) {
    val ink = MiuixTheme.colorScheme.primary
    val density = LocalDensity.current
    val description = tr(Res.string.vision_target_count, state.rectangles.size.toString())
    BoxWithConstraints(Modifier.fillMaxSize().testTag("vision-targets").semantics { contentDescription = description }) {
        val viewport = VideoViewport.fit(with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() }, videoWidth, videoHeight)
        Canvas(Modifier.fillMaxSize()) {
            for (rect in state.rectangles) {
                val bounds = viewport.bounds(rect)
                drawRect(ink, bounds.topLeft, bounds.size, style = Stroke(2.dp.toPx()))
            }
        }
        if (state.mode == VisionMode.MARKERS) Layout(content = {
            state.rectangles.forEachIndexed { index, rect ->
                Text(tr(Res.string.vision_marker_label, markerSymbol(rect.markerId) ?: rect.markerId.toString(), rect.markerDistanceCentimetres.toString()),
                    Modifier.background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = HanppieDesignTokens.RemoteHudAlpha), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp).testTag("vision-marker-$index"),
                    color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp, maxLines = 2)
            }
        }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val image = Rect(viewport.left, viewport.top, viewport.left + viewport.width, viewport.top + viewport.height)
            val gap = 4.dp.toPx()
            val placed = mutableListOf<Rect>()
            val labels = measurables.mapIndexed { index, measurable ->
                val label = measurable.measure(Constraints(maxWidth = minOf(160.dp.roundToPx(), viewport.width.toInt()).coerceAtLeast(0),
                    maxHeight = viewport.height.toInt().coerceAtLeast(0)))
                val target = viewport.bounds(state.rectangles[index])
                val position = markerLabelBounds(target, label.width.toFloat(), label.height.toFloat(), image, occlusions + placed, gap, occlusions)
                    ?: markerLabelBounds(target, label.width.toFloat(), label.height.toFloat(), image, occlusions, gap)
                position?.let { placed += it }
                label to position
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                labels.forEach { (label, position) -> position?.let { label.place(it.left.roundToInt(), it.top.roundToInt()) } }
            }
        }
    }
}

/** Measured glyph bounds avoid actual task/thumb HUD geometry; detector rectangles never move. */
private fun markerLabelBounds(target: Rect, width: Float, height: Float, viewport: Rect, obstacles: List<Rect>, gap: Float,
    candidateObstacles: List<Rect> = obstacles): Rect? {
    if (width <= 0 || height <= 0 || width > viewport.width || height > viewport.height) return null
    val xs = listOf(target.left, target.right - width, viewport.left, viewport.right - width) +
        candidateObstacles.flatMap { listOf(it.left - width - gap, it.right + gap) }
    val ys = listOf(target.top, target.bottom - height, target.top - height - gap, target.bottom + gap, viewport.top, viewport.bottom - height) +
        candidateObstacles.flatMap { listOf(it.top - height - gap, it.bottom + gap) }
    return xs.flatMap { x -> ys.map { y ->
        val left = x.coerceIn(viewport.left, viewport.right - width)
        val top = y.coerceIn(viewport.top, viewport.bottom - height)
        Rect(left, top, left + width, top + height)
    } }.distinct().filter { candidate -> obstacles.none { candidate.overlaps(it) } }
        .minByOrNull { candidate ->
            val dx = candidate.center.x - target.center.x; val dy = candidate.center.y - target.center.y
            dx * dx + dy * dy
        }
}
