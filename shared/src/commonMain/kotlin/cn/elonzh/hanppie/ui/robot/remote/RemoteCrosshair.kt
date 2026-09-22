package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.ui.app.AmmoType
import cn.elonzh.hanppie.ui.app.ConsoleController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.pow

private val InfraredPulseColor = Color(0xff00d2ff)
private val InfraredFlashColor = Color(0xff80d8ff)
private val GelPulseColor = Color(0xffff6d00)
private val GelFlashColor = Color(0xffffb74d)

@Composable
internal fun RemoteCrosshair(
    model: ConsoleController,
    modifier: Modifier = Modifier,
) {
    val firing by model.firing.collectAsState()
    val gelSelected by model.gelSelected.collectAsState()

    val recoil = remember { Animatable(0f) }
    val shockwave = remember { Animatable(1f) }
    val flash = remember { Animatable(0f) }
    var lastFiredAmmo by remember { mutableStateOf(AmmoType.INFRARED) }

    LaunchedEffect(model) {
        model.fireEvents.collectLatest { ammo ->
            lastFiredAmmo = ammo
            coroutineScope {
                launch {
                    val kickIntensity = if (ammo == AmmoType.GEL) 1.35f else 1.0f
                    recoil.snapTo(kickIntensity)
                    recoil.animateTo(
                        0f,
                        animationSpec = tween(
                            durationMillis = if (ammo == AmmoType.GEL) 220 else 160,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                }
                launch {
                    shockwave.snapTo(0f)
                    shockwave.animateTo(
                        1f,
                        animationSpec = tween(
                            durationMillis = if (ammo == AmmoType.GEL) 260 else 200,
                            easing = LinearOutSlowInEasing,
                        ),
                    )
                }
                launch {
                    flash.snapTo(1f)
                    flash.animateTo(
                        0f,
                        animationSpec = tween(durationMillis = 110, easing = FastOutLinearInEasing),
                    )
                }
            }
        }
    }

    val sustainedSpread by animateFloatAsState(
        targetValue = if (firing) 1f else 0f,
        animationSpec = tween(120),
    )

    Canvas(modifier.size(64.dp).testTag("remote-crosshair")) {
        drawCrosshair(
            recoil = recoil.value,
            sustainedSpread = sustainedSpread,
            shockwaveProgress = shockwave.value,
            flashProgress = flash.value,
            lastFiredAmmo = lastFiredAmmo,
            gelSelected = gelSelected,
            isFiring = firing,
        )
    }
}

private fun DrawScope.drawCrosshair(
    recoil: Float,
    sustainedSpread: Float,
    shockwaveProgress: Float,
    flashProgress: Float,
    lastFiredAmmo: AmmoType,
    gelSelected: Boolean,
    isFiring: Boolean,
) {
    val c = center

    // 1. Shockwave energy ring expanding outward
    if (shockwaveProgress < 1f) {
        val ringRadius = 7.dp.toPx() + shockwaveProgress * 21.dp.toPx()
        val ringAlpha = ((1f - shockwaveProgress).pow(1.6f) * 0.85f).coerceIn(0f, 1f)
        val ringStroke = (2.2.dp.toPx() * (1f - shockwaveProgress * 0.6f)).coerceAtLeast(1f)
        val ringColor = if (lastFiredAmmo == AmmoType.GEL) GelPulseColor else InfraredPulseColor

        drawCircle(
            color = Color.Black.copy(alpha = ringAlpha * 0.45f),
            radius = ringRadius,
            center = c,
            style = Stroke(width = ringStroke + 1.5.dp.toPx()),
        )
        drawCircle(
            color = ringColor.copy(alpha = ringAlpha),
            radius = ringRadius,
            center = c,
            style = Stroke(width = ringStroke),
        )
    }

    // 2. Tactical corner brackets framing the aiming zone
    val bracketCorner = 18.dp.toPx() + recoil * 2.5.dp.toPx()
    val arm = 3.5.dp.toPx()
    val bracketAlpha = if (isFiring) 0.70f else if (recoil > 0.05f) 0.55f else 0.32f
    val bracketThemeColor = if (gelSelected) Color(0xffffd0a8) else Color(0xffd0f2ff)

    val bracketPaths = listOf(
        // Top-Left
        Path().apply {
            moveTo(c.x - bracketCorner, c.y - bracketCorner + arm)
            lineTo(c.x - bracketCorner, c.y - bracketCorner)
            lineTo(c.x - bracketCorner + arm, c.y - bracketCorner)
        },
        // Top-Right
        Path().apply {
            moveTo(c.x + bracketCorner - arm, c.y - bracketCorner)
            lineTo(c.x + bracketCorner, c.y - bracketCorner)
            lineTo(c.x + bracketCorner, c.y - bracketCorner + arm)
        },
        // Bottom-Left
        Path().apply {
            moveTo(c.x - bracketCorner, c.y + bracketCorner - arm)
            lineTo(c.x - bracketCorner, c.y + bracketCorner)
            lineTo(c.x - bracketCorner + arm, c.y + bracketCorner)
        },
        // Bottom-Right
        Path().apply {
            moveTo(c.x + bracketCorner - arm, c.y + bracketCorner)
            lineTo(c.x + bracketCorner, c.y + bracketCorner)
            lineTo(c.x + bracketCorner, c.y + bracketCorner - arm)
        },
    )

    bracketPaths.forEach { p ->
        drawPath(
            p,
            Color.Black.copy(alpha = bracketAlpha * 0.6f),
            style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Square)
        )
        drawPath(
            p,
            bracketThemeColor.copy(alpha = bracketAlpha),
            style = Stroke(1.dp.toPx(), cap = StrokeCap.Square)
        )
    }

    // 3. Four crosshair tick lines (with recoil kick and sustained spread expansion)
    val baseGap = 5.dp.toPx()
    val spreadAddition = sustainedSpread * 3.5.dp.toPx()
    val kickAddition =
        recoil * (if (lastFiredAmmo == AmmoType.GEL) 6.5.dp.toPx() else 4.5.dp.toPx())
    val currentGap = baseGap + spreadAddition + kickAddition
    val barLength = 5.5.dp.toPx()
    val currentEdge = currentGap + barLength

    val segments = listOf(
        Offset(c.x - currentEdge, c.y) to Offset(c.x - currentGap, c.y),
        Offset(c.x + currentGap, c.y) to Offset(c.x + currentEdge, c.y),
        Offset(c.x, c.y - currentEdge) to Offset(c.x, c.y - currentGap),
        Offset(c.x, c.y + currentGap) to Offset(c.x, c.y + currentEdge),
    )

    segments.forEach { (a, b) ->
        // Dark halo for high contrast over any bright video feed
        drawLine(Color.Black.copy(alpha = 0.58f), a, b, 3.dp.toPx(), StrokeCap.Round)
        // High visibility white reticle line
        drawLine(Color.White.copy(alpha = 0.90f), a, b, 1.2.dp.toPx(), StrokeCap.Round)
    }

    // 4. Center aim pip
    drawCircle(Color.Black.copy(alpha = 0.50f), radius = 1.6.dp.toPx(), center = c)
    drawCircle(Color.White.copy(alpha = 0.85f), radius = 1.0.dp.toPx(), center = c)

    // 5. Muzzle flash flare at center point on impact/shot
    if (flashProgress > 0f) {
        val flashColor = if (lastFiredAmmo == AmmoType.GEL) GelFlashColor else InfraredFlashColor
        val flashRadius = (3.6.dp.toPx() * flashProgress).coerceAtLeast(0.5f)
        drawCircle(flashColor.copy(alpha = flashProgress * 0.95f), radius = flashRadius, center = c)
        // Mini 4-point star ray
        val rayLen = 5.5.dp.toPx() * flashProgress
        val rayAlpha = flashProgress * 0.8f
        drawLine(
            flashColor.copy(alpha = rayAlpha),
            Offset(c.x - rayLen, c.y),
            Offset(c.x + rayLen, c.y),
            1.2.dp.toPx(),
            StrokeCap.Round
        )
        drawLine(
            flashColor.copy(alpha = rayAlpha),
            Offset(c.x, c.y - rayLen),
            Offset(c.x, c.y + rayLen),
            1.2.dp.toPx(),
            StrokeCap.Round
        )
    }
}
