package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import cn.elonzh.hanppie.robot.media.*
import cn.elonzh.hanppie.robot.session.*
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

private class OnboardCameraFixture : RobotCameraService {
    val card = CameraStorageStatus(true, 0, 32768, 16000, 5000, 3600, false, false, 0)
    override val state = MutableStateFlow(CameraState(100, timeSynced = true, storage = card, workMode = 0))
    var photos = 0
    val recording = mutableListOf<Boolean>()
    var startFailure = false
    override suspend fun takePhoto() { photos++ }
    override suspend fun setRecording(active: Boolean) {
        recording += active
        if (active && startFailure) throw RobotRequestException(RequestProblem.TIMED_OUT)
        state.value = CameraState(100, state.value.revision + 1, true,
            card.copy(recording = active, recordingSeconds = if (active) 42 else 0), 1)
    }
    override suspend fun formatStorage() = error("unused")
    override suspend fun setPhotoSize(size: cn.elonzh.hanppie.robot.media.CameraPhotoSize) = error("unused")
    override suspend fun setTransmissionRate(rate: cn.elonzh.hanppie.robot.media.VideoTransmissionRate) = error("unused")
    override suspend fun switchCamera(index: Int) = error("unused")
    override suspend fun requestKeyFrame() = error("unused")
    override suspend fun setZoom(hundredths: Int, smooth: Boolean) = error("unused")
    override suspend fun getAntiFlicker(): Int = error("unused")
    override suspend fun setAntiFlicker(mode: AntiFlicker): Int = error("unused")
    override suspend fun getDate(): CameraDate? = error("unused")
    override suspend fun syncClock(): CameraDate = error("unused")
}

class OnboardCaptureUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun cockpitUsesAnExplicitSdDestinationAndKeepsDeviceRecordingVisibleAfterClosingThePanel() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val camera = OnboardCameraFixture()
        val localMedia = RemoteMediaController()
        var albumOpens = 0
        model.state.value = model.state.value.copy(connected = true)
        model.robotCamera.attach(camera)
        val controller = object : ConsoleController by model {
            override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) {}
        }
        try {
            rule.setContent { TestWorkbenchTheme { RemotePage(controller, videoContent = {}, mediaControls = localMedia, manageSession = false, onAlbum = { albumOpens++ }) } }
            rule.onNodeWithTag("onboard-capture-open").performClick()
            rule.onNodeWithText("照片和视频保存在机器人 SD 卡。").assertIsDisplayed()
            rule.onNodeWithTag("onboard-record-stop").assertIsNotEnabled()
            rule.onNodeWithTag("onboard-photo").performClick()
            result("拍照请求已确认")
            rule.onNodeWithTag("onboard-record-start").performClick()
            result("已确认机器人开始录像")
            rule.onNodeWithTag("onboard-photo").assertIsNotEnabled()
            rule.onNodeWithTag("onboard-record-start").assertIsNotEnabled()
            rule.onNodeWithTag("onboard-record-stop").assertIsEnabled()
            rule.onNodeWithTag("onboard-capture-close").performScrollTo().performClick()
            rule.onNodeWithTag("onboard-capture-open").assertTextEquals("SD · 42 秒").performClick()
            rule.onNodeWithTag("onboard-record-stop").performScrollTo().performClick()
            result("已确认机器人停止录像")
            rule.onNodeWithTag("onboard-album-open").performScrollTo().performClick()
            rule.onNodeWithTag("onboard-photo").assertDoesNotExist()
            rule.runOnIdle {
                assertEquals(1, albumOpens)
                assertEquals(1, camera.photos); assertEquals(listOf(true, false), camera.recording)
                assertEquals(RemoteMediaRequests(), localMedia.requests.value)
            }
        } finally { model.close() }
    }

    @Test fun unknownRecordingOutcomeAllowsExplicitStopAndDisconnectClosesTheOldPanel() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val camera = OnboardCameraFixture().apply { startFailure = true }
        model.state.value = model.state.value.copy(connected = true)
        model.robotCamera.attach(camera)
        val controller = object : ConsoleController by model {
            override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) {}
        }
        try {
            rule.setContent { TestWorkbenchTheme { RemotePage(controller, videoContent = {}, manageSession = false) } }
            rule.onNodeWithTag("onboard-capture-open").performClick()
            rule.onNodeWithTag("onboard-record-start").performClick()
            result("拍录结果未确认，请检查当前状态；需要时停止录像。")
            rule.runOnIdle { camera.state.value = CameraState() }
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("onboard-capture-status") and hasText("等待存储卡状态…")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("onboard-photo").assertIsNotEnabled()
            rule.onNodeWithTag("onboard-record-start").assertIsNotEnabled()
            rule.onNodeWithTag("onboard-record-stop").performScrollTo().assertIsEnabled().performClick()
            result("已确认机器人停止录像")
            rule.runOnIdle { model.robotCamera.clear(); model.state.value = model.state.value.copy(connected = false) }
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("onboard-photo")).fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("onboard-capture-open").assertIsNotEnabled()
            rule.runOnIdle { model.robotCamera.attach(camera); model.state.value = model.state.value.copy(connected = true) }
            rule.onNodeWithTag("onboard-capture-open").performClick()
            rule.onNodeWithTag("onboard-capture-result").assertDoesNotExist()
            rule.runOnIdle { assertEquals(listOf(true, false), camera.recording) }
        } finally { model.close() }
    }

    private fun result(text: String) {
        rule.waitUntil(3_000) {
            rule.onAllNodes(hasTestTag("onboard-capture-result") and hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("onboard-capture-result").performScrollTo().assertTextEquals(text)
    }

    @Test fun openingCaptureClearsHeldDrivingAndClosingDoesNotResumeIt() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        model.state.value = model.state.value.copy(connected = true)
        model.remoteEnabled.value = true
        model.robotCamera.attach(OnboardCameraFixture())
        var forward = 0.0
        val controller = object : ConsoleController by model {
            override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) { forward = x }
        }
        try {
            rule.setContent { TestWorkbenchTheme { RemotePage(controller, videoContent = {}, manageSession = false) } }
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.W) }
            rule.waitUntil(3_000) { forward > 0 }
            rule.onNodeWithTag("onboard-capture-open").performClick()
            rule.waitUntil(3_000) { forward == 0.0 }
            rule.onNodeWithTag("onboard-capture-close").performScrollTo().performClick()
            rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.W) }
            rule.runOnIdle { assertEquals(0.0, forward) }
        } finally { model.close() }
    }
}

@OptIn(ExperimentalTestApi::class)
class OnboardCaptureLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun chineseLargeText() = layout(844, 390, "zh", 1.4f)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)

    private fun layout(width: Int, height: Int, language: String = "zh", fontScale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel()
            val camera = OnboardCameraFixture()
            model.state.value = model.state.value.copy(connected = true)
            model.remoteEnabled.value = true
            model.robotCamera.attach(camera); model.cameraZoom.attach(camera)
            val controller = object : ConsoleController by model {
                override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) {}
            }
            try {
                setContent { TestWorkbenchTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        LaunchedEffect(Unit) {
                            while (true) { model.videoFrames.publish(null, ImageBitmap(1280, 720)); delay(50) }
                        }
                        RemotePage(controller, videoContent = { Box(Modifier.fillMaxSize().background(HanppieDesignTokens.RemoteHudSurface)) }, manageSession = false, onAlbum = {})
                    }
                } }
                onNodeWithTag("onboard-capture-open").assertIsDisplayed().assertIsEnabled()
                val button = onNodeWithTag("onboard-capture-open").fetchSemanticsNode().boundsInRoot
                assertTrue(button.height >= 48)
                val tracking = onNodeWithTag("tracking-start").fetchSemanticsNode().boundsInRoot
                val touch = onNodeWithTag("remote-touch-left").fetchSemanticsNode().boundsInRoot
                assertTrue(button.right <= tracking.left && button.bottom <= touch.top)
                snapshot("sd-capture-$width-$height-$language-$fontScale", onNodeWithTag("remote-surface"))
                onNodeWithTag("onboard-capture-open").performClick()
                onNodeWithTag("onboard-photo").performScrollTo().assertIsDisplayed()
                onNodeWithTag("onboard-record-start").assertIsDisplayed()
                onNodeWithTag("onboard-record-stop").performScrollTo().assertIsDisplayed()
                onNodeWithTag("onboard-album-open").performScrollTo().assertIsDisplayed()
                onNodeWithTag("onboard-capture-close").performScrollTo().assertIsDisplayed()
                snapshot("sd-capture-panel-$width-$height-$language-$fontScale",
                    onNode(isRoot() and hasAnyDescendant(hasTestTag("onboard-photo"))))
            } finally { model.close(); Localization.initialize("zh", null) }
        }

    private fun snapshot(name: String, node: SemanticsNodeInteraction) {
        val image = node.captureToImage()
        val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val file = File("build/reports/ui/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(out, "png", file)
    }
}
