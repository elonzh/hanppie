package cn.elonzh.hanppie.ui.robot.album

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.app.Console
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import cn.elonzh.hanppie.ui.i18n.formatLocalDateTime
import cn.elonzh.hanppie.ui.i18n.DateTimeStyle
import cn.elonzh.hanppie.ui.design.*
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.media.desktopFfmpegExecutable
import io.github.vinceglb.filekit.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class CapturedMediaUiTest {
    @Test fun compact() = scenario(844, 390)
    @Test fun tablet() = scenario(1024, 600)
    @Test fun desktop() = scenario(1280, 720)
    @Test fun englishLargeText() = scenario(844, 390, "en", 1.4f)
    private fun scenario(width: Int, height: Int, language: String = "zh", scale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val root = Files.createTempDirectory("hanppie-local-preview-ui-").toFile()
            val model = testConsoleModel(albumDirectory = { PlatformFile(root) })
            val photo = model.robotAlbum.captureDestination(MediaContent.JPEG)
            val bitmap = BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB)
            bitmap.createGraphics().apply { color = Color(223, 107, 56); fillRect(0, 0, 640, 360); dispose() }
            ImageIO.write(bitmap, "jpg", File(photo.path))
            val video = model.robotAlbum.captureDestination(MediaContent.VIDEO)
            val generated = ProcessBuilder(desktopFfmpegExecutable(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=640x360:r=30", "-t", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", video.path).start()
            assertTrue(generated.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, generated.exitValue())
            fun capture(name: String) {
                val image = onRoot().captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                ImageIO.write(out, "png", File("build/reports/ui/local-library-$width-$height-$language-$name.png").apply { parentFile.mkdirs() })
            }
            try {
                setContent { TestWorkbenchTheme { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    Console(model, remember { mutableStateOf(EditorDocument()) }, onRobotMediaExport = {})
                } } }
                onNodeWithTag("navigate-4").performClick()
                onNodeWithTag("navigate-5").performClick()
                onNodeWithTag("navigate-5").assertIsSelected()
                waitUntil(timeoutMillis = 5_000) { model.robotAlbum.state.value.local.size == 2 }
                val savedPhoto = model.robotAlbum.state.value.local.first { it.file.path == photo.path }
                assertNotNull(savedPhoto.createdAt)
                assertTrue(onAllNodesWithText(photo.name, substring = true).fetchSemanticsNodes().isNotEmpty())
                val created = tr(Res.string.media_created_at, formatLocalDateTime(savedPhoto.createdAt!!, DateTimeStyle.SHORT))
                onAllNodesWithText(created).onFirst().assertIsDisplayed()
                waitUntil(timeoutMillis = 10_000) { model.robotAlbum.state.value.localThumbnails.size == 2 &&
                    model.robotAlbum.state.value.localThumbnails.values.all { it is MediaThumbnail.Ready } }
                onNodeWithTag("album-local-${PlatformFile(photo.path).hashCode()}-thumbnail", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag("album-local-${PlatformFile(video.path).hashCode()}-thumbnail", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag("album-local-${PlatformFile(video.path).hashCode()}-video", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag("album-local-${PlatformFile(photo.path).hashCode()}-video", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithTag("album-progress").assertDoesNotExist()
                capture("list")
                onNodeWithTag("album-local-${PlatformFile(photo.path).hashCode()}").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("album-local-preview").fetchSemanticsNodes().isNotEmpty() }
                capture("photo")
                if (onAllNodesWithTag("album-list-back").fetchSemanticsNodes().isNotEmpty()) onNodeWithTag("album-list-back").performClick()
                onNodeWithTag("album-local-${PlatformFile(video.path).hashCode()}").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("local-media-video-frame").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("local-media-player").assertIsDisplayed()
                capture("video-ready")
                onNodeWithTag("local-media-play").assertIsDisplayed().performClick()
                onNodeWithContentDescription(tr(Res.string.media_preview_pause)).assertIsDisplayed()
                onNodeWithTag("local-media-play").assertIsDisplayed().performClick()
                capture("video")
                onNodeWithText(tr(Res.string.media_preview_failed)).assertDoesNotExist()
                onNodeWithTag("album-open").assertDoesNotExist()
                assertFalse(model.state.value.connected)
            } finally { model.close(); root.deleteRecursively(); Localization.initialize("zh", null) }
        }
}
