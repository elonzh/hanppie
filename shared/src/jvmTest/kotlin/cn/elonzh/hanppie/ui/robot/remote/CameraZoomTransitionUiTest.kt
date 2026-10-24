package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.elonzh.hanppie.robot.media.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.robot.display.displayFixtureVideo
import cn.elonzh.hanppie.ui.settings.*
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

/** Real cockpit/controller, controlled camera ACK/report boundaries and synthetic, unchanged video. */
@OptIn(ExperimentalTestApi::class)
class CameraZoomTransitionUiTest {
    @Test fun compact() = scenario(844, 390, 1f, false)
    @Test fun tabletDark() = scenario(1024, 768, 1f, true)
    @Test fun desktop() = scenario(1280, 720, 1f, false)
    @Test fun largeNineLanguages() = scenario(844, 390, 1.4f, true, allLanguages = true)

    private class InputLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    }

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean, allLanguages: Boolean = false) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize("zh", "zh")
            val model = testConsoleModel(); val peer = RobotSessionFixture()
            val calls = AtomicInteger(0); val smoothRequests = CopyOnWriteArrayList<Boolean>()
            val finish = CompletableDeferred<Unit>(); val cancelled = (4..6).associateWith { CompletableDeferred<Unit>() }
            val camera = object : RobotCameraService by peer.camera {
                override suspend fun setZoom(hundredths: Int, smooth: Boolean) {
                    val request = calls.incrementAndGet()
                    smoothRequests += smooth
                    when (request) {
                        1 -> { peer.cameraState.value = CameraState(200); finish.await(); peer.cameraState.value = CameraState(hundredths) }
                        2 -> { peer.cameraState.value = CameraState(250); throw RobotRequestException(RequestProblem.REJECTED) }
                        3 -> peer.cameraState.value = CameraState(hundredths)
                        else -> try { peer.cameraState.value = CameraState(200); awaitCancellation() }
                        finally { cancelled.getValue(request).complete(Unit) }
                    }
                }
            }
            model.remoteEnabled.value = true; model.cameraZoom.attach(camera)
            val controller = object : ConsoleController by model {
                override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) {}
            }
            val video = displayFixtureVideo()
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            lateinit var lifecycle: InputLifecycle
            var visible by mutableStateOf(true); var publishing by mutableStateOf(true)
            fun capture(name: String) {
                waitForIdle()
                val surface = if (name.startsWith("error-details"))
                    onNode(isRoot() and hasAnyDescendant(hasTestTag("camera-zoom-error"))) else onNodeWithTag("remote-surface")
                val image = surface.captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/zoom-transition-$width-$height-$scale-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(output, "png", file)
            }
            fun ready() = waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasTestTag("camera-zoom") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            fun fits() {
                val node = onNodeWithTag("camera-zoom").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                val zoom = node.fetchSemanticsNode().boundsInRoot
                assertTrue(zoom.left >= 0 && zoom.right <= width && zoom.top >= 0 && zoom.bottom <= height)
                for (tag in listOf("tracking-controls", "onboard-capture-open", "remote-crosshair"))
                    assertFalse(zoom.overlaps(onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot), tag)
            }
            try {
                setContent {
                    LaunchedEffect(publishing) { while (publishing) { model.videoFrames.publish(null, video); delay(50) } }
                    TestWorkbenchTheme(appearance) {
                        val owner = remember { InputLifecycle() }
                        SideEffect { lifecycle = owner }
                        CompositionLocalProvider(LocalDensity provides Density(1f, scale), LocalLifecycleOwner provides owner) {
                            if (visible) RemotePage(controller, manageSession = false,
                                videoContent = { Image(video, "Synthetic transition scene", Modifier.fillMaxSize()) })
                        }
                    }
                }
                ready(); capture("idle"); fits()
                onNodeWithTag("camera-zoom").performClick()
                waitUntil(timeoutMillis = 5_000) { model.cameraZoom.state.value.busy && model.cameraZoom.state.value.hundredths == 200 }
                onNodeWithTag("camera-zoom").assertIsNotEnabled(); onNodeWithTag("tracking-start").assertIsNotEnabled()
                capture("transition"); fits()
                if (allLanguages) for (language in listOf("zh", "zh-Hant", "en", "ja", "de", "es", "fr", "ko", "ru")) {
                    runOnIdle { Localization.initialize(language, language) }; fits(); capture("transition-$language")
                }
                finish.complete(Unit); ready(); assertEquals(400, model.cameraZoom.state.value.hundredths)
                capture("four"); onNodeWithTag("camera-zoom").performClick()
                waitUntil(timeoutMillis = 5_000) { model.cameraZoom.state.value.error != null && !model.cameraZoom.state.value.busy }
                assertEquals(250, model.cameraZoom.state.value.hundredths)
                fits(); capture("partial-error")
                onNodeWithContentDescription(tr(Res.string.camera_zoom_error_details)).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
                onNodeWithTag("camera-zoom-error").assertIsDisplayed(); capture("error-details")
                if (allLanguages) for (language in listOf("zh", "zh-Hant", "en", "ja", "de", "es", "fr", "ko", "ru")) {
                    runOnIdle { Localization.initialize(language, language) }
                    onNodeWithTag("camera-zoom-error").assertTextEquals(checkNotNull(model.cameraZoom.state.value.error).resolve())
                    onNodeWithTag("camera-zoom-error-close").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    capture("error-details-$language")
                }
                onNodeWithTag("camera-zoom-error-close").performClick()
                onNodeWithTag("camera-zoom").performClick(); ready()
                assertEquals(100, model.cameraZoom.state.value.hundredths); onNodeWithTag("camera-zoom-error").assertDoesNotExist()
                capture("recovered-one"); onNodeWithTag("camera-zoom").performClick()
                waitUntil(timeoutMillis = 5_000) { calls.get() == 4 && model.cameraZoom.state.value.busy }
                runOnIdle { publishing = false }; waitForIdle()
                runOnIdle { model.videoFrames.publish("other connection", video) }
                waitUntil(timeoutMillis = 5_000) { cancelled.getValue(4).isCompleted && !model.cameraZoom.state.value.busy }
                onNodeWithTag("camera-zoom").assertIsNotEnabled(); capture("video-lost")
                runOnIdle { publishing = true }; ready(); assertEquals(4, calls.get()); capture("video-restored")
                onNodeWithTag("camera-zoom").performClick()
                waitUntil(timeoutMillis = 5_000) { calls.get() == 5 && model.cameraZoom.state.value.busy }
                runOnIdle { lifecycle.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
                waitUntil(timeoutMillis = 5_000) { cancelled.getValue(5).isCompleted && !model.cameraZoom.state.value.busy }
                runOnIdle { lifecycle.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }; ready(); assertEquals(5, calls.get())
                onNodeWithTag("camera-zoom").performClick()
                waitUntil(timeoutMillis = 5_000) { calls.get() == 6 && model.cameraZoom.state.value.busy }
                runOnIdle { visible = false }
                waitUntil(timeoutMillis = 5_000) { cancelled.getValue(6).isCompleted && !model.cameraZoom.state.value.busy }
                runOnIdle { visible = true }; ready(); assertEquals(6, calls.get()); capture("remounted")
                assertEquals(List(6) { true }, smoothRequests.toList())
                assertNull(model.cameraZoom.state.value.error)
            } finally { finish.complete(Unit); model.close() }
        }
}
