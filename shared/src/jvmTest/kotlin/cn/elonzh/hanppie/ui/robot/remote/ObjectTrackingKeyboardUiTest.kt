package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.tracking.*
import cn.elonzh.hanppie.robot.vision.VisionRect
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.*
import cn.elonzh.hanppie.ui.settings.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ObjectTrackingKeyboardUiTest {
    @Test fun compact() = scenario(844, 390, 1f, false)
    @Test fun tabletDark() = scenario(1024, 768, 1f, true)
    @Test fun desktop() = scenario(1280, 720, 1f, false)
    @Test fun large() = scenario(844, 390, 1.4f, true)

    @Test fun disabledOrChangedPhaseRetiresTheHeldEnter() = runDesktopComposeUiTest(width = 844, height = 390) {
        Localization.initialize("en", "en")
        val ready = VisionRect(.5f, .5f, .2f, .3f, 2)
        val state = mutableStateOf(TrackingState(TrackingPhase.TARGET_READY, listOf(ready)))
        val enabled = mutableStateOf(true)
        var confirmed = 0; var stopped = 0
        setContent { TestWorkbenchTheme {
            ObjectTrackingTargets(state.value, 1280, 720, enabled.value, { _, _ -> fail("Not a candidate") }, { confirmed++ }, { stopped++ })
        } }
        fun holdEnter() {
            onNodeWithTag("tracking-target-0").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                .performKeyInput { keyDown(Key.Enter) }
        }
        holdEnter()
        runOnIdle { enabled.value = false }
        onNodeWithTag("tracking-target-0").assertIsNotEnabled().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .performKeyInput { keyUp(Key.Enter) }
        runOnIdle { enabled.value = true }
        holdEnter()
        runOnIdle { state.value = TrackingState(TrackingPhase.FOLLOWING, listOf(ready.copy(info = 3))) }
        onNodeWithTag("tracking-target-0").performKeyInput { keyUp(Key.Enter) }
        runOnIdle { assertEquals(0, stopped); state.value = TrackingState(TrackingPhase.TARGET_READY, listOf(ready)) }
        holdEnter()
        runOnIdle { state.value = TrackingState(TrackingPhase.SELECTING) }
        onNodeWithTag("tracking-target-0").assertDoesNotExist()
        onRoot().performKeyInput { keyUp(Key.Enter) }
        runOnIdle { state.value = TrackingState(TrackingPhase.TARGET_READY, listOf(ready)) }
        onNodeWithTag("tracking-target-0").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        runOnIdle { assertEquals(0, confirmed) }
        onNodeWithTag("tracking-target-0").performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun queuedSemanticActionsDoNotSelectReplacementFramesButCanStillStop() =
        runDesktopComposeUiTest(width = 844, height = 390) {
            Localization.initialize("en", "en")
            val first = VisionRect(.3f, .5f, .14f, .28f, 1)
            val second = first.copy(x = .7f)
            val state = mutableStateOf(TrackingState(TrackingPhase.SEARCHING, candidates = listOf(first)))
            var selections = 0; var stops = 0
            setContent { TestWorkbenchTheme {
                ObjectTrackingTargets(state.value, 1280, 720, true, { _, _ -> selections++ }, {}, { stops++ })
            } }
            val oldSelect = onNodeWithTag("tracking-target-0").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            runOnIdle { state.value = TrackingState(TrackingPhase.SEARCHING, candidates = listOf(second)) }
            onNodeWithTag("tracking-target-0").assertExists()
            runOnIdle { assertFalse(oldSelect()); assertEquals(0, selections) }
            onNodeWithTag("tracking-target-0").performClick()
            runOnIdle { assertEquals(1, selections); state.value = TrackingState(TrackingPhase.FOLLOWING, listOf(first.copy(info = 3))) }
            val stop = onNodeWithTag("tracking-target-0").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            runOnIdle { state.value = TrackingState(TrackingPhase.FOLLOWING, listOf(second.copy(info = 3))) }
            onNodeWithTag("tracking-target-0").assertExists()
            runOnIdle { assertTrue(stop()); assertEquals(1, stops) }
        }

    @Test fun losingFocusRetiresTheHeldEnterAndPointerSelectionStillUsesTheCanvas() =
        runDesktopComposeUiTest(width = 844, height = 390) {
            Localization.initialize("en", "en")
            val ready = VisionRect(.3f, .5f, .14f, .28f, 2)
            val other = ready.copy(x = .7f, info = 1)
            var selected = 0; var confirmed = 0
            setContent { TestWorkbenchTheme {
                ObjectTrackingTargets(TrackingState(TrackingPhase.TARGET_READY, listOf(ready), listOf(other)), 1280, 720, true,
                    { x, y -> assertEquals(other.x to other.y, x to y); selected++ }, { confirmed++ }, {})
            } }
            onNodeWithTag("tracking-target-0").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                .performKeyInput { keyDown(Key.Enter) }
            onNodeWithTag("tracking-target-1").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                .performKeyInput { keyUp(Key.Enter) }
            runOnIdle { assertEquals(0, selected); assertEquals(0, confirmed) }
            onNodeWithTag("tracking-targets").performTouchInput {
                val view = VideoViewport.forImage(width.toFloat(), height.toFloat(), 1280, 720)
                val point = view.bounds(other).center
                click(point)
            }
            runOnIdle { assertEquals(1, selected); assertEquals(0, confirmed) }
        }

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize("en", "en")
            val model = testConsoleModel()
            val first = VisionRect(.3f, .5f, .14f, .28f, 1)
            val second = first.copy(x = .7f)
            val searching = TrackingState(TrackingPhase.SEARCHING, candidates = listOf(first, second))
            val tracking = MutableStateFlow(searching)
            var selections = 0; var confirmations = 0; var stops = 0; var fires = 0
            var selected: Pair<Float, Float>? = null
            val movements = mutableListOf<List<Double>>()
            val controller = object : ConsoleController by model {
                override val tracking = tracking
                override fun selectTrackingTarget(x: Float, y: Float) {
                    selections++; selected = x to y
                    tracking.value = TrackingState(TrackingPhase.SELECTING)
                }
                override fun confirmObjectTracking() {
                    confirmations++; tracking.value = TrackingState(TrackingPhase.FOLLOWING, listOf(second.copy(info = 3)))
                }
                override fun stopObjectTracking() { stops++; tracking.value = TrackingState() }
                override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) {
                    movements += listOf(x, y, z, pitch, yaw)
                }
                override fun startFiring() { fires++ }
            }
            fun picture(name: String) {
                val image = onNodeWithTag("remote-surface").captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val path = File("build/reports/ui/tracking-keyboard-$width-$height-$scale-$name.png")
                path.parentFile.mkdirs(); ImageIO.write(bitmap, "png", path)
            }
            try {
                model.remoteEnabled.value = true
                setContent { TestWorkbenchTheme(AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        LaunchedEffect(Unit) {
                            val frame = ImageBitmap(1280, 720)
                            while (true) { model.videoFrames.publish(null, frame); delay(50) }
                        }
                        RemotePage(controller, videoContent = {}, manageSession = false)
                    }
                } }
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("tracking-stop") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                picture("searching")
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("tracking-target-0") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                val candidate = onNodeWithTag("tracking-target-0")
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assertContentDescriptionEquals(tr(Res.string.tracking_candidate_action, "1"))
                candidate.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                candidate.performKeyInput { pressKey(Key.Tab) }
                onNodeWithTag("tracking-target-1").assertIsFocused()
                picture("focused")
                onNodeWithTag("tracking-target-1").performKeyInput { keyDown(Key.Enter) }
                runOnIdle { assertEquals(0, selections) }
                onNodeWithTag("tracking-target-1").performKeyInput { keyUp(Key.Enter) }
                runOnIdle { assertEquals(1, selections); assertEquals(second.x to second.y, selected); assertEquals(0, confirmations) }
                onNodeWithTag("tracking-target-0").assertDoesNotExist()
                runOnIdle { tracking.value = TrackingState(TrackingPhase.TARGET_READY, listOf(second.copy(info = 2))) }
                onNodeWithTag("tracking-target-0").assertContentDescriptionEquals(tr(Res.string.tracking_start_follow))
                    .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                picture("ready")
                onNodeWithTag("tracking-target-0").performKeyInput { pressKey(Key.Enter) }
                runOnIdle { assertEquals(1, confirmations) }
                onNodeWithTag("tracking-target-0").assertContentDescriptionEquals(tr(Res.string.tracking_stop))
                    .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                picture("following")
                onNodeWithTag("tracking-target-0").performClick()
                runOnIdle { assertEquals(1, stops); assertEquals(0, fires); assertTrue(movements.all { values -> values.all { it == 0.0 } }) }
                onNodeWithTag("tracking-targets").assertDoesNotExist()
                if (scale > 1f) {
                    runOnIdle { tracking.value = searching }
                    for (language in AppLanguage.entries) {
                        runOnIdle { Localization.select(language.preference) }
                        onNodeWithTag("tracking-target-0").assertContentDescriptionEquals(tr(Res.string.tracking_candidate_action, "1"))
                            .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                        picture("focused-${language.preference}")
                    }
                    runOnIdle { assertEquals(1, selections); assertEquals(1, confirmations); assertEquals(1, stops) }
                }
            } finally { model.close(); Localization.initialize("zh", "zh") }
        }
}
