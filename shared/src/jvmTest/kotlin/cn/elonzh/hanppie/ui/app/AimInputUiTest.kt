package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.display.displayFixtureVideo
import cn.elonzh.hanppie.ui.robot.remote.RemotePage
import cn.elonzh.hanppie.ui.robot.remote.input.*
import cn.elonzh.hanppie.ui.settings.*
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AimInputUiTest {
    @Test fun phoneLandscape() = scenario(844, 390, "zh", 1f, false)
    @Test fun mediumDarkLandscape() = scenario(1024, 768, "zh", 1f, true)
    @Test fun desktopLandscape() = scenario(1280, 720, "zh", 1f, false)
    @Test fun largeEnglishLandscape() = scenario(844, 390, "en", 1.4f, false)

    private class Pointer : RelativePointer {
        var closes = 0
        lateinit var motion: (Double, Double) -> Unit
        lateinit var button: (Boolean, Boolean) -> Unit
        lateinit var lost: () -> Unit
        override fun capture(onReady: () -> Unit, onMotion: (Double, Double) -> Unit,
            onButton: (Boolean, Boolean) -> Unit, onLost: () -> Unit): AutoCloseable {
            motion = onMotion; button = onButton; lost = onLost; onReady()
            return AutoCloseable { closes++ }
        }
    }
    private class Gyro : AimGyroscope {
        override val available = true
        var running = false
        var age = 0L
        var stamp = 0L
        override fun start() { running = true }
        override fun read() = if (running) GyroscopeReading(.2, .1, 0.0, stamp++, age, 1) else null
        override fun stop() { running = false }
    }
    private class InputLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun scenario(width: Int, height: Int, language: String, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, language)
            val pointer = Pointer(); val gyro = Gyro(); val factory: () -> AimGyroscope = { gyro }
            val session = RobotSessionFixture(); val store = MemorySettingsStore()
            session.cameraState.value = session.cameraState.value.copy(zoomHundredths = 100)
            session.zoomRequest = { value -> session.cameraState.value = session.cameraState.value.copy(zoomHundredths = value) }
            val model = testConsoleModel(settingsStore = store, robotRuntime = powerRuntime(listOf(session)))
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val video = displayFixtureVideo()
            var settings by mutableStateOf(false)
            lateinit var inputLifecycle: InputLifecycle
            val prefix = "aim-input-$width-$height-$language-${if (dark) "dark" else "light"}"
            fun capture(name: String) {
                waitForIdle()
                val image = onRoot().captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/$prefix-$name.png"); file.parentFile.mkdirs(); ImageIO.write(output, "png", file)
            }
            try {
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy && !model.settingsBusy.value }
                setContent {
                    LaunchedEffect(model) { while (true) { model.videoFrames.publish(model.state.value.connectedAddress, video); delay(100) } }
                    WorkbenchTheme(appearance) {
                        val owner = remember { InputLifecycle() }
                        SideEffect { inputLifecycle = owner }
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalRelativePointer provides pointer, LocalAimGyroscope provides factory,
                            LocalLifecycleOwner provides owner,
                            LocalDensity provides Density(density.density, scale)) {
                            if (settings) Box(Modifier.fillMaxSize().padding(16.dp)) { SettingsPage(model) }
                            else RemotePage(model, videoContent = { Image(video, "Synthetic validation scene", Modifier.fillMaxSize()) })
                        }
                    }
                }
                runOnIdle { inputLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
                model.enableRemote()
                waitUntil(timeoutMillis = 5000) { model.remoteEnabled.value }
                onNodeWithTag("aim-mouse-toggle").assertIsEnabled().assertIsDisplayed()
                onNodeWithTag("aim-gyro-toggle").assertIsEnabled().assertIsDisplayed()
                capture("idle")
                onNodeWithTag("aim-mouse-toggle").performClick()
                runOnIdle { assertEquals(0, pointer.closes) }
                onNodeWithTag("aim-input-status").assertIsDisplayed()
                onNodeWithTag("aim-mouse-toggle").assertDoesNotExist()
                onNodeWithTag("aim-gyro-toggle").assertDoesNotExist()
                runOnIdle { pointer.motion(10.0, -5.0) }
                waitUntil(timeoutMillis = 5000) { model.remoteInput.value[3] > 0 && model.remoteInput.value[4] > 0 }
                waitUntil(timeoutMillis = 5000) { model.remoteInput.value.all { it == 0.0 } }
                val before = session.infraredCount.get()
                runOnIdle { pointer.button(true, true); pointer.button(true, false) }
                waitUntil(timeoutMillis = 5000) { session.infraredCount.get() == before + 1 }
                runOnIdle { pointer.button(false, true); pointer.button(false, false) }
                waitUntil(timeoutMillis = 5000) { model.cameraZoom.state.value.hundredths == 400 && !model.cameraZoom.state.value.busy }
                runOnIdle { pointer.button(true, true) }
                waitUntil(timeoutMillis = 5000) { model.firing.value && session.infraredCount.get() > before + 2 }
                capture("mouse")
                onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
                waitUntil(timeoutMillis = 5000) { pointer.closes == 1 }
                waitUntil(timeoutMillis = 5000) { !model.firing.value }
                assertTrue(model.remoteEnabled.value)
                val stoppedShots = session.infraredCount.get()
                runOnIdle { pointer.motion(99.0, 99.0); pointer.button(true, true) }
                waitForIdle(); assertTrue(model.remoteInput.value.all { it == 0.0 })
                assertEquals(stoppedShots, session.infraredCount.get())
                onNodeWithTag("aim-gyro-toggle").performClick()
                waitUntil(timeoutMillis = 5000) { gyro.running && model.remoteInput.value[3] > 0 && model.remoteInput.value[4] > 0 }
                runOnIdle { gyro.age = 251 }
                waitUntil(timeoutMillis = 5000) { !gyro.running && model.remoteInput.value.all { it == 0.0 } }
                capture("stale")
                runOnIdle { gyro.age = 0 }
                waitForIdle(); assertFalse(gyro.running)
                onNodeWithTag("aim-gyro-toggle").performClick()
                waitUntil(timeoutMillis = 5000) { gyro.running }
                runOnIdle { session.vision.value = cn.elonzh.hanppie.robot.vision.VisionState(
                    phase = cn.elonzh.hanppie.robot.vision.VisionPhase.ACTIVE,
                    mode = cn.elonzh.hanppie.robot.vision.VisionMode.ROBOT_AIM, enableMayBeActive = true) }
                waitUntil(timeoutMillis = 5000) { !gyro.running }
                runOnIdle { session.vision.value = cn.elonzh.hanppie.robot.vision.VisionState() }
                onNodeWithTag("aim-gyro-toggle").performClick()
                waitUntil(timeoutMillis = 5000) { gyro.running }
                runOnIdle {
                    inputLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                    assertFalse(gyro.running, "Native sampling stops in the lifecycle callback, before another frame")
                    assertTrue(model.remoteInput.value.all { it == 0.0 })
                    inputLifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                }
                waitForIdle(); assertFalse(gyro.running)
                onNodeWithTag("aim-gyro-toggle").performClick()
                waitUntil(timeoutMillis = 5000) { gyro.running }
                runOnIdle { model.setForeground(false) }
                waitUntil(timeoutMillis = 5000) { !gyro.running && !model.remoteEnabled.value }
                runOnIdle { model.setForeground(true) }
                waitForIdle(); assertFalse(gyro.running)
                runOnIdle { settings = true }
                onNodeWithTag("settings-category-control").performScrollTo().performClick()
                onNodeWithTag("aim-mouse-sensitivity").performScrollTo()
                onNodeWithTag("aim-mouse-sensitivity").performSemanticsAction(SemanticsActions.SetProgress) { it(75f) }
                onNodeWithTag("aim-mouse-direction-selector").performScrollTo().performClick()
                onNodeWithTag("aim-mouse-direction-true").performClick()
                capture("settings-mouse")
                onNodeWithTag("aim-gyro-sensitivity").performScrollTo()
                onNodeWithTag("aim-gyro-sensitivity").performSemanticsAction(SemanticsActions.SetProgress) { it(25f) }
                onNodeWithTag("aim-gyro-direction-selector").performScrollTo().performClick()
                onNodeWithTag("aim-gyro-direction-true").performClick()
                onNodeWithTag("settings-save").performClick()
                waitUntil(timeoutMillis = 5000) { !model.settingsBusy.value }
                assertEquals(AimInputSettings(75, true, 25, true), runBlocking { store.load() }.control.aimInput)
                onNodeWithTag("aim-gyro-direction-selector").performScrollTo()
                capture("settings-gyro")
            } finally { model.close() }
        }
}
