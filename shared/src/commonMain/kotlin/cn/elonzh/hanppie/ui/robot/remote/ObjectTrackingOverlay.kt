package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.tracking.TrackingPhase
import cn.elonzh.hanppie.robot.tracking.TrackingProblem
import cn.elonzh.hanppie.robot.tracking.TrackingState
import cn.elonzh.hanppie.robot.vision.VisionRect
import cn.elonzh.hanppie.robot.tracking.kind
import cn.elonzh.hanppie.robot.tracking.TrackingTargetKind
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private fun VisionRect.actionable(phase: TrackingPhase): Boolean = when (kind) {
    TrackingTargetKind.CANDIDATE -> phase in listOf(TrackingPhase.SEARCHING, TrackingPhase.TARGET_READY)
    TrackingTargetKind.READY -> phase == TrackingPhase.TARGET_READY
    TrackingTargetKind.FOLLOWING -> phase == TrackingPhase.FOLLOWING
    else -> false
}

@Composable
internal fun ObjectTrackingTargets(state: TrackingState, videoWidth: Int, videoHeight: Int,
    enabled: Boolean, onSelect: (Float, Float) -> Unit, onConfirm: () -> Unit, onStop: () -> Unit, fillVideo: Boolean = false) {
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val view = VideoViewport.forImage(measured.width.toFloat(), measured.height.toFloat(), videoWidth, videoHeight, fillVideo)
    val ink = MiuixTheme.colorScheme.primary
    val muted = HanppieDesignTokens.RemoteHudMuted
    val current by rememberUpdatedState(state)
    val activate: (VisionRect) -> Unit = { rect ->
        if (enabled && rect.actionable(state.phase)) when (rect.kind) {
            TrackingTargetKind.CANDIDATE -> onSelect(rect.x, rect.y)
            TrackingTargetKind.READY -> onConfirm()
            TrackingTargetKind.FOLLOWING -> onStop()
            else -> Unit
        }
    }
    val currentActivate by rememberUpdatedState(activate)
    val description = tr(Res.string.tracking_target_frames)
    val actions = if (!enabled) emptyList() else state.targets.mapIndexedNotNull { index, rect ->
        val bounds = view.bounds(rect, rect.kind == TrackingTargetKind.FOLLOWING)
        if (!rect.actionable(state.phase) || bounds.width <= 0 || bounds.height <= 0) null else CustomAccessibilityAction(when (rect.kind) {
            TrackingTargetKind.CANDIDATE -> tr(Res.string.tracking_candidate_action, (index + 1).toString())
            TrackingTargetKind.READY -> tr(Res.string.tracking_start_follow)
            else -> tr(Res.string.tracking_stop)
        }) { activate(rect); true }
    }
    Canvas(Modifier.fillMaxSize().onSizeChanged { measured = it }.testTag("tracking-targets")
        .semantics { contentDescription = description; customActions = actions }
        .pointerInput(enabled, videoWidth, videoHeight, fillVideo) {
            if (enabled) detectTapGestures { offset ->
                val viewport = VideoViewport.forImage(size.width.toFloat(), size.height.toFloat(), videoWidth, videoHeight, fillVideo)
                if (viewport.point(offset.x, offset.y) != null) current.targets.asReversed().firstOrNull {
                    it.actionable(current.phase) && viewport.bounds(it, it.kind == TrackingTargetKind.FOLLOWING).contains(offset)
                }?.let(currentActivate)
            }
        }) {
        val viewport = VideoViewport.forImage(size.width, size.height, videoWidth, videoHeight, fillVideo)
        for (rect in state.targets) {
            val bounds = viewport.bounds(rect, rect.kind == TrackingTargetKind.FOLLOWING)
            val candidate = rect.kind == TrackingTargetKind.CANDIDATE
            val known = rect.kind in listOf(TrackingTargetKind.CANDIDATE, TrackingTargetKind.READY, TrackingTargetKind.FOLLOWING)
            drawRect(if (known) ink else muted, bounds.topLeft, bounds.size,
                style = Stroke((if (candidate) 2.dp else 3.dp).toPx(),
                    pathEffect = if (candidate) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())) else null))
        }
    }
}

@Composable
internal fun ObjectTrackingControls(state: TrackingState, enabled: Boolean, modifier: Modifier,
    onStart: () -> Unit, onConfirm: () -> Unit, onStop: () -> Unit,
    unavailableReason: org.jetbrains.compose.resources.StringResource? = null,
    additionalActions: @Composable RowScope.() -> Unit = {}) {
    val phase = state.phase
    val status = when (phase) {
        TrackingPhase.OFF -> unavailableReason
        TrackingPhase.STARTING -> Res.string.tracking_preparing
        TrackingPhase.SEARCHING -> if (state.candidates.none { it.kind == TrackingTargetKind.CANDIDATE })
            Res.string.tracking_searching else Res.string.tracking_select_target
        TrackingPhase.SELECTING -> Res.string.tracking_selecting
        TrackingPhase.TARGET_READY -> Res.string.tracking_ready
        TrackingPhase.STARTING_FOLLOW -> Res.string.tracking_starting
        TrackingPhase.FOLLOWING -> Res.string.tracking_following
        TrackingPhase.STOPPING -> Res.string.tracking_stopping
        TrackingPhase.ERROR -> when (state.problem) {
            TrackingProblem.STOP_UNCONFIRMED -> Res.string.tracking_stop_unconfirmed
            TrackingProblem.TARGET_LOST -> Res.string.tracking_target_lost
            TrackingProblem.REJECTED -> Res.string.tracking_rejected
            TrackingProblem.DEVICE_ERROR -> Res.string.tracking_device_error
            TrackingProblem.UNSUPPORTED_REPORT -> Res.string.tracking_unknown_report
            else -> Res.string.tracking_timeout
        }
    }
    Column(modifier
        .background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = HanppieDesignTokens.RemoteHudAlpha), RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 4.dp).testTag("tracking-controls"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        status?.let {
            Text(tr(it), Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp)
        }
        val report = state.report
        val detail = when {
            state.responseCode != null -> tr(Res.string.tracking_response_code, state.rejectedCommand?.toString(16) ?: "—", state.responseCode.toString())
            report != null && report.error != 0 -> tr(Res.string.tracking_report_error_code, report.error.toString(), report.type.toString())
            state.problem == TrackingProblem.UNSUPPORTED_REPORT && report != null -> tr(Res.string.tracking_report_unknown,
                report.status.toString(), report.rectangles.map { it.info }.distinct().joinToString(limit = 3).ifEmpty { "—" })
            else -> null
        }
        detail?.let { Text(it, Modifier.testTag("tracking-report-detail"), color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp) }
        Row(Modifier.width(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (!state.blocksManualControl) HudTextButton(tr(if (phase == TrackingPhase.ERROR) Res.string.tracking_retry else Res.string.object_tracking), enabled, "tracking-start", modifier = Modifier.weight(1f), action = onStart)
            if (!state.blocksManualControl) additionalActions()
            if (phase == TrackingPhase.TARGET_READY) HudTextButton(tr(Res.string.tracking_start_follow), enabled, "tracking-confirm", modifier = Modifier.weight(1f), action = onConfirm)
            if (state.blocksManualControl) HudTextButton(tr(Res.string.tracking_stop),
                phase != TrackingPhase.STOPPING, "tracking-stop", modifier = Modifier.weight(1f), action = onStop)
        }
    }
}

@Composable
internal fun HudTextButton(label: String, enabled: Boolean, tag: String, description: String = label,
    modifier: Modifier = Modifier, action: () -> Unit) {
    Box(modifier.heightIn(min = HanppieDesignTokens.TouchTarget).testTag(tag)
        .semantics { contentDescription = description }
        .clickable(enabled = enabled, role = Role.Button, onClick = action).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) HanppieDesignTokens.RemoteHudContent else HanppieDesignTokens.RemoteHudMuted,
            fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}
