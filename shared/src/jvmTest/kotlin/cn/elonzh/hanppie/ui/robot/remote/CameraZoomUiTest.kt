package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import cn.elonzh.hanppie.robot.media.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.tracking.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.display.displayFixtureVideo
import cn.elonzh.hanppie.ui.settings.AppearanceController
import cn.elonzh.hanppie.ui.settings.AppearanceSettings
import cn.elonzh.hanppie.ui.settings.NightMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

private class CameraFixture : RobotCameraService {
    override val state = MutableStateFlow(CameraState(100))
    var fail = false
    override suspend fun takePhoto() { error("unused") }
    override suspend fun setRecording(active: Boolean) { error("unused") }
    override suspend fun formatStorage() { error("unused") }
    override suspend fun getAntiFlicker(): Int = error("unused")
    override suspend fun setAntiFlicker(mode: AntiFlicker): Int = error("unused")
    override suspend fun getDate(): CameraDate? = error("unused")
    override suspend fun syncClock(): CameraDate = error("unused")
    override suspend fun setPhotoSize(size: cn.elonzh.hanppie.robot.media.CameraPhotoSize) = error("unused")
    override suspend fun setTransmissionRate(rate: cn.elonzh.hanppie.robot.media.VideoTransmissionRate) = error("unused")
    override suspend fun switchCamera(index: Int) = error("unused")
    override suspend fun requestKeyFrame() = error("unused")
    override suspend fun setZoom(hundredths: Int) {
        if (fail) throw RobotRequestException(RequestProblem.REJECTED)
        state.value = CameraState(hundredths, state.value.revision + 1)
    }
}

class CameraZoomUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun changesZoomPreservesErrorsAndIsUnavailableDuringTrackingOrStaleStatus() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val camera = CameraFixture()
        model.remoteEnabled.value = true
        model.cameraZoom.attach(camera)
        val controller = object : ConsoleController by model {
            override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) {}
            override fun startObjectTracking() { model.tracking.value = TrackingState(TrackingPhase.SEARCHING) }
            override fun stopObjectTracking() { model.tracking.value = TrackingState() }
        }
        try {
            rule.setContent { TestWorkbenchTheme {
                LaunchedEffect(Unit) {
                    while (true) { model.videoFrames.publish(null, ImageBitmap(1280, 720)); delay(50) }
                }
                RemotePage(controller, videoContent = {}, manageSession = false)
            } }
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("camera-zoom") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("camera-zoom").performClick()
            rule.waitUntil(3_000) { camera.state.value.zoomHundredths == 400 && !model.cameraZoom.state.value.busy }
            rule.onNodeWithContentDescription("恢复 1 倍变焦").assertIsDisplayed()
            rule.onNodeWithTag("tracking-start").assertIsNotEnabled()
            rule.onNodeWithTag("camera-zoom").performClick()
            rule.waitUntil(3_000) { camera.state.value.zoomHundredths == 100 && !model.cameraZoom.state.value.busy }
            rule.onNodeWithTag("tracking-start").assertIsEnabled().performClick()
            rule.onNodeWithTag("camera-zoom").assertDoesNotExist()
            rule.onNodeWithTag("tracking-stop").performClick()
            camera.fail = true
            rule.onNodeWithTag("camera-zoom").performClick()
            rule.waitUntil(3_000) { model.cameraZoom.state.value.error != null }
            rule.onNodeWithTag("camera-zoom-error").assertIsDisplayed()
            assertEquals(100, model.cameraZoom.state.value.hundredths)
            camera.fail = false
            rule.onNodeWithTag("camera-zoom").performClick()
            rule.waitUntil(3_000) { camera.state.value.zoomHundredths == 400 && !model.cameraZoom.state.value.busy }
            rule.onNodeWithTag("camera-zoom-error").assertDoesNotExist()
            camera.state.value = CameraState(revision = 4)
            rule.waitUntil(3_000) { model.cameraZoom.state.value.hundredths == null }
            rule.onNodeWithTag("camera-zoom").assertIsNotEnabled()
            model.cameraZoom.clear()
            assertEquals(CameraZoomState(), model.cameraZoom.state.value)
        } finally { model.close() }
    }
}

/** Complete real-HUD text and task geometry, with synthetic camera status/video only. */
@OptIn(ExperimentalTestApi::class)
class CameraZoomLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600, dark = true)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)
    @Test fun chineseLargeText() = layout(844, 390, "zh", 1.4f, true)
    @Test fun traditionalChineseLargeText() = layout(844, 390, "zh-Hant", 1.4f, true)
    @Test fun japaneseLargeText() = layout(844, 390, "ja", 1.4f, true)
    @Test fun germanLargeText() = layout(844, 390, "de", 1.4f, true)
    @Test fun spanishLargeText() = layout(844, 390, "es", 1.4f, true)
    @Test fun frenchLargeText() = layout(844, 390, "fr", 1.4f, true)
    @Test fun koreanLargeText() = layout(844, 390, "ko", 1.4f, true)
    @Test fun russianLargeText() = layout(844, 390, "ru", 1.4f, true)

    private fun layout(width: Int, height: Int, language: String = "zh", fontScale: Float = 1f, dark: Boolean = false) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel()
            val camera = CameraFixture().apply { state.value = CameraState(400) }
            model.remoteEnabled.value = true
            model.cameraZoom.attach(camera)
            val video = displayFixtureVideo()
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val controller = object : ConsoleController by model {
                override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) {}
            }
            fun capture(name: String) {
                waitForIdle()
                val image = onNodeWithTag("remote-surface").captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/hud-fit-$width-$height-$language-$fontScale-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(output, "png", file)
            }
            fun assertHudFit() {
                val tracking = onNodeWithTag("tracking-controls").fetchSemanticsNode().boundsInRoot
                val sight = onNodeWithTag("remote-crosshair").fetchSemanticsNode().boundsInRoot
                val zoom = onNodeWithTag("camera-zoom").fetchSemanticsNode().boundsInRoot
                val capture = onNodeWithTag("onboard-capture-open").fetchSemanticsNode().boundsInRoot
                assertFalse(tracking.overlaps(zoom), "Task actions must avoid zoom")
                assertFalse(tracking.overlaps(capture), "Task actions must avoid SD capture")
                assertFalse(tracking.overlaps(sight), "Task actions must avoid the sight")
                assertTrue(tracking.left >= 0 && tracking.right <= width && tracking.top >= 0 && tracking.bottom <= height)
                for (tag in listOf("tracking-start", "vision-tools", "challenge-open", "camera-zoom", "onboard-capture-open")) {
                    val action = onNodeWithTag(tag)
                    action.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    val bounds = action.fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.bottom <= height)
                }
                val captions = onAllNodesWithTag("remote-stick-caption", useUnmergedTree = true)
                assertEquals(2, captions.fetchSemanticsNodes().size)
                for (index in 0..1) {
                    captions[index].assertIsDisplayed()
                    val bounds = captions[index].fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.bottom <= height)
                }
                captions[1].assertTextContains("°/s", substring = true)
            }
            try {
                setContent { TestWorkbenchTheme(appearance) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        LaunchedEffect(Unit) { while (true) { model.videoFrames.publish(null, video); delay(50) } }
                        RemotePage(controller, videoContent = { Image(video, "Synthetic HUD validation scene", Modifier.fillMaxSize()) }, manageSession = false)
                    }
                } }
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodes(hasTestTag("camera-zoom") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("tracking-start").assertIsNotEnabled()
                capture("4x"); assertHudFit()
                onNodeWithTag("camera-zoom").performClick()
                waitUntil(timeoutMillis = 3_000) { model.cameraZoom.state.value.hundredths == 100 && !model.cameraZoom.state.value.busy }
                onNodeWithTag("tracking-start").assertIsEnabled()
                capture("1x"); assertHudFit()
                if (language in listOf("en", "ru") && fontScale > 1f) {
                    onNodeWithTag("vision-tools").performClick()
                    onNodeWithTag("vision-start-aim").assertIsDisplayed()
                    onNodeWithTag("vision-start-lines").assertIsDisplayed()
                    val picker = onNodeWithTag("vision-picker").fetchSemanticsNode().boundsInRoot
                    assertTrue(picker.left >= 0 && picker.right <= width && picker.bottom <= height)
                    capture("picker")
                }
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
