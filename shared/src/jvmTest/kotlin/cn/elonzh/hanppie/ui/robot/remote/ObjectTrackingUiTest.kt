package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.tracking.*
import cn.elonzh.hanppie.robot.vision.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class ObjectTrackingUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun videoFitCoordinatesRejectLetterboxes() {
        val viewport = VideoViewport.forImage(1000f, 1000f, 1280, 720)
        assertNull(viewport.point(500f, 50f))
        assertEquals(.5f to .5f, viewport.point(500f, 500f))
        assertEquals(0f to 0f, viewport.point(viewport.left, viewport.top))
        val pillarbox = VideoViewport.forImage(1600f, 600f, 1280, 720)
        assertNull(pillarbox.point(10f, 300f))
        assertEquals(.5f to .5f, pillarbox.point(800f, 300f))
    }

    @Test fun targetSelectionConfirmationStopAndStaleVideoHaveDistinctControls() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val tracking = MutableStateFlow(TrackingState())
        val rect = VisionRect(.5f, .5f, .2f, .4f, 2)
        var selected: Pair<Float, Float>? = null
        var stopCount = 0
        var refreshVideo = true
        val controller = object : ConsoleController by model {
            override val tracking = tracking
            override fun startObjectTracking() { tracking.value = TrackingState(TrackingPhase.SEARCHING, candidates = listOf(rect.copy(info = 1))) }
            override fun selectTrackingTarget(x: Float, y: Float) {
                selected = x to y
                tracking.value = TrackingState(TrackingPhase.TARGET_READY, listOf(rect))
            }
            override fun confirmObjectTracking() { tracking.value = TrackingState(TrackingPhase.FOLLOWING, listOf(rect.copy(info = 3))) }
            override fun stopObjectTracking() { stopCount++; tracking.value = TrackingState() }
            override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) = Unit
        }
        try {
            model.remoteEnabled.value = true
            rule.setContent { TestWorkbenchTheme {
                LaunchedEffect(Unit) {
                    val frame = ImageBitmap(1280, 720)
                    while (true) { if (refreshVideo) model.videoFrames.publish(null, frame); delay(50) }
                }
                Box(Modifier.requiredSize(844.dp, 390.dp)) { RemotePage(controller, videoContent = {}, manageSession = false) }
            } }
            rule.waitUntil(3_000) {
                rule.onAllNodes(hasTestTag("tracking-start") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("tracking-start").performClick()
            rule.onNodeWithTag("tracking-confirm").assertDoesNotExist()
            rule.onNodeWithTag("tracking-targets").performTouchInput { click(center) }
            assertEquals(.5f to .5f, selected)
            rule.onNodeWithText("目标已锁定，开始后机器人将自动移动").assertIsDisplayed()
            rule.onNodeWithTag("tracking-start").assertDoesNotExist()
            rule.onNodeWithTag("tracking-confirm").performClick()
            rule.onNodeWithTag("tracking-confirm").assertDoesNotExist()
            rule.onNodeWithContentDescription("红外开火").assertIsNotEnabled()
            rule.onNodeWithTag("remote-crosshair").assertDoesNotExist()
            rule.onNodeWithTag("tracking-stop").performClick()
            rule.onNodeWithTag("tracking-start").assertIsDisplayed()
            val stopsBeforeVideoExpiry = stopCount
            rule.runOnIdle { tracking.value = TrackingState(TrackingPhase.FOLLOWING, listOf(rect.copy(info = 3))); refreshVideo = false }
            rule.waitUntil(3_000) { stopCount > stopsBeforeVideoExpiry }
            rule.onNodeWithTag("tracking-confirm").assertDoesNotExist()
        } finally { model.close() }
    }

    @Test fun targetInfoActionsUseCurrentFramesAndIgnoreNonActionableSpace() {
        Localization.initialize("zh", null)
        val rect = VisionRect(.5f, .5f, .2f, .4f, 1)
        val tracking = MutableStateFlow(TrackingState(TrackingPhase.SEARCHING, candidates = listOf(rect, rect.copy(x = .15f, info = 0))))
        var selected: Pair<Float, Float>? = null
        var confirmed = 0
        var stopped = 0
        rule.setContent { TestWorkbenchTheme {
            val state by tracking.collectAsState()
            ObjectTrackingTargets(state, 1280, 720, true,
                { x, y -> selected = x to y; tracking.value = TrackingState(TrackingPhase.TARGET_READY, listOf(rect.copy(info = 2))) },
                { confirmed++; tracking.value = TrackingState(TrackingPhase.FOLLOWING, listOf(rect.copy(info = 3))) },
                { stopped++ })
        } }
        rule.onNodeWithTag("tracking-targets").performTouchInput { click(Offset(width * .05f, height * .05f)) }
        assertNull(selected)
        rule.onNodeWithTag("tracking-target-0").assertContentDescriptionEquals("选择候选目标 1").assertHasClickAction()
        rule.onNodeWithTag("tracking-target-1").assertDoesNotExist()
        rule.onNodeWithTag("tracking-targets").performTouchInput { click(center) }
        assertEquals(.5f to .5f, selected)
        rule.onNodeWithTag("tracking-targets").performTouchInput { click(center) }
        assertEquals(1, confirmed)
        rule.onNodeWithTag("tracking-targets").performTouchInput {
            val viewport = VideoViewport.forImage(width.toFloat(), height.toFloat(), 1280, 720)
            click(viewport.bounds(rect.copy(info = 3), following = true).center)
        }
        assertEquals(1, stopped)
        rule.runOnIdle { tracking.value = TrackingState(TrackingPhase.SEARCHING, candidates = listOf(rect.copy(info = 0x80000000.toInt()))) }
        rule.onNodeWithTag("tracking-target-0").assertDoesNotExist()
    }

    @Test fun deviceFailuresKeepTheirCodesAndSpecificRecoveryControls() {
        Localization.initialize("zh", null)
        val tracking = MutableStateFlow(TrackingState(TrackingPhase.ERROR, problem = TrackingProblem.DEVICE_ERROR,
            report = VisionReport(6, 3, 4660, emptyList())))
        var retries = 0
        var stops = 0
        rule.setContent { TestWorkbenchTheme {
            val state by tracking.collectAsState()
            ObjectTrackingControls(state, true, Modifier, { retries++ }, {}, { stops++ })
        } }
        rule.onNodeWithText("视觉报告异常，检查目标后重试").assertIsDisplayed()
        rule.onNodeWithText("视觉异常码：4660（类型 6）").assertIsDisplayed()
        rule.onNodeWithTag("tracking-start").performClick(); assertEquals(1, retries)
        rule.runOnIdle { tracking.value = TrackingState(TrackingPhase.ERROR, problem = TrackingProblem.UNSUPPORTED_REPORT,
            report = VisionReport(6, 255, 0, listOf(VisionRect(.5f, .5f, .2f, .4f, -1)))) }
        rule.onNodeWithText("状态 255 · Info -1").assertIsDisplayed()
        rule.runOnIdle { tracking.value = TrackingState(TrackingPhase.ERROR, problem = TrackingProblem.STOP_UNCONFIRMED,
            responseCode = 224, rejectedCommand = 0xa3) }
        rule.onNodeWithText("请求 0a:a3 · 应答码 224").assertIsDisplayed()
        rule.onNodeWithTag("tracking-start").assertDoesNotExist()
        rule.onNodeWithTag("tracking-stop").performClick(); assertEquals(1, stops)
    }

    @Test fun runningTargetUsesAPixelSquareAndAnUpwardQuarterHeightOffset() {
        val viewport = VideoViewport.forImage(1000f, 1000f, 1280, 720)
        val rect = VisionRect(.5f, .5f, .2f, .4f, 3)
        val marker = viewport.bounds(rect, following = true)
        assertEquals(200f, marker.width)
        assertEquals(marker.width, marker.height)
        assertEquals(500f - .1f * viewport.height, marker.center.y)
        val clipped = viewport.bounds(rect.copy(x = 0f, y = 0f), following = true)
        assertTrue(clipped.left >= viewport.left && clipped.top >= viewport.top)
    }

}

@OptIn(ExperimentalTestApi::class)
class ObjectTrackingLayoutTest {
    @Test fun phoneLandscape() = verifyLayout(844, 390)
    @Test fun tabletLandscape() = verifyLayout(1024, 600)
    @Test fun desktopLandscape() = verifyLayout(1280, 720)
    @Test fun largeEnglishText() = verifyLayout(844, 390, "en", 1.4f)

    private fun verifyLayout(width: Int, height: Int, language: String = "zh", fontScale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel()
            val tracking = MutableStateFlow(TrackingState(TrackingPhase.TARGET_READY, listOf(VisionRect(.5f, .52f, .2f, .4f, 2))))
            val controller = object : ConsoleController by model {
                override val tracking = tracking
                override fun stopObjectTracking() = Unit
                override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) = Unit
            }
            try {
                model.remoteEnabled.value = true
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
                val ready = VisionRect(.5f, .52f, .2f, .4f, 2)
                val cases = listOf(
                    "candidate" to TrackingState(TrackingPhase.SEARCHING, candidates = listOf(ready.copy(x = .25f, info = 1), ready.copy(x = .75f, info = 1))),
                    "ready" to TrackingState(TrackingPhase.TARGET_READY, listOf(ready), listOf(ready.copy(info = 1), ready.copy(x = .8f, info = 1))),
                    "following" to TrackingState(TrackingPhase.FOLLOWING, listOf(ready.copy(info = 3))),
                    "device-error" to TrackingState(TrackingPhase.ERROR, problem = TrackingProblem.DEVICE_ERROR, report = VisionReport(6, 3, 4660, emptyList())),
                    "unknown" to TrackingState(TrackingPhase.ERROR, problem = TrackingProblem.UNSUPPORTED_REPORT, report = VisionReport(6, 255, 0, listOf(ready.copy(info = -1)))),
                    "rejected" to TrackingState(TrackingPhase.ERROR, problem = TrackingProblem.REJECTED, responseCode = 224, rejectedCommand = 0xa7),
                    "stop-unconfirmed" to TrackingState(TrackingPhase.ERROR, problem = TrackingProblem.STOP_UNCONFIRMED, responseCode = 224, rejectedCommand = 0xa3))
                for ((name, state) in cases) {
                runOnIdle { tracking.value = state }
                waitForIdle()
                if (state.phase == TrackingPhase.TARGET_READY) onNodeWithTag("tracking-confirm").assertIsDisplayed()
                if (state.blocksManualControl) onNodeWithTag("tracking-stop").assertIsDisplayed()
                else onNodeWithTag("tracking-start").assertIsDisplayed()
                val controls = onNodeWithTag("tracking-controls").fetchSemanticsNode().boundsInRoot
                val heading = onNodeWithTag("remote-telemetry").fetchSemanticsNode().boundsInRoot
                assertTrue(controls.top >= heading.bottom)
                assertTrue(controls.left >= 0 && controls.right <= width && controls.bottom <= height)
                val image = onNodeWithTag("remote-surface").captureToImage()
                assertEquals(width, image.width); assertEquals(height, image.height)
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                buffered.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/tracking-v01-$width-$height-$language-$name.png"); file.parentFile.mkdirs()
                ImageIO.write(buffered, "png", file)
                }
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
