package cn.elonzh.hanppie.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import top.yukonga.miuix.kmp.basic.Text

/** Telemetry is camera yaw relative to chassis; the camera-up view uses its inverse. */
internal fun chassisHeadingInCameraFrame(relativeYaw: Double?): Double? =
    relativeYaw?.takeIf { it.isFinite() && it in -360.0..360.0 }?.let { -it }

@Composable
internal fun HeadingIndicator(relativeYaw: Double?) {
    val heading = chassisHeadingInCameraFrame(relativeYaw)
    val degrees = heading?.let { kotlin.math.round(it).toInt() }
    val angle = degrees?.let { if (it > 0) "+$it°" else "$it°" } ?: "—"
    val description = if (degrees == null) tr(Res.string.chassis_gimbal_angle_unknown)
        else tr(Res.string.chassis_heading_camera_value, angle)
    Row(Modifier.semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(28.dp)) {
            scale(size.width / 64f, size.height / 64f, pivot = Offset.Zero) {
                val ink = Color(0xffb8c7ce)
                val blue = Color(0xff88adbd)
                // A fixed field-of-view wedge, not a barrel or a second moving direction arrow.
                drawPath(Path().apply { moveTo(32f, 30f); lineTo(19f, 3f); lineTo(45f, 3f); close() }, blue.copy(alpha = .2f))
                if (heading != null) rotate(heading.toFloat(), pivot = Offset(32f, 34f)) {
                    val chassis = Path().apply {
                        moveTo(32f, 14f); lineTo(43f, 24f); lineTo(43f, 49f)
                        lineTo(21f, 49f); lineTo(21f, 24f); close()
                    }
                    drawPath(chassis, Color(0xff3c4b55))
                    drawPath(chassis, ink, style = Stroke(2f))
                    for (x in listOf(12f, 45f)) for (y in listOf(16f, 38f)) {
                        drawRoundRect(Color(0xff172028), Offset(x, y), Size(7f, 15f), CornerRadius(2f))
                        drawRoundRect(ink, Offset(x, y), Size(7f, 15f), CornerRadius(2f), style = Stroke(1f))

                    }

                } else drawCircle(ink.copy(alpha = .35f), 18f, Offset(32f, 34f), style = Stroke(1f))
                // Fixed camera point and field of view remain independent of the chassis.
                drawCircle(Color(0xff172028), 6f, Offset(32f, 34f))
                drawCircle(blue, 3f, Offset(32f, 34f))
            }
        }
        Text(angle, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = HanppieDesignTokens.RemoteHudContent, fontSize = 11.sp)
    }
}
