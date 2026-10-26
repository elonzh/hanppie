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

    private class Gyro : AimGyroscope {
        override var available by mutableStateOf(true)
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
            val gyro = Gyro(); val factory: () -> AimGyroscope = { gyro }
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
                        CompositionLocalProvider(LocalAimGyroscope provides factory,
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
                onNodeWithTag("aim-mouse-toggle").assertDoesNotExist()
                onNodeWithTag("aim-gyro-toggle").assertIsEnabled().assertIsDisplayed()
                onNodeWithTag("remote-touch-left").assertIsDisplayed()
                onNodeWithTag("remote-touch-right").assertIsDisplayed()
                capture("idle")
                onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.DirectionUp) }
                waitUntil(timeoutMillis = 5000) { model.remoteInput.value[3] > 0 }
                onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.DirectionUp) }
                waitUntil(timeoutMillis = 5000) { model.remoteInput.value.all { it == 0.0 } }
                onNodeWithTag("aim-gyro-toggle").performClick()
                waitUntil(timeoutMillis = 5000) { gyro.running && model.remoteInput.value[3] > 0 && model.remoteInput.value[4] > 0 }
                capture("gyro")
                onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
                waitUntil(timeoutMillis = 5000) { !gyro.running && model.remoteInput.value.all { it == 0.0 } }
                assertTrue(model.remoteEnabled.value)
                onNodeWithTag("aim-gyro-toggle").performClick()
                waitUntil(timeoutMillis = 5000) { gyro.running }
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
                onNodeWithTag("aim-mouse-sensitivity").assertDoesNotExist()
                onNodeWithTag("aim-mouse-direction-selector").assertDoesNotExist()
                onNodeWithTag("aim-gyro-sensitivity").performScrollTo()
                onNodeWithTag("aim-gyro-sensitivity").performSemanticsAction(SemanticsActions.SetProgress) { it(25f) }
                onNodeWithTag("aim-gyro-direction-selector").performScrollTo().performClick()
                onNodeWithTag("aim-gyro-direction-true").performClick()
                onNodeWithTag("settings-save").performClick()
                waitUntil(timeoutMillis = 5000) { !model.settingsBusy.value }
                assertEquals(AimInputSettings(gyroSensitivity = 25, gyroInvertY = true), runBlocking { store.load() }.control.aimInput)
                onNodeWithTag("aim-gyro-direction-selector").performScrollTo()
                capture("settings-gyro")
                runOnIdle { gyro.available = false }
                onNodeWithTag("aim-gyro-sensitivity").assertDoesNotExist()
                onNodeWithTag("aim-mouse-sensitivity").assertDoesNotExist()
                capture("settings-no-sensor")
                runOnIdle { settings = false }
                model.enableRemote()
                waitUntil(timeoutMillis = 5000) { model.remoteEnabled.value }
                onNodeWithTag("aim-mouse-toggle").assertDoesNotExist()
                onNodeWithTag("aim-gyro-toggle").assertDoesNotExist()
                onNodeWithTag("remote-touch-left").assertIsDisplayed()
                onNodeWithTag("remote-touch-right").assertIsDisplayed()
                capture("no-sensor")

            } finally { model.close() }
        }
}
