package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import cn.elonzh.hanppie.robot.tracking.*
import cn.elonzh.hanppie.robot.vision.*
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

class VisionUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun existingRemoteEntryStartsModesAndStaleVideoStopsWithoutResuming() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val vision = MutableStateFlow(VisionState())
        val starts = mutableListOf<VisionMode>()
        var stops = 0
        var publish = true
        val controller = object : ConsoleController by model {
            override val vision = vision
            override fun startVision(mode: VisionMode) { starts += mode; vision.value = VisionState(VisionPhase.ACTIVE, mode, mask = mode.mask, enableMayBeActive = true) }
            override fun stopVision() { stops++; vision.value = VisionState() }
            override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) = Unit
        }
        try {
            model.remoteEnabled.value = true
            rule.setContent { TestWorkbenchTheme {
                LaunchedEffect(Unit) { val frame = ImageBitmap(1280, 720); while (true) { if (publish) model.videoFrames.publish(null, frame); delay(50) } }
                RemotePage(controller, videoContent = {}, manageSession = false)
            } }
            rule.waitUntil(3_000) { rule.onAllNodesWithTag("vision-tools").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("vision-tools").performClick()
            rule.onNodeWithTag("vision-start-aim").performClick()
            assertEquals(listOf(VisionMode.ROBOT_AIM), starts)
            rule.onNodeWithTag("tracking-start").assertDoesNotExist()
            rule.onNodeWithTag("vision-stop").performClick()
            rule.onNodeWithTag("vision-tools").performClick()
            rule.onNodeWithTag("vision-start-markers").performClick()
            assertEquals(VisionMode.MARKERS, starts.last())
            publish = false
            rule.waitUntil(4_000) { stops >= 2 && vision.value.phase == VisionPhase.OFF }
            val count = starts.size
            publish = true
            rule.waitForIdle()
            assertEquals(count, starts.size)
        } finally { model.close() }
    }

    @Test fun uncertainStopOffersOnlyStopRetryAndSafeErrorCanBeDismissed() {
        Localization.initialize("zh", null)
        val state = MutableStateFlow(VisionState(VisionPhase.ERROR, VisionMode.ROBOT_AIM,
            problem = VisionProblem.STOP_UNCONFIRMED, responseCode = 224, enableMayBeActive = true))
        var stops = 0
        rule.setContent { TestWorkbenchTheme {
            val value by state.collectAsState()
            VisionModeControls(value, TrackingState(), false, Modifier, {}, { stops++ }, {}, {}, {})
        } }
        rule.onNodeWithTag("vision-retry").assertDoesNotExist()
        rule.onNodeWithTag("vision-stop").performClick(); assertEquals(1, stops)
        rule.onNodeWithTag("vision-show-details").performClick()
        rule.onNodeWithTag("vision-details").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(problem = VisionProblem.RESOURCE_BUSY, enableMayBeActive = false) }
        rule.onNodeWithTag("vision-retry").assertIsNotEnabled()
        rule.onNodeWithText("关闭").performClick(); assertEquals(2, stops)
    }

    @Test fun markersUseTheirOwnGeometryAndIdentityInsteadOfTrackingDrawCodes() {
        val rect = VisionRect(.5f, .5f, .2f, .4f, 3)
        val viewport = VideoViewport.fit(1000f, 1000f, 1280, 720)
        val bounds = viewport.bounds(rect)
        assertEquals(200f, bounds.width)
        assertEquals(.4f * viewport.height, bounds.height)
        assertEquals(500f, bounds.center.y)
        Localization.initialize("zh", null)
        rule.setContent { TestWorkbenchTheme {
            VisionTargets(VisionState(VisionPhase.ACTIVE, VisionMode.MARKERS,
                rectangles = listOf(rect.copy(info = 0x0064002d))), 1280, 720)
        } }
        rule.onNodeWithText("标签 Z · 100 cm").assertIsDisplayed()
        rule.onNodeWithTag("vision-marker-0").assertHasNoClickAction()
    }
}

@OptIn(ExperimentalTestApi::class)
class VisionLayoutTest {
    @Test fun phoneLandscape() = capture(844, 390)
    @Test fun tabletLandscape() = capture(1024, 600)
    @Test fun desktopLandscape() = capture(1280, 720)
    @Test fun largeEnglish() = capture(844, 390, "en", 1.4f)

    private fun capture(width: Int, height: Int, language: String = "zh", fontScale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel()
            val vision = MutableStateFlow(VisionState())
            val controller = object : ConsoleController by model {
                override val vision = vision
                override fun stopVision() = Unit
                override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) = Unit
            }
            try {
                model.remoteEnabled.value = true
                setContent { TestWorkbenchTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        LaunchedEffect(Unit) { val frame = ImageBitmap(1280, 720); while (true) { model.videoFrames.publish(null, frame); delay(50) } }
                        Box(Modifier.background(HanppieDesignTokens.RemoteHudSurface)) {
                            RemotePage(controller, videoContent = {}, manageSession = false)
                        }
                    }
                } }
                val aim = VisionState(VisionPhase.ACTIVE, VisionMode.ROBOT_AIM, mask = 8, enableMayBeActive = true,
                    rectangles = listOf(VisionRect(.5f, .5f, .22f, .35f, 5)), reportAgeMillis = 0)
                val cases = listOf(
                    "off" to VisionState(), "chooser" to VisionState(),
                    "starting" to aim.copy(phase = VisionPhase.STARTING, rectangles = emptyList()),
                    "aim" to aim,
                    "markers" to VisionState(VisionPhase.ACTIVE, VisionMode.MARKERS, mask = 32, enableMayBeActive = true,
                        rectangles = listOf(VisionRect(.28f, .5f, .17f, .3f, 0x0064002d), VisionRect(.77f, .92f, .18f, .25f, 0x00fa0003))),
                    "stopping" to aim.copy(phase = VisionPhase.STOPPING, rectangles = emptyList()),
                    "uncertain" to aim.copy(phase = VisionPhase.ERROR, rectangles = emptyList(), problem = VisionProblem.STOP_UNCONFIRMED),
                    "busy" to aim.copy(phase = VisionPhase.ERROR, enableMayBeActive = false, rectangles = emptyList(), problem = VisionProblem.RESOURCE_BUSY))
                for ((name, value) in cases) {
                    runOnIdle { vision.value = value }
                    waitForIdle()
                    if (name == "chooser") onNodeWithTag("vision-tools").performClick()
                    val tag = if (value.phase == VisionPhase.OFF) "tracking-controls" else "vision-controls"
                    onNodeWithTag(tag).assertIsDisplayed()
                    val controls = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    val telemetry = onNodeWithTag("remote-telemetry").fetchSemanticsNode().boundsInRoot
                    assertTrue(controls.top >= telemetry.bottom)
                    assertTrue(controls.left >= 0 && controls.right <= width && controls.bottom <= height)
                    if (name == "chooser") onNodeWithTag("vision-start-aim").assertIsDisplayed()
                    if (name == "markers") for (index in 0..1) {
                        onNodeWithTag("vision-marker-$index").assertIsDisplayed()
                        val label = onNodeWithTag("vision-marker-$index").fetchSemanticsNode().boundsInRoot
                        for (hud in listOf("vision-controls", "remote-touch-left", "remote-touch-right", "remote-ammo", "remote-telemetry"))
                            assertFalse(label.overlaps(onNodeWithTag(hud).fetchSemanticsNode().boundsInRoot), "Marker label must stay clear of $hud")
                    }
                    val image = onNodeWithTag("remote-surface").captureToImage()
                    val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                    val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                    buffered.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                    val file = File("build/reports/ui/vision-v02-$width-$height-$language-$name.png"); file.parentFile.mkdirs()
                    ImageIO.write(buffered, "png", file)
                }
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
