package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import cn.elonzh.hanppie.robot.display.SightPoint
import cn.elonzh.hanppie.robot.vision.VisionRect

/** Shared Fit/Crop transform for video, reticles and detection. Hidden image regions are not tappable. */
internal data class VideoViewport(val left: Float, val top: Float, val width: Float, val height: Float) {
    val visibleLeft: Float get() = maxOf(0f, left)
    val visibleTop: Float get() = maxOf(0f, top)
    val visibleRight: Float get() = minOf(left + width, left * 2 + width)
    val visibleBottom: Float get() = minOf(top + height, top * 2 + height)

    fun point(x: Float, y: Float): Pair<Float, Float>? =
        if (width > 0 && height > 0 && x in visibleLeft..visibleRight && y in visibleTop..visibleBottom)
            (x - left) / width to (y - top) / height else null

    fun sightOffset(point: SightPoint?, zoomHundredths: Int): Offset = point?.let {
        Offset((it.x - 5000) / 10000f * width * zoomHundredths / 100f,
            -(it.y - 5000) / 10000f * height * zoomHundredths / 100f)
    } ?: Offset.Zero

    /** Only tracking Follow_2 uses an upward-shifted pixel square; other detectors use their source geometry. */
    fun bounds(rect: VisionRect, following: Boolean = false): Rect {
        val w = rect.width * width
        val h = if (following) w else rect.height * height
        val cx = left + rect.x * width
        val cy = top + (rect.y - if (following) rect.height * .25f else 0f) * height
        val x1 = (cx - w / 2).coerceIn(visibleLeft, visibleRight)
        val y1 = (cy - h / 2).coerceIn(visibleTop, visibleBottom)
        return Rect(x1, y1, (cx + w / 2).coerceIn(x1, visibleRight), (cy + h / 2).coerceIn(y1, visibleBottom))
    }

    companion object {
        fun forImage(width: Float, height: Float, videoWidth: Int, videoHeight: Int, fill: Boolean = false): VideoViewport {
            require(videoWidth > 0 && videoHeight > 0)
            val scale = if (fill) maxOf(width / videoWidth, height / videoHeight) else minOf(width / videoWidth, height / videoHeight)
            val w = videoWidth * scale; val h = videoHeight * scale
            return VideoViewport((width - w) / 2, (height - h) / 2, w, h)
        }
    }
}
