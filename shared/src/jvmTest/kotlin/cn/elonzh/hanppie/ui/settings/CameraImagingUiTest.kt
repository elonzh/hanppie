package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.camera.ImagingCameraFixture
import cn.elonzh.hanppie.ui.robot.camera.CameraRequest
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class CameraImagingUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun realSettingsExposeReportedCamerasAndSeparateRateAcknowledgementFromDeviceStatus() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val camera = ImagingCameraFixture()
        model.state.value = model.state.value.copy(connected = true)
        model.robotCamera.attach(camera)
        try {
            rule.setContent { TestWorkbenchTheme { SettingsPage(model) } }
            rule.onNodeWithTag("settings-category-media").performScrollTo().performClick()
            rule.onNodeWithTag("settings-camera-open").performScrollTo().performClick()
            rule.onNodeWithTag("camera-video-format").performScrollTo().assertTextEquals("当前视频：1080p · 30 fps")
            rule.onNodeWithTag("camera-photo-size-selector").performScrollTo().performClick()
            rule.onNodeWithTag("camera-photo-size-R1440P").performClick()
            rule.waitUntil(3_000) { camera.requests.contains("photo:2") }
            rule.onNodeWithTag("camera-source-selector").performScrollTo().performClick()
            rule.onNodeWithTag("camera-source-1").performClick()
            rule.waitUntil(3_000) { camera.state.value.imaging?.cameraIndex == 1 }
            rule.onNodeWithTag("camera-transmission-rate-selector").performScrollTo().performClick()
            rule.onNodeWithTag("camera-transmission-rate-BEST").performClick()
            rule.waitUntil(3_000) { model.robotCamera.state.value.transmissionRate is CameraRequest.Value }
            rule.onNodeWithTag("camera-rate-result").assertDoesNotExist()
            camera.failure = RequestProblem.TIMED_OUT
            rule.onNodeWithTag("camera-request-keyframe").performScrollTo().performClick()
            rule.waitUntil(3_000) { model.robotCamera.state.value.keyFrame is CameraRequest.Failed }
            rule.onNodeWithTag("camera-keyframe-result").performScrollTo().assertIsDisplayed()
            camera.failure = null
            rule.onNodeWithTag("camera-request-keyframe").performClick()
            rule.waitUntil(3_000) { model.robotCamera.state.value.keyFrame is CameraRequest.Value }
            assertEquals(2, camera.requests.count { it == "keyframe" })
            rule.onNodeWithTag("camera-keyframe-result").assertDoesNotExist()
            rule.runOnIdle { model.robotAlbum.state.value = model.robotAlbum.state.value.copy(nativeBusy = true) }
            rule.onNodeWithTag("camera-source-selector").performScrollTo().assertIsNotEnabled()
            rule.runOnIdle {
                model.robotAlbum.state.value = model.robotAlbum.state.value.copy(nativeBusy = false)
                camera.state.value = camera.state.value.copy(imaging = camera.state.value.imaging?.copy(sources = 1, cameraIndex = 0))
            }
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("camera-source-selector")).fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("camera-source-value").performScrollTo().assertTextEquals("当前相机：主相机")
            rule.runOnIdle { model.robotCamera.clear(); model.state.value = model.state.value.copy(connected = false) }
            rule.onNodeWithTag("camera-request-keyframe").assertDoesNotExist()
            rule.onNodeWithTag("camera-rate-result").assertDoesNotExist()
        } finally { model.close() }
    }
}

@OptIn(ExperimentalTestApi::class)
class CameraImagingLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun chineseLargeText() = layout(844, 390, "zh", 1.4f)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)

    private fun layout(width: Int, height: Int, language: String = "zh", fontScale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel()
            model.state.value = model.state.value.copy(connected = true)
            model.robotCamera.attach(ImagingCameraFixture())
            try {
                setContent { TestWorkbenchTheme { CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    Box(Modifier.fillMaxSize().padding(16.dp).testTag("imaging-layout")) { SettingsPage(model) }
                } } }
                onNodeWithTag("settings-category-media").performScrollTo().performClick()
                onNodeWithTag("settings-camera-open").performScrollTo().performClick()
                for (tag in listOf("camera-photo-size-selector", "camera-source-selector", "camera-transmission-rate-selector", "camera-request-keyframe")) {
                    onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
                    assertTrue(onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height >= 48)
                }
                val image = onNodeWithTag("imaging-layout").captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/camera-imaging-$width-$height-$language-$fontScale.png").apply { parentFile.mkdirs() }
                ImageIO.write(out, "png", file)
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
