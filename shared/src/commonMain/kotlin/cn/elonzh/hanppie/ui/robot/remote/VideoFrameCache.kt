package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.time.Duration.Companion.milliseconds

/** Last decoded image, shared by every video consumer for this session. No decoder or stream is retained. */
internal class VideoFrameCache {
    private data class Frame(val address: String?, val image: ImageBitmap,
        val receivedAt: kotlin.time.TimeMark = kotlin.time.TimeSource.Monotonic.markNow())
    private var latest by mutableStateOf<Frame?>(null)

    fun image(address: String?): ImageBitmap? = latest?.takeIf { it.address == address }?.image
    fun isFresh(address: String?): Boolean = latest?.let {
        it.address == address && it.receivedAt.elapsedNow() < 750.milliseconds
    } == true
    fun publish(address: String?, image: ImageBitmap) { latest = Frame(address, image) }
}
