package cn.elonzh.hanppie.ui.robot.album

import cn.elonzh.hanppie.ui.media.desktopFfmpegExecutable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual suspend fun localVideoThumbnail(path: String): ByteArray = withTimeout(8_000) {
    suspendCancellableCoroutine { continuation ->
        val process = ProcessBuilder(desktopFfmpegExecutable(), "-hide_banner", "-nostdin", "-loglevel", "error",
            "-i", path, "-an", "-frames:v", "1", "-vf", "scale=160:120:force_original_aspect_ratio=decrease",
            "-f", "image2pipe", "-vcodec", "png", "pipe:1").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        continuation.invokeOnCancellation { process.destroyForcibly() }
        thread(name = "hanppie-media-thumbnail", isDaemon = true) {
            try {
                val bytes = process.inputStream.use { it.readNBytes(1024 * 1024) }
                check(process.waitFor() == 0 && bytes.isNotEmpty())
                continuation.resume(bytes)
            } catch (error: Exception) { continuation.resumeWithException(error) }
            finally { process.destroyForcibly() }
        }
    }
}
