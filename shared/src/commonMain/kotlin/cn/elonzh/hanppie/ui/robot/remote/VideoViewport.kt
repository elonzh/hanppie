package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.ui.geometry.Rect
import cn.elonzh.hanppie.robot.vision.VisionRect

/** Same Fit transform as the video renderers. Letterbox taps never select a target. */
internal data class VideoViewport(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun point(x: Float, y: Float): Pair<Float, Float>? =
        if (width > 0 && height > 0 && x in left..left + width && y in top..top + height)
            (x - left) / width to (y - top) / height else null

    /** Only tracking Follow_2 uses an upward-shifted pixel square; other detectors use their source geometry. */
    fun bounds(rect: VisionRect, following: Boolean = false): Rect {
        val w = rect.width * width
        val h = if (following) w else rect.height * height
        val cx = left + rect.x * width
        val cy = top + (rect.y - if (following) rect.height * .25f else 0f) * height
        val x1 = (cx - w / 2).coerceIn(left, left + width)
        val y1 = (cy - h / 2).coerceIn(top, top + height)
        return Rect(x1, y1, (cx + w / 2).coerceIn(x1, left + width), (cy + h / 2).coerceIn(y1, top + height))
    }

    companion object {
        fun fit(width: Float, height: Float, videoWidth: Int, videoHeight: Int): VideoViewport {
            require(videoWidth > 0 && videoHeight > 0)
            val scale = minOf(width / videoWidth, height / videoHeight)
            val w = videoWidth * scale; val h = videoHeight * scale
            return VideoViewport((width - w) / 2, (height - h) / 2, w, h)
        }
    }
}
