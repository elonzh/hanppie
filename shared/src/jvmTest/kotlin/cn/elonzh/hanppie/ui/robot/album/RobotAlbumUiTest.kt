package cn.elonzh.hanppie.ui.robot.album

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.media.CameraStorageStatus
import cn.elonzh.hanppie.ui.app.Console
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.robot.camera.RobotCameraUiState
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import cn.elonzh.hanppie.ui.settings.*
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class RobotAlbumUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun maintenanceNavigationOpensTheAlbumAndReturnsToItsOrigin() {
        Localization.initialize("zh", null)
        val root = Files.createTempDirectory("hanppie-album-ui").toFile()
        val model = testConsoleModel(albumDirectory = { PlatformFile(root) })
        val service = AlbumFixture()
        model.robotAlbum.attach(service)
        try {
            rule.setContent { TestWorkbenchTheme { Console(model, remember { mutableStateOf(EditorDocument()) }) } }
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithTag("settings-category-robot").performScrollTo().performClick()
            rule.onNodeWithTag("settings-album-open").performScrollTo().performClick()
            rule.onNodeWithTag("robot-album-page").assertIsDisplayed()
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("album-entry-1")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("album-back").performClick()
            rule.onNodeWithTag("settings-detail-robot").assertIsDisplayed()
        } finally { model.close(); root.deleteRecursively() }
    }

    @Test fun thumbnailFailureDoesNotBlockDownloadAndCompletedMediaOpensAndExportsOffline() {
        Localization.initialize("zh", null)
        val root = Files.createTempDirectory("hanppie-album-ui").toFile()
        val model = testConsoleModel(albumDirectory = { PlatformFile(root) })
        val service = AlbumFixture().apply { thumbnailError = true }
        var opened: LocalRobotMedia? = null
        var exported: LocalRobotMedia? = null
        model.robotAlbum.attach(service)
        try {
            rule.setContent { TestWorkbenchTheme { RobotAlbumPage(model, onBack = {}, onOpen = { opened = it }, onExport = { exported = it }) } }
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("album-entry-1")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("album-entry-1").performClick()
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("album-thumbnail-error")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("album-download").assertIsEnabled().performClick()
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("album-downloaded")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("album-open").performClick()
            rule.onNodeWithTag("album-export").performClick()
            assertNotNull(opened); assertEquals(opened, exported)
            assertContentEquals(service.photo, runBlocking { opened!!.file.readBytes() })
            rule.runOnIdle { model.robotAlbum.clear() }
            rule.onNodeWithText("已下载", useUnmergedTree = true).performClick()
            rule.waitUntil(3_000) { rule.onAllNodes(hasText("照片 #1")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasText("照片 #1") and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
            rule.onNode(hasText("照片 #1") and hasClickAction()).assertIsSelected()
            rule.onNodeWithTag("album-open").assertIsEnabled()
            rule.onNodeWithTag("album-remove").performScrollTo().performClick()
            rule.onNodeWithText("此操作不会删除机器人 SD 卡上的原文件。").assertIsDisplayed()
            rule.onNodeWithTag("album-remove-confirm").performClick()
            rule.waitUntil(3_000) { model.robotAlbum.state.value.local.isEmpty() }
            rule.onNodeWithText("还没有下载的媒体。").assertIsDisplayed()
        } finally { model.close(); root.deleteRecursively() }
    }

    @Test fun activeAlbumTransferDisablesFormattingWithoutHidingItsReason() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val service = AlbumFixture().apply { busy.value = true }
        model.state.value = model.state.value.copy(connected = true)
        model.robotCamera.state.value = RobotCameraUiState(storage = CameraStorageStatus(true, 0, 1024, 512, 10, 20, false, false, 0))
        model.robotAlbum.attach(service)
        try {
            rule.setContent { TestWorkbenchTheme { SettingsPage(model) } }
            rule.onNodeWithTag("settings-category-robot").performScrollTo().performClick()
            rule.onNodeWithTag("camera-storage-format").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithText("正在读取存储卡，传输结束后可格式化。").assertIsDisplayed()
            rule.runOnIdle { service.busy.value = false }
            rule.waitUntil(3_000) { !model.robotAlbum.state.value.nativeBusy }
            rule.onNodeWithTag("camera-storage-format").assertIsEnabled()
        } finally { model.close() }
    }


}

@OptIn(ExperimentalTestApi::class)
class RobotAlbumLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun chineseLargeText() = layout(844, 390, "zh", 1.4f)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)

    private fun layout(width: Int, height: Int, language: String = "zh", fontScale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val root = Files.createTempDirectory("hanppie-album-layout").toFile()
            val model = testConsoleModel(albumDirectory = { PlatformFile(root) })
            model.robotAlbum.attach(AlbumFixture())
            try {
                setContent { TestWorkbenchTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        RobotAlbumPage(model, Modifier.fillMaxSize().padding(16.dp), onBack = {}, onOpen = {}, onExport = {})
                    }
                } }
                waitUntil(timeoutMillis = 3_000) { onAllNodes(hasTestTag("album-entry-1")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("album-entry-1").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assertHasClickAction().assertIsNotSelected().performClick()
                assertEquals(1, model.robotAlbum.state.value.selected?.index)
                if (onAllNodesWithTag("album-entry-1").fetchSemanticsNodes().isNotEmpty()) {
                    onNodeWithTag("album-entry-1").assertIsSelected()
                } else {
                    onNodeWithTag("album-list").assertDoesNotExist()
                    onNodeWithTag("album-list-back").assertIsDisplayed().performClick()
                    onNodeWithTag("album-entry-1").assertIsNotSelected().performClick()
                }
                waitUntil(timeoutMillis = 3_000) { onAllNodes(hasTestTag("album-thumbnail")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("album-download").assertIsDisplayed().assertIsEnabled()
                assertTrue(onNodeWithTag("album-download").fetchSemanticsNode().boundsInRoot.height >= 48)
                snapshot("album-$width-$height-$language-$fontScale", onNodeWithTag("robot-album-page"))
                onNodeWithTag("album-download").performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodes(hasTestTag("album-downloaded")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("album-export").assertIsDisplayed().assertIsEnabled()
                snapshot("album-downloaded-$width-$height-$language-$fontScale", onNodeWithTag("robot-album-page"))
            } finally { model.close(); root.deleteRecursively(); Localization.initialize("zh", null) }
        }

}

/** Selection ownership and recovery in every supported landscape layout. */
@OptIn(ExperimentalTestApi::class)
class RobotAlbumLocalMediaTest {
    @Test fun compactLandscape() = localMedia(844, 390)
    @Test fun tabletLandscape() = localMedia(1024, 600, dark = true)
    @Test fun desktopLandscape() = localMedia(1280, 720)
    @Test fun chineseLargeText() = localMedia(844, 390, "zh", 1.4f, dark = true)
    @Test fun englishLargeText() = localMedia(844, 390, "en", 1.4f, dark = true)

    private fun localMedia(width: Int, height: Int, language: String = "zh", fontScale: Float = 1f, dark: Boolean = false) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val root = Files.createTempDirectory("hanppie-album-preview").toFile()
            fun file(index: Int, extension: String) = File(root, "sd-1000-$index-00000000-0000-0000-0000-000000000000.$extension")
            val colors = listOf(java.awt.Color(231, 77, 29), java.awt.Color(25, 107, 213))
            colors.forEachIndexed { index, color ->
                val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
                image.createGraphics().apply { this.color = color; fillRect(0, 0, 32, 32); dispose() }
                ImageIO.write(image, "png", file(index + 1, "png"))
            }
            file(3, "mp4").writeBytes(byteArrayOf(0, 0, 0, 12, 102, 116, 121, 112, 105, 115, 111, 109))
            file(4, "bin").writeText("unknown media")
            file(5, "png").writeText("broken photo")
            val model = testConsoleModel(albumDirectory = { PlatformFile(root) })
            var opened: LocalRobotMedia? = null
            var exported: LocalRobotMedia? = null
            fun select(title: String) {
                if (onAllNodesWithTag("album-list-back").fetchSemanticsNodes().isNotEmpty()) {
                    onNodeWithTag("album-list-back").performClick()
                }
                val row = hasText(title, substring = true) and hasClickAction()
                onNodeWithTag("album-list").performScrollToNode(row)
                onNode(row).performClick()
            }
            fun assertPhoto(color: java.awt.Color) {
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag("album-local-preview").fetchSemanticsNodes().isNotEmpty() }
                val image = onNodeWithTag("album-local-preview").captureToImage()
                val pixel = IntArray(1)
                image.readPixels(pixel, image.width / 2, image.height / 2, 1, 1)
                assertEquals(color.rgb, pixel.single())
                val pixels = IntArray(image.width * image.height)
                image.readPixels(pixels)
                var left = image.width; var right = -1; var top = image.height; var bottom = -1
                pixels.forEachIndexed { index, value -> if (value == color.rgb) {
                    val x = index % image.width; val y = index / image.width
                    left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                } }
                val visibleWidth = right - left + 1; val visibleHeight = bottom - top + 1
                assertTrue(kotlin.math.abs(visibleWidth - visibleHeight) <= 1,
                    "The entire square photo must be visible without scrolling: $visibleWidth x $visibleHeight")
            }
            try {
                setContent { TestWorkbenchTheme(AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        RobotAlbumPage(model, Modifier.fillMaxSize().padding(16.dp), onBack = {}, onOpen = { opened = it }, onExport = { exported = it })
                    }
                } }
                waitUntil(timeoutMillis = 3_000) { model.robotAlbum.state.value.local.size == 5 }
                select(tr(Res.string.album_photo_name, 1))
                assertPhoto(colors[0])
                snapshot("album-local-photo-$width-$height-$language-$fontScale", onNodeWithTag("robot-album-page"))
                select(tr(Res.string.album_video_name, 3))
                onNodeWithTag("album-local-preview").assertDoesNotExist()
                snapshot("album-local-video-$width-$height-$language-$fontScale", onNodeWithTag("robot-album-page"))
                onNodeWithTag("album-open").assertIsDisplayed().performClick()
                assertEquals(3L, opened?.sourceIndex)
                select(tr(Res.string.album_photo_name, 2))
                assertPhoto(colors[1])
                select(tr(Res.string.album_media_name, 4))
                onNodeWithTag("album-local-preview").assertDoesNotExist()
                snapshot("album-local-unknown-$width-$height-$language-$fontScale", onNodeWithTag("robot-album-page"))
                onNodeWithTag("album-export").assertIsDisplayed().performClick()
                assertEquals(4L, exported?.sourceIndex)
                select(tr(Res.string.album_photo_name, 1))
                assertPhoto(colors[0])
                select(tr(Res.string.album_photo_name, 5))
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText(tr(Res.string.album_preview_failed)).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("album-local-preview").assertDoesNotExist()
                snapshot("album-local-broken-$width-$height-$language-$fontScale", onNodeWithTag("robot-album-page"))
                select(tr(Res.string.album_photo_name, 2))
                assertPhoto(colors[1])
            } finally { model.close(); root.deleteRecursively(); Localization.initialize("zh", null) }
        }
}

private fun snapshot(name: String, node: SemanticsNodeInteraction) {
    val image = node.captureToImage()
    val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
    val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
    val file = File("build/reports/ui/$name.png").apply { parentFile.mkdirs() }
    ImageIO.write(out, "png", file)
}
