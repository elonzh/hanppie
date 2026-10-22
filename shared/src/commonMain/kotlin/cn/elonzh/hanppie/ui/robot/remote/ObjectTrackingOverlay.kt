package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.tracking.TrackingPhase
import cn.elonzh.hanppie.robot.tracking.TrackingProblem
import cn.elonzh.hanppie.robot.tracking.TrackingState
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Same Fit transform as the video renderers. Letterbox taps never select a target. */
internal data class TrackingViewport(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun point(x: Float, y: Float): Pair<Float, Float>? =
        if (width > 0 && height > 0 && x in left..left + width && y in top..top + height)
            (x - left) / width to (y - top) / height else null

    companion object {
        fun fit(width: Float, height: Float, videoWidth: Int, videoHeight: Int): TrackingViewport {
            require(videoWidth > 0 && videoHeight > 0)
            val scale = minOf(width / videoWidth, height / videoHeight)
            val w = videoWidth * scale; val h = videoHeight * scale
            return TrackingViewport((width - w) / 2, (height - h) / 2, w, h)
        }
    }
}

@Composable
internal fun ObjectTrackingTargets(state: TrackingState, videoWidth: Int, videoHeight: Int,
    enabled: Boolean, onSelect: (Float, Float) -> Unit) {
    val ink = MiuixTheme.colorScheme.primary
    val canSelect = enabled && state.phase in listOf(TrackingPhase.SEARCHING, TrackingPhase.TARGET_READY)
    val description = tr(Res.string.tracking_select_target)
    Canvas(Modifier.fillMaxSize().testTag("tracking-targets")
        .semantics { contentDescription = description }
        .pointerInput(canSelect, videoWidth, videoHeight) {
            if (canSelect) detectTapGestures { offset ->
                TrackingViewport.fit(size.width.toFloat(), size.height.toFloat(), videoWidth, videoHeight)
                    .point(offset.x, offset.y)?.let { onSelect(it.first, it.second) }
            }
        }) {
        val viewport = TrackingViewport.fit(size.width, size.height, videoWidth, videoHeight)
        val rects = if (state.rectangles.isNotEmpty()) state.rectangles else state.candidates
        for (rect in rects) {
            val left = (viewport.left + (rect.x - rect.width / 2) * viewport.width).coerceIn(viewport.left, viewport.left + viewport.width)
            val top = (viewport.top + (rect.y - rect.height / 2) * viewport.height).coerceIn(viewport.top, viewport.top + viewport.height)
            val right = (viewport.left + (rect.x + rect.width / 2) * viewport.width).coerceIn(left, viewport.left + viewport.width)
            val bottom = (viewport.top + (rect.y + rect.height / 2) * viewport.height).coerceIn(top, viewport.top + viewport.height)
            drawRect(ink, Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
internal fun ObjectTrackingControls(state: TrackingState, enabled: Boolean, modifier: Modifier,
    onStart: () -> Unit, onConfirm: () -> Unit, onStop: () -> Unit) {
    val phase = state.phase
    val status = when (phase) {
        TrackingPhase.OFF -> null
        TrackingPhase.STARTING -> Res.string.tracking_preparing
        TrackingPhase.SEARCHING -> Res.string.tracking_select_target
        TrackingPhase.SELECTING -> Res.string.tracking_selecting
        TrackingPhase.TARGET_READY -> Res.string.tracking_ready
        TrackingPhase.STARTING_FOLLOW -> Res.string.tracking_starting
        TrackingPhase.FOLLOWING -> Res.string.tracking_following
        TrackingPhase.STOPPING -> Res.string.tracking_stopping
        TrackingPhase.ERROR -> when (state.problem) {
            TrackingProblem.STOP_UNCONFIRMED -> Res.string.tracking_stop_unconfirmed
            TrackingProblem.TARGET_LOST -> Res.string.tracking_target_lost
            TrackingProblem.REJECTED -> Res.string.tracking_rejected
            else -> Res.string.tracking_timeout
        }
    }
    Column(modifier.widthIn(max = 360.dp)
        .background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = HanppieDesignTokens.RemoteHudAlpha), RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 4.dp).testTag("tracking-controls"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        status?.let {
            Text(tr(it), Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!state.blocksManualControl) TrackingAction(tr(Res.string.object_tracking), enabled, "tracking-start", onStart)
            if (phase == TrackingPhase.TARGET_READY) TrackingAction(tr(Res.string.tracking_start_follow), enabled, "tracking-confirm", onConfirm)
            if (state.blocksManualControl) TrackingAction(tr(Res.string.tracking_stop),
                phase != TrackingPhase.STOPPING, "tracking-stop", onStop)
        }
    }
}

@Composable
private fun TrackingAction(label: String, enabled: Boolean, tag: String, action: () -> Unit) {
    Box(Modifier.heightIn(min = HanppieDesignTokens.TouchTarget).testTag(tag)
        .clickable(enabled = enabled, role = Role.Button, onClick = action).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) HanppieDesignTokens.RemoteHudContent else HanppieDesignTokens.RemoteHudMuted,
            fontSize = 14.sp)
    }
}
