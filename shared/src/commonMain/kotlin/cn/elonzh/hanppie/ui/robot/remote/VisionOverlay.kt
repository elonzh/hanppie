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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
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
    onStart: (VisionMode, VisionColor?) -> Unit, onStop: () -> Unit, onStartTracking: () -> Unit,
    onConfirmTracking: () -> Unit, onStopTracking: () -> Unit,
    unavailableReason: org.jetbrains.compose.resources.StringResource? = null) {
    var choosing by remember { mutableStateOf(false) }
    var colorMode by remember { mutableStateOf<VisionMode?>(null) }
    LaunchedEffect(enabled, tracking.active, vision.phase) { if (!enabled || tracking.active || vision.phase != VisionPhase.OFF) choosing = false }
    if (vision.phase in listOf(VisionPhase.OFF, VisionPhase.CLOSED)) {
        Column(modifier.widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ObjectTrackingControls(tracking, enabled, Modifier, onStartTracking, onConfirmTracking, onStopTracking, unavailableReason) {
                HudTextButton(tr(Res.string.vision_tools), enabled, "vision-tools") { choosing = !choosing; colorMode = null }
            }
            if (choosing) Column(Modifier.background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = HanppieDesignTokens.RemoteHudAlpha),
                RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 4.dp).testTag("vision-picker"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                val selected = colorMode
                if (selected == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                    for (mode in VisionMode.entries) HudTextButton(visionModeTitle(mode), enabled, "vision-start-${mode.tag}") {
                        if (mode.supportsColor) colorMode = mode else { choosing = false; onStart(mode, null) }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HudTextButton(tr(Res.string.back), true, "vision-colors-back") { colorMode = null }
                        Text(tr(Res.string.vision_choose_color, visionModeTitle(selected)), color = HanppieDesignTokens.RemoteHudContent, fontSize = 14.sp)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                        HudTextButton(tr(Res.string.vision_current_color), enabled, "vision-current-color") { choosing = false; onStart(selected, null) }
                        for (color in VisionColor.entries) HudTextButton(visionColorTitle(color), enabled, "vision-color-${color.name.lowercase()}") {
                            choosing = false; onStart(selected, color)
                        }
                    }
                }
            }
        }
        return
    }
    val mode = vision.mode ?: VisionMode.MARKERS
    val title = visionModeTitle(mode)
    val status = when (vision.phase) {
        VisionPhase.CHECKING -> Res.string.vision_checking
        VisionPhase.STARTING -> Res.string.vision_starting
        VisionPhase.STOPPING -> Res.string.vision_stopping
        VisionPhase.ACTIVE -> if (vision.rectangles.isEmpty() && vision.linePoints.isEmpty()) Res.string.vision_searching else Res.string.vision_detecting
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
        Text(vision.color?.let { tr(Res.string.vision_mode_color, title, visionColorTitle(it)) } ?: title,
            Modifier.padding(top = 4.dp), color = HanppieDesignTokens.RemoteHudContent, fontSize = 14.sp)
        Text(tr(status), Modifier.testTag("vision-status").semantics { liveRegion = LiveRegionMode.Polite },
            color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp)
        if (details) Text(tr(Res.string.vision_report_details,
            vision.mask?.toString() ?: "—", vision.report?.status?.toString() ?: "—",
            vision.report?.error?.toString() ?: "—", vision.responseCode?.toString() ?: "—"),
            Modifier.testTag("vision-details"), color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp)
        if (details && mode == VisionMode.LINES) vision.report?.linePoints?.firstOrNull()?.let { point ->
            Text(tr(Res.string.vision_line_details, vision.report?.linePoints?.size.toString(), point.tangent.toString(), point.curvature.toString()),
                color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!vision.ownsPipeline) HudTextButton(tr(Res.string.vision_retry), enabled, "vision-retry") { onStart(mode, vision.color) }
            HudTextButton(tr(when {
                vision.problem == VisionProblem.STOP_UNCONFIRMED -> Res.string.vision_retry_stop
                !vision.ownsPipeline -> Res.string.close
                else -> Res.string.vision_stop
            }), vision.phase != VisionPhase.STOPPING, "vision-stop", action = onStop)
            if (vision.phase == VisionPhase.ERROR || mode == VisionMode.LINES) HudTextButton(tr(Res.string.vision_details), true, "vision-show-details") { details = !details }
        }
    }
}

/** Passive overlays: marker taps do not start the conflicting Lab PID aim path. */
@Composable
internal fun VisionTargets(state: VisionState, videoWidth: Int, videoHeight: Int, occlusions: List<Rect> = emptyList()) {
    val ink = MiuixTheme.colorScheme.primary
    val density = LocalDensity.current
    val description = if (state.mode == VisionMode.LINES) tr(Res.string.vision_line_samples, state.linePoints.size.toString())
        else tr(Res.string.vision_target_count, state.rectangles.size.toString())
    BoxWithConstraints(Modifier.fillMaxSize().testTag("vision-targets").semantics { contentDescription = description }) {
        val viewport = VideoViewport.fit(with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() }, videoWidth, videoHeight)
        Canvas(Modifier.fillMaxSize()) {
            for (rect in state.rectangles) {
                val bounds = viewport.bounds(rect)
                drawRect(ink, bounds.topLeft, bounds.size, style = Stroke(2.dp.toPx()))
            }
            clipRect(viewport.left, viewport.top, viewport.left + viewport.width, viewport.top + viewport.height) {
                for (line in state.linePoints.chunked(10)) {
                    val points = line.map { Offset(viewport.left + it.x * viewport.width, viewport.top + it.y * viewport.height) }
                    for ((a, b) in points.zipWithNext()) drawLine(ink, a, b, strokeWidth = 2.dp.toPx())
                    for (point in points) drawCircle(ink, 3.dp.toPx(), point)
                }
            }
        }
        val labels = when (state.mode) {
            VisionMode.MARKERS -> state.rectangles.map { rect -> viewport.bounds(rect) to
                tr(Res.string.vision_marker_label, markerSymbol(rect.markerId) ?: rect.markerId.toString(), rect.markerDistanceCentimetres.toString()) }
            VisionMode.POSE -> state.rectangles.map { viewport.bounds(it) to poseTitle(it.poseId) }
            VisionMode.LINES -> state.linePoints.chunked(10).map { line ->
                val point = line[line.size / 2]
                val x = viewport.left + point.x * viewport.width; val y = viewport.top + point.y * viewport.height
                Rect(x, y, x, y) to lineTitle(line.first().info and 0xffff)
            }
            else -> emptyList()
        }
        if (labels.isNotEmpty()) Layout(content = {
            labels.forEachIndexed { index, (_, text) ->
                Text(text,
                    Modifier.background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = HanppieDesignTokens.RemoteHudAlpha), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp).testTag(if (state.mode == VisionMode.MARKERS) "vision-marker-$index" else "vision-result-$index"),
                    color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp, maxLines = 2)
            }
        }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val image = Rect(viewport.left, viewport.top, viewport.left + viewport.width, viewport.top + viewport.height)
            val gap = 4.dp.toPx()
            val placed = mutableListOf<Rect>()
            val labels = measurables.mapIndexed { index, measurable ->
                val label = measurable.measure(Constraints(maxWidth = minOf(160.dp.roundToPx(), viewport.width.toInt()).coerceAtLeast(0),
                    maxHeight = viewport.height.toInt().coerceAtLeast(0)))
                val target = labels[index].first
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

private val VisionMode.tag: String get() = when (this) {
    VisionMode.ROBOT_AIM -> "aim"
    VisionMode.MARKERS -> "markers"
    else -> name.lowercase()
}

@Composable
private fun visionModeTitle(mode: VisionMode): String = tr(when (mode) {
    VisionMode.ROBOT_AIM -> Res.string.vision_robot_aim
    VisionMode.MARKERS -> Res.string.vision_markers
    VisionMode.HEAD_SHOULDERS -> Res.string.vision_head_shoulders
    VisionMode.PEOPLE -> Res.string.vision_people
    VisionMode.POSE -> Res.string.vision_pose
    VisionMode.LINES -> Res.string.vision_lines
})

@Composable
private fun visionColorTitle(color: VisionColor): String = tr(when (color) {
    VisionColor.RED -> Res.string.vision_red
    VisionColor.GREEN -> Res.string.vision_green
    VisionColor.BLUE -> Res.string.vision_blue
})

@Composable
private fun poseTitle(id: Int): String = when (id) {
    in 1..9 -> tr(listOf(Res.string.vision_pose_jump, Res.string.vision_pose_left_up, Res.string.vision_pose_right_up,
        Res.string.vision_pose_victory, Res.string.vision_pose_give_in, Res.string.vision_pose_capture,
        Res.string.vision_pose_left_wave, Res.string.vision_pose_right_wave, Res.string.vision_pose_idle)[id - 1])
    else -> tr(Res.string.vision_pose_unknown, id.toString())
}

@Composable
private fun lineTitle(id: Int): String = when (id) {
    0 -> tr(Res.string.vision_line_none)
    1 -> tr(Res.string.vision_line_single)
    2 -> tr(Res.string.vision_line_t)
    3 -> tr(Res.string.vision_line_cross)
    else -> tr(Res.string.vision_line_unknown, id.toString())
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
