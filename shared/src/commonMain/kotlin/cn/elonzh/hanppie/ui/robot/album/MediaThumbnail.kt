package cn.elonzh.hanppie.ui.robot.album

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import cn.elonzh.hanppie.robot.media.album.RobotMediaEntry
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.readBytes
import org.jetbrains.compose.resources.decodeToImageBitmap

internal sealed interface MediaThumbnail {
    data object Loading : MediaThumbnail
    data class Ready(val image: ImageBitmap) : MediaThumbnail
    data object Failed : MediaThumbnail
    data object Unavailable : MediaThumbnail
}

internal suspend fun localMediaThumbnail(file: LocalRobotMedia): MediaThumbnail {
    val bytes = when (file.content) {
        MediaContent.JPEG, MediaContent.PNG -> { check(file.size <= 32L * 1024 * 1024); file.file.readBytes() }
        MediaContent.VIDEO -> localVideoThumbnail(file.file.path)
        MediaContent.UNKNOWN -> return MediaThumbnail.Unavailable
    }
    val source = bytes.decodeToImageBitmap()
    val scale = minOf(160f / source.width, 120f / source.height, 1f)
    val size = IntSize((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1))
    val image = ImageBitmap(size.width, size.height)
    Canvas(image).drawImageRect(source, IntOffset.Zero, IntSize(source.width, source.height), IntOffset.Zero, size, Paint())
    return MediaThumbnail.Ready(image)
}

/** Platform video decoding only; visibility, caching and scheduling remain shared. */
internal expect suspend fun localVideoThumbnail(path: String): ByteArray

/** Camera video thumbnails are 160×120 NV16; photo thumbnails are encoded images.
 * The raw layout is verified against SD transfer bytes and the matching MP4 frame.
 */
internal fun decodeMediaThumbnail(entry: RobotMediaEntry, bytes: ByteArray): MediaThumbnail {
    if (entry.isVideo && bytes.size == 160 * 120 * 2 && mediaContent(bytes) == MediaContent.UNKNOWN) {
        val image = ImageBitmap(160, 120)
        val canvas = Canvas(image)
        val paint = Paint().apply { isAntiAlias = false }
        for (y in 0 until 120) for (x in 0 until 160) {
            val luma = (bytes[y * 160 + x].toInt() and 255) - 16
            val chroma = 160 * 120 + y * 160 + (x / 2) * 2
            val u = (bytes[chroma].toInt() and 255) - 128
            val v = (bytes[chroma + 1].toInt() and 255) - 128
            val red = ((298 * luma + 409 * v + 128) shr 8).coerceIn(0, 255)
            val green = ((298 * luma - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val blue = ((298 * luma + 516 * u + 128) shr 8).coerceIn(0, 255)
            paint.color = Color(red, green, blue)
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
        }
        return MediaThumbnail.Ready(image)
    }
    return try { MediaThumbnail.Ready(bytes.decodeToImageBitmap()) }
    catch (_: Exception) { MediaThumbnail.Unavailable }
}
