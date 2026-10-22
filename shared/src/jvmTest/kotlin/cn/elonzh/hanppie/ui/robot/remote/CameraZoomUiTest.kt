package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalTestApi::class)
class CameraZoomLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)

    private fun layout(width: Int, height: Int, language: String = "zh", fontScale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel()
            model.remoteEnabled.value = true
            model.cameraZoom.attach(CameraFixture().apply { state.value = CameraState(400) })
            val controller = object : ConsoleController by model {
                override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) {}
            }
            try {
                setContent { TestWorkbenchTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        LaunchedEffect(Unit) {
                            val frame = ImageBitmap(1280, 720)
                            while (true) { model.videoFrames.publish(null, frame); delay(50) }
                        }
                        Box(Modifier.background(HanppieDesignTokens.RemoteHudSurface)) {
                            RemotePage(controller, videoContent = {}, manageSession = false)
                        }
                    }
                } }
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodes(hasTestTag("camera-zoom") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("tracking-start").assertIsNotEnabled()
                val zoom = onNodeWithTag("camera-zoom").fetchSemanticsNode().boundsInRoot
                val tracking = onNodeWithTag("tracking-controls").fetchSemanticsNode().boundsInRoot
                assertFalse(zoom.overlaps(tracking))
                val image = onNodeWithTag("remote-surface").captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/zoom-$width-$height-$language.png").apply { parentFile.mkdirs() }
                ImageIO.write(out, "png", file)
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
