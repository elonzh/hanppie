package cn.elonzh.hanppie.ui.robot.album

import cn.elonzh.hanppie.ui.media.desktopFfmpegExecutable
import cn.elonzh.hanppie.ui.robot.remote.saveDesktopPhoto
import cn.elonzh.hanppie.robot.media.VideoResolution
import io.github.vinceglb.filekit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DesktopFilePlaybackTest {
    @Test fun capturedPhotosAndRecordingsAreListedOfflineAndPlaybackDecodesVideoAndAudio() = runBlocking<Unit> {
        val root = Files.createTempDirectory("hanppie-capture-library-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val album = RobotAlbumController(scope, { PlatformFile(root) }, { emptyList() })
        try {
            val photo = album.captureDestination(MediaContent.JPEG)
            saveDesktopPhoto(ByteArray(1280 * 720 * 4), VideoResolution.R720P, java.nio.file.Path.of(photo.path))
            val video = album.captureDestination(MediaContent.VIDEO)
            val generated = ProcessBuilder(desktopFfmpegExecutable(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=640x360:r=30", "-f", "lavfi", "-i", "sine=frequency=200:sample_rate=48000",
                "-t", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", video.path).start()
            assertTrue(generated.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, generated.exitValue())
            album.loadLocal()
            val local = withTimeout(5_000) { album.state.first { !it.localLoading && it.local.size == 2 } }.local
            assertTrue(local.all { it.captured }); assertEquals(setOf(MediaContent.JPEG, MediaContent.VIDEO), local.map { it.content }.toSet())
            val frames = AtomicInteger(); val audio = CountDownLatch(1); val ended = CountDownLatch(1); val failures = AtomicInteger()
            val player = DesktopFilePlayback(video.path, 0.0, true,
                { assertEquals(640 * 360 * 4, it.size); frames.incrementAndGet() }, {}, { assertTrue(it >= .9) },
                { ended.countDown() }, { failures.incrementAndGet() }, playAudio = false, onAudio = { _, n -> if (n > 0) audio.countDown() })
            try { assertTrue(ended.await(10, TimeUnit.SECONDS)); assertTrue(audio.await(1, TimeUnit.SECONDS)); assertTrue(frames.get() >= 25); assertEquals(0, failures.get()) }
            finally { player.close() }
            val preview = CountDownLatch(1)
            DesktopFilePlayback(video.path, .4, false, { preview.countDown() }, { assertTrue(it >= .4) }, {}, {}, { failures.incrementAndGet() }, playAudio = false)
                .use { assertTrue(preview.await(5, TimeUnit.SECONDS)) }
            assertEquals(0, failures.get())
            album.removeLocal(local.first()); withTimeout(3_000) { album.state.first { it.local.size == 1 } }
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
