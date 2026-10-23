package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.challenge.*
import cn.elonzh.hanppie.robot.vision.*
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.i18n.tr
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Passive host-rule feedback, using the same video transform as detection and the reticle. */
@Composable
internal fun ChallengeFeedback(state: ChallengeState, vision: VisionState, videoWidth: Int, videoHeight: Int,
    fillVideo: Boolean, active: Boolean, sounds: Boolean, playerFactory: () -> ChallengeSoundPlayer) {
    if (!active || !state.ownsRound) return
    if (sounds) ChallengeAudio(state, playerFactory)
    if (state.phase == ChallengePhase.RESULT) return
    val hit = state.hit?.takeIf { state.hitAgeMillis?.let { age -> age in 0..999 } == true }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(hit?.sequence) {
        if (hit != null) {
            progress.snapTo((state.hitAgeMillis ?: 0).toFloat() / 1000)
            // Animatable honors the platform duration scale. Static hit/text remains when motion is disabled.
            progress.animateTo(1f, tween((1000 - (state.hitAgeMillis ?: 0)).toInt(), easing = LinearOutSlowInEasing))
        }
    }
    val density = LocalDensity.current
    val ink = MiuixTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxSize().testTag("challenge-feedback")) {
        val viewport = VideoViewport.forImage(with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() },
            videoWidth, videoHeight, fillVideo)
        val aimed = vision.rectangles.firstOrNull { it.markerId == state.aimedMarkerId }
        val aimDescription = aimed?.let { tr(Res.string.challenge_aimed, markerSymbol(it.markerId) ?: it.markerId.toString()) }
        Canvas(Modifier.fillMaxSize().then(if (aimDescription != null) Modifier.testTag("challenge-aimed")
            .semantics { contentDescription = aimDescription } else Modifier)) {
            clipRect(viewport.visibleLeft, viewport.visibleTop, viewport.visibleRight, viewport.visibleBottom) {
                if (aimed != null) {
                    val bounds = viewport.bounds(aimed)
                    drawRect(Color.Black, bounds.topLeft, bounds.size, style = Stroke(5.dp.toPx()))
                    drawRect(ink, bounds.topLeft, bounds.size, style = Stroke(3.dp.toPx()))
                }
                if (hit != null) {
                    val offset = viewport.sightOffset(state.sight, 100)
                    val rectangle = hit.rectangle
                    val point = if (state.firing == ChallengeFiring.GEL && rectangle != null)
                        viewport.bounds(rectangle).center else center + offset
                    if ((state.hitAgeMillis ?: 1000) < 500) {
                        val arm = 6.dp.toPx(); val gap = 3.dp.toPx()
                        for (x in listOf(-1, 1)) for (y in listOf(-1, 1)) {
                            val a = point + Offset(x * gap, y * gap); val b = point + Offset(x * (gap + arm), y * (gap + arm))
                            drawLine(Color.Black, a, b, 5.dp.toPx()); drawLine(ink, a, b, 2.dp.toPx())
                        }
                    }
                    hit.rectangle?.let { rectangle ->
                        val bounds = viewport.bounds(rectangle)
                        val p = progress.value
                        repeat(8) { index ->
                            val angle = index * PI / 4
                            val distance = (12.dp.toPx() + minOf(bounds.width, bounds.height) * .6f) * p
                            val location = bounds.center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * distance
                            drawCircle(ink.copy(alpha = (1f - p).coerceIn(0f, 1f)), (3.dp.toPx() * (1f - p)).coerceAtLeast(.1f), location)
                        }
                    }
                }
            }
        }
        if (hit != null && (state.hitAgeMillis ?: 1000) < 750) {
            val message = tr(when {
                !hit.credited -> Res.string.challenge_hit_unscored
                hit.scoreDelta > 0 -> Res.string.challenge_hit_points
                else -> Res.string.challenge_hit_completed
            }, markerSymbol(hit.markerId) ?: hit.markerId.toString())
            Text(message, Modifier.align(Alignment.Center).padding(top = 96.dp).widthIn(max = 380.dp)
                .background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = .92f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp).testTag("challenge-hit")
                .semantics { liveRegion = LiveRegionMode.Polite }, color = HanppieDesignTokens.RemoteHudContent, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ChallengeAudio(state: ChallengeState, factory: () -> ChallengeSoundPlayer) {
    val latest by rememberUpdatedState(state)
    LaunchedEffect(factory) {
        val tracker = ChallengeCueTracker(latest)
        var player: ChallengeSoundPlayer? = null
        try {
            // Capture ownership inside the worker, so cancellation during initialization still closes it.
            withContext(Dispatchers.Default) { player = factory() }
            snapshotFlow { latest }.collect { current -> tracker.update(current)?.let { player?.play(it) } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { KotlinLogging.logger {}.warn(error) { "Challenge feedback audio unavailable" } }
        finally { runCatching { player?.close() } }
    }
}
