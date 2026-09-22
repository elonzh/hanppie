package cn.elonzh.hanppie.ui.robot.scene

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.device.DevicePage
import cn.elonzh.hanppie.ui.settings.AppearanceController
import cn.elonzh.hanppie.ui.settings.AppearanceSettings
import cn.elonzh.hanppie.ui.settings.NightMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

/** Opt-in real GPU tests: layout suites substitute only the GPU surface, never state mapping. */
@OptIn(ExperimentalTestApi::class)
class RobotSceneUiTest {
    @Before fun requireGpu() { assumeTrue("Run with HANPPIE_GPU_TESTS=1 on a graphical host", System.getenv("HANPPIE_GPU_TESTS") == "1") }

    @Test fun livePoseMovesModelAndCameraResetRestoresView() = runDesktopComposeUiTest(width = 720, height = 560) {
        Localization.initialize("zh", null)
        mainClock.autoAdvance = false
        val state = mutableStateOf(RobotSceneState(RobotAppearance.TURRET, PoseStatus.LIVE))
        setContent { WorkbenchTheme { RobotScene(state.value, Modifier.fillMaxSize()) } }
        awaitReady("示意模型 · 云台姿态更新中")
        settleFrames()
        val baseline = onNodeWithTag("robot-scene-viewport").captureToImage()
        snapshot("robot-neutral", onRoot().captureToImage())
        runOnUiThread { state.value = state.value.copy(yaw = 65f, pitch = 25f) }
        settleFrames()
        var posed = onNodeWithTag("robot-scene-viewport").captureToImage()
        assertTrue(changedPixels(baseline, posed) > 1_000, "Telemetry must visibly move the 3D geometry")
        snapshot("robot-live-pose", onRoot().captureToImage())
        runOnUiThread { state.value = state.value.copy(chassisYaw = 35f) }
        settleFrames()
        val heading = onNodeWithTag("robot-scene-viewport").captureToImage()
        assertTrue(changedPixels(posed, heading) > 1_000, "Chassis telemetry must rotate the complete model")
        runOnUiThread { state.value = state.value.copy(wheelAngles = List(4) { 75f }) }
        settleFrames()
        posed = onNodeWithTag("robot-scene-viewport").captureToImage()
        assertTrue(changedPixels(heading, posed) > 200, "Encoders must rotate wheel geometry independently")
        onNodeWithTag("robot-scene-viewport").performTouchInput { swipe(center.copy(x = 100f), center.copy(x = 650f)) }
        settleFrames()
        val orbited = onNodeWithTag("robot-scene-viewport").captureToImage()
        snapshot("robot-orbited", onRoot().captureToImage())
        assertTrue(changedPixels(posed, orbited) > 1_000)
        onNodeWithTag("robot-scene-viewport").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        onNodeWithTag("robot-scene-viewport").performKeyInput { repeat(3) { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) } }
        settleFrames()
        snapshot("workshop-low-angle", onRoot().captureToImage())
        onNodeWithTag("robot-scene-viewport").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.MoveHome) }
        settleFrames()
        val reset = onNodeWithTag("robot-scene-viewport").captureToImage()
        assertTrue(changedPixels(posed, reset) < changedPixels(posed, orbited) / 10, "Reset must restore camera, keeping robot pose")
        runOnUiThread { state.value = state.value.copy(poseStatus = PoseStatus.STALE) }
        settleFrames()
        onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "最近云台姿态 · 更新已暂停")).assertIsDisplayed()
        runOnUiThread { state.value = RobotSceneState(RobotAppearance.ARM, PoseStatus.STATIC) }
        awaitReady("外观示意 · 暂无关节姿态")
        settleFrames()
        snapshot("robot-arm", onRoot().captureToImage())
        runOnUiThread { state.value = RobotSceneState(RobotAppearance.CHASSIS, PoseStatus.STATIC) }
        awaitReady("外观示意 · 暂无关节姿态")
        settleFrames()
        snapshot("robot-chassis", onRoot().captureToImage())
    }

    @Test fun cameraJourneyRendersOrbitApproachAndOnboardView() = runDesktopComposeUiTest(width = 1040, height = 700) {
        Localization.initialize("zh", null)
        mainClock.autoAdvance = false
        val travel = mutableFloatStateOf(0f)
        setContent { WorkbenchTheme {
            CompositionLocalProvider(LocalSceneEntryProgress provides travel.floatValue) {
                RobotScene(RobotSceneState(RobotAppearance.TURRET, PoseStatus.PREVIEW), Modifier.fillMaxSize(), .2f)
            }
        } }
        awaitReady("外观预览 · 连接后查看机器人状态")
        settleFrames()
        var before = onNodeWithTag("robot-scene-viewport").captureToImage()
        for ((label, value) in listOf("orbit" to .4f, "approach" to .75f, "onboard" to 1f)) {
            runOnUiThread { travel.floatValue = value }
            settleFrames()
            val after = onNodeWithTag("robot-scene-viewport").captureToImage()
            assertTrue(changedPixels(before, after) > 2_000)
            snapshot("entry-$label", after)
            before = after
        }
    }

    @Test fun desktopLight() = homepage(1120, 630, NightMode.LIGHT, "zh", 1f, "home-desktop-light")
    @Test fun tabletDark() = homepage(1180, 820, NightMode.DARK, "zh", 1f, "home-tablet-dark")
    @Test fun phoneLandscapeLargeEnglish() = homepage(800, 360, NightMode.LIGHT, "en", 1.3f, "home-phone-landscape-large-en")

    private fun homepage(width: Int, height: Int, mode: NightMode, language: String, fontScale: Float, name: String) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            mainClock.autoAdvance = false
            val model = testConsoleModel()
            try {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        WorkbenchTheme(AppearanceController(AppearanceSettings(mode))) {
                            DevicePage(model, model.state.value, width < 720, Modifier.fillMaxSize(), {}, {})
                        }
                    }
                }
                awaitReady(if (language == "zh") "外观预览 · 连接后查看机器人状态" else "Appearance preview · connect to see robot status")
                settleFrames()
                snapshot(name, onRoot().captureToImage())
                onNodeWithTag("auto-connect").assertIsDisplayed()
                onNodeWithTag("connection-guide").assertDoesNotExist()
            } finally { model.close() }
        }

    private fun ComposeUiTest.awaitReady(text: String) {
        waitUntil(timeoutMillis = 30_000) {
            mainClock.advanceTimeByFrame()
            onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)).fetchSemanticsNodes().isNotEmpty()
        }
        // A loaded asset is not evidence that asynchronous GPU readback has presented it.
        // Both light and dark themes need visible model pixels before taking a screenshot.
        waitUntil(timeoutMillis = 30_000) {
            mainClock.advanceTimeByFrame()
            val image = onNodeWithTag("robot-scene-viewport").captureToImage()
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            pixels.count { pixel ->
                val r = (pixel shr 16) and 255
                val g = (pixel shr 8) and 255
                val b = pixel and 255
                // Cyan geometry, not the orange connection action overlaid on a wide viewport.
                g > r + 30 && b > r + 30
            } > 50
        }
    }

    private fun ComposeUiTest.settleFrames() {
        // GPU readback is asynchronous and 1–2 frames behind Compose. Advance and yield to the driver.
        repeat(20) { mainClock.advanceTimeByFrame(); Thread.sleep(10) }
        waitForIdle()
    }

    private fun changedPixels(first: ImageBitmap, second: ImageBitmap): Int {
        val a = IntArray(first.width * first.height)
        val b = IntArray(second.width * second.height)
        first.readPixels(a); second.readPixels(b)
        return a.indices.count { index -> (0..2).any { channel ->
            kotlin.math.abs(((a[index] shr (channel * 8)) and 255) - ((b[index] shr (channel * 8)) and 255)) > 20
        } }
    }

    private fun snapshot(name: String, image: ImageBitmap) {
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        buffered.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val file = File("build/reports/ui/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(buffered, "png", file)
    }
}
