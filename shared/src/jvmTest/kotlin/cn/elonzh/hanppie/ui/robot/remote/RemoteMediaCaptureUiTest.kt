package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.media.desktopFfmpegExecutable
import cn.elonzh.hanppie.ui.robot.album.MediaContent
import io.github.vinceglb.filekit.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Actual desktop decoder and capture backend; network and robot commands remain isolated. */
@OptIn(ExperimentalTestApi::class)
class RemoteMediaCaptureUiTest {
    @Test fun hudCanStopRestartCaptureAndFinishFilesWithoutLeakingPathsIntoThePage() = runDesktopComposeUiTest(width = 1280, height = 720) {
        val root = Files.createTempDirectory("hanppie-hud-capture-").toFile()
        val encoded = root.resolve("source.h264")
        val generator = ProcessBuilder(desktopFfmpegExecutable(), "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
            "color=c=blue:s=1280x720:r=30", "-t", "1", "-c:v", "libx264", "-tune", "zerolatency", "-f", "h264", encoded.path).start()
        assertTrue(generator.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, generator.exitValue())
        val model = testConsoleModel(albumDirectory = { PlatformFile(root) })
        model.state.value = model.state.value.copy(connected = true)
        model.remoteEnabled.value = true
        val controller = object : ConsoleController by model {
            override fun startMedia(audio: Boolean) = Unit
            override fun stopMedia() { model.videoSink = null; model.audioSink = null }
        }
        val media = RemoteMediaController()
        val packets = encoded.readBytes()
        fun feed() { waitUntil(timeoutMillis = 5_000) { model.videoSink != null }; model.videoSink!!.invoke(packets) }
        try {
            setContent { TestWorkbenchTheme { RemotePage(controller, Modifier.fillMaxSize(), mediaControls = media, manageSession = false) } }
            feed()
            waitUntil(timeoutMillis = 5_000) { media.state.value.videoReady }
            onNodeWithTag("remote-video-toggle").performTouchInput { click(center) }
            waitUntil(timeoutMillis = 3_000) { !media.state.value.playing && !media.state.value.videoReady }
            onNodeWithTag("remote-video-toggle").assertIsEnabled().performTouchInput { click(center) }
            feed()
            waitUntil(timeoutMillis = 5_000) { media.state.value.videoReady }
            onNodeWithTag("remote-photo").performTouchInput { click(center) }
            waitUntil(timeoutMillis = 5_000) { !media.state.value.saving && root.listFiles().orEmpty().any { it.extension == "jpg" } }
            onNodeWithTag("remote-record").performTouchInput { click(center) }
            waitUntil(timeoutMillis = 3_000) { media.state.value.recording }
            feed()
            waitUntil(timeoutMillis = 5_000) { root.listFiles().orEmpty().any { it.name.endsWith(".video.part") && it.length() > 0 } }
            onNodeWithTag("remote-record").performTouchInput { click(center) }
            waitUntil(timeoutMillis = 10_000) { !media.state.value.recording && !media.state.value.saving && root.listFiles().orEmpty().any { it.extension == "mp4" } }
            model.robotAlbum.loadLocal()
            waitUntil(timeoutMillis = 5_000) { model.robotAlbum.state.value.local.size == 2 }
            assertEquals(setOf(MediaContent.JPEG, MediaContent.VIDEO), model.robotAlbum.state.value.local.map { it.content }.toSet())
            onAllNodes(hasText(root.path, substring = true)).assertCountEquals(0)
            assertTrue(model.remoteInput.value.all { it == 0.0 })
            runOnIdle { model.state.value = model.state.value.copy(connected = false) }
        } finally { model.close(); root.deleteRecursively() }
    }
}
