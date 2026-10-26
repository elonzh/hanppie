package cn.elonzh.hanppie.ui.robot.album

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream

internal actual suspend fun localVideoThumbnail(path: String): ByteArray {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(path)
        val frame = checkNotNull(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
        try { return ByteArrayOutputStream().use { output ->
            check(frame.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
        } } finally { frame.recycle() }
    } finally { retriever.release() }
}
