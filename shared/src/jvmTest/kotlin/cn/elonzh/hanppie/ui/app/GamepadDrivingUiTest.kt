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
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.elonzh.hanppie.robot.gamepad.*
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.display.displayFixtureVideo
import cn.elonzh.hanppie.ui.robot.remote.RemotePage
import cn.elonzh.hanppie.ui.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Real cockpit sender and shared session; all accessory bytes and robot effects are synthetic. */
@OptIn(ExperimentalTestApi::class)
class GamepadDrivingUiTest {
    @Test fun shortLandscape() = scenario(844, 390, "zh", 1f, false)
    @Test fun mediumDarkLandscape() = scenario(1024, 768, "zh", 1f, true)
    @Test fun desktopLandscape() = scenario(1280, 720, "zh", 1f, false)
    @Test fun largeEnglishLandscape() = scenario(844, 390, "en", 1.4f, false)

    private class Wire : GamepadTransport {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        @Volatile var forward = 1024; @Volatile var lateral = 1024; @Volatile var pitch = 1024; @Volatile var yaw = 1024; @Volatile var buttons = 0
        @Volatile var sending = true
        var flags = 0xd8
        var sequence = 0
        @Synchronized fun send(buttons: Int = this.buttons) {
            val bytes = ByteArray(19); bytes[1] = flags.toByte(); bytes[18] = 88
            for ((offset, value) in listOf(2 to lateral, 4 to forward, 6 to yaw, 8 to pitch, 12 to buttons)) {
                bytes[offset] = value.toByte(); bytes[offset + 1] = (value shr 8).toByte()
            }
            incoming.trySend(Protocol.duss(6, 2, 0, 0x3f, 0x90, bytes, ++sequence))
        }
        override suspend fun read() = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray): Unit = error("Driving must not write a second accessory control protocol")
        override fun close() { incoming.close() }
    }
    private class Connector : GamepadConnector {
        override val available = true
        var wire = Wire()
        override fun accessories() = listOf(GamepadAccessory("fixture", "Synthetic Gamepad"))
        override suspend fun open(id: String): GamepadTransport = wire
    }
    private class InputLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private fun scenario(width: Int, height: Int, language: String, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, language)
            val connector = Connector(); val wire = connector.wire
            val robot = RobotSessionFixture(); val store = MemorySettingsStore()
            robot.cameraState.value = robot.cameraState.value.copy(zoomHundredths = 100)
            robot.zoomRequest = { value -> robot.cameraState.value = robot.cameraState.value.copy(zoomHundredths = value) }
            val model = testConsoleModel(settingsStore = store, robotRuntime = powerRuntime(listOf(robot)), gamepadConnector = connector)
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val video = displayFixtureVideo()
            var settings by mutableStateOf(false)
            lateinit var lifecycle: InputLifecycle
            val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val prefix = "gamepad-drive-$width-$height-$language-${if (dark) "dark" else "light"}"
            fun capture(name: String) {
                waitForIdle(); val image = onRoot().captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/$prefix-$name.png"); file.parentFile.mkdirs(); ImageIO.write(output, "png", file)
            }
            try {
                model.connect("127.0.0.1", "12345678"); model.gamepad.connect("fixture")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy && !model.settingsBusy.value && model.gamepad.state.value.phase == GamepadPhase.CONNECTED }
                fixtureScope.launch { while (isActive) { if (wire.sending) wire.send(); delay(20) } }
                setContent {
                    LaunchedEffect(model) { while (true) { model.videoFrames.publish(model.state.value.connectedAddress, video); delay(100) } }
                    WorkbenchTheme(appearance) {
                        val owner = remember { InputLifecycle() }; SideEffect { lifecycle = owner }
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalDensity provides Density(density.density, scale)) {
                            if (settings) Box(Modifier.fillMaxSize().padding(16.dp)) { SettingsPage(model) }
                            else RemotePage(model, videoContent = { Image(video, "Synthetic validation scene", Modifier.fillMaxSize()) })
                        }
                    }
                }
                runOnIdle { lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
                model.enableRemote(); waitUntil(timeoutMillis = 5000) { model.remoteEnabled.value && model.gamepad.state.value.reportFresh }
                onNodeWithTag("gamepad-drive-toggle").assertIsEnabled().assertIsDisplayed(); capture("ready")
                runOnIdle { wire.forward = 1684; wire.send() }
                waitUntil(timeoutMillis = 5000) { model.gamepad.state.value.report?.forward == 1684 }
                onNodeWithTag("gamepad-drive-toggle").performClick()
                onNodeWithTag("gamepad-drive-status").assertTextContains(if (language == "en") "Center" else "回中", substring = true)
                assertTrue(model.remoteInput.value.all { it == 0.0 }); capture("recenter")
                runOnIdle { wire.forward = 1024; wire.send() }
                waitUntil(timeoutMillis = 5000) { model.gamepad.state.value.report?.forward == 1024 }
                onNodeWithTag("gamepad-drive-toggle").performClick(); capture("active")
                onNodeWithContentDescription(if (language == "en") "Chassis joystick" else "底盘 摇杆").assertIsNotEnabled()
                onNodeWithContentDescription(if (language == "en") "Gimbal joystick" else "云台 摇杆").assertIsNotEnabled()
                val driveBefore = robot.driveCount.get()
                runOnIdle { wire.forward = 1684; wire.lateral = 1684; wire.pitch = 1354; wire.yaw = 694; wire.send() }
                waitUntil(timeoutMillis = 5000) { model.remoteInput.value[0] > 0 && model.remoteInput.value[1] > 0 && model.remoteInput.value[3] > 0 && model.remoteInput.value[4] < 0 }
                assertTrue(robot.driveCount.get() > driveBefore)
                assertTrue(kotlin.math.hypot(model.remoteInput.value[0], model.remoteInput.value[1]) <= .650001)
                assertTrue(model.remoteInput.value.drop(3).all { kotlin.math.abs(it) <= 90 }); capture("motion")
                runOnIdle { wire.flags = 0x98; wire.send() }
                waitUntil(timeoutMillis = 5000) { model.gamepad.state.value.report?.gimbalActive == false && model.remoteInput.value[3] == 0.0 && model.remoteInput.value[4] == 0.0 }
                onNodeWithContentDescription(if (language == "en") "Gimbal joystick" else "云台 摇杆").assertIsEnabled()
                runOnIdle { wire.flags = 0xd8; wire.send() }
                val shots = robot.infraredCount.get()
                runOnIdle { wire.send(0x8000); wire.send(0) }
                waitUntil(timeoutMillis = 5000) { robot.infraredCount.get() == shots + 1 }
                runOnIdle { wire.buttons = 0x80; wire.send() }
                waitUntil(timeoutMillis = 5000) { model.firing.value && robot.infraredCount.get() > shots + 2 }; capture("firing")
                onNodeWithTag("gamepad-drive-toggle").performClick()
                waitUntil(timeoutMillis = 5000) { !model.firing.value && model.remoteInput.value.all { it == 0.0 } }; capture("released")
                val releasedShots = robot.infraredCount.get()
                runOnIdle {
                    wire.buttons = 0; wire.forward = 1024; wire.lateral = 1024; wire.pitch = 1024; wire.yaw = 1024
                    wire.send(0x8000); wire.send(0)
                }
                waitUntil(timeoutMillis = 5000) { model.gamepad.state.value.report?.forward == 1024 }
                waitForIdle(); assertEquals(releasedShots, robot.infraredCount.get())
                onNodeWithTag("gamepad-drive-toggle").performClick()
                runOnIdle { wire.forward = 1684; wire.send() }
                waitUntil(timeoutMillis = 5000) { model.remoteInput.value[0] > 0 }
                runOnIdle { wire.send(0x40); wire.send(0) }
                waitUntil(timeoutMillis = 5000) { model.cameraZoom.state.value.hundredths == 400 }
                runOnIdle { wire.sending = false }
                waitUntil(timeoutMillis = 5000) { !model.gamepad.snapshot().reportFresh && model.remoteInput.value.all { it == 0.0 } }
                onNodeWithTag("gamepad-drive-status").assertTextContains(if (language == "en") "expired" else "过期", substring = true); capture("stale")
                runOnIdle { wire.forward = 1024; wire.lateral = 1024; wire.pitch = 1024; wire.yaw = 1024; wire.sending = true }
                waitUntil(timeoutMillis = 5000) { model.gamepad.snapshot().reportFresh }
                mainClock.advanceTimeBy(100); waitForIdle()
                assertTrue(model.remoteInput.value.all { it == 0.0 })
                onNodeWithTag("gamepad-drive-status").assertTextContains(if (language == "en") "restored" else "已恢复", substring = true); capture("recovered")
                onNodeWithTag("gamepad-drive-toggle").performClick()
                runOnIdle { wire.forward = 1684; wire.send() }
                waitUntil(timeoutMillis = 5000) { model.remoteInput.value[0] > 0 }
                onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
                waitUntil(timeoutMillis = 5000) { model.remoteInput.value.all { it == 0.0 } }; assertTrue(model.remoteEnabled.value)
                runOnIdle { wire.forward = 1024; wire.send() }
                waitUntil(timeoutMillis = 5000) { model.gamepad.state.value.report?.forward == 1024 }
                onNodeWithTag("gamepad-drive-toggle").performClick()
                runOnIdle { wire.forward = 1684; wire.send() }
                waitUntil(timeoutMillis = 5000) { model.remoteInput.value[0] > 0 }
                onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.W); keyUp(Key.W) }
                waitUntil(timeoutMillis = 5000) { model.remoteInput.value.all { it == 0.0 } }
                onNodeWithTag("gamepad-drive-toggle").assertTextContains(if (language == "en") "Enable" else "启用", substring = true)
                runOnIdle { wire.forward = 1024; wire.send() }
                waitUntil(timeoutMillis = 5000) { model.gamepad.state.value.report?.forward == 1024 }
                onNodeWithTag("gamepad-drive-toggle").performClick()
                runOnIdle {
                    lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                    assertTrue(model.remoteInput.value.all { it == 0.0 }); assertFalse(model.firing.value)
                }
                runOnIdle { lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
                runOnIdle { wire.forward = 1684; wire.send() }
                waitForIdle(); assertTrue(model.remoteInput.value.all { it == 0.0 })
                runOnIdle { settings = true }
                onNodeWithTag("settings-category-control").performScrollTo().performClick()
                onNodeWithTag("gamepad-response-lateral-gain").performScrollTo().assertIsDisplayed(); capture("settings-gain")
                onNodeWithTag("gamepad-response-yaw-exponent").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(250f) }
                assertEquals(2.5, model.controlSettings.value.gamepadInput.yaw.exponent)
                onNodeWithTag("gamepad-response-pitch-deadzone").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(25f) }
                assertEquals(1.0 / 3, model.controlSettings.value.gamepadInput.pitch.deadZone)
                capture("settings-curve")
                onNodeWithTag("settings-save").performClick()
                waitUntil(timeoutMillis = 5000) { !model.settingsBusy.value && runBlocking { store.load() }.control.gamepadInput == model.controlSettings.value.gamepadInput }
                onNodeWithTag("gamepad-response-defaults").performScrollTo().performClick(); assertEquals(GamepadInputSettings(), model.controlSettings.value.gamepadInput)
                model.setForeground(false); assertEquals(GamepadPhase.DISCONNECTED, model.gamepad.state.value.phase)
                model.setForeground(true); waitForIdle(); assertEquals(GamepadPhase.DISCONNECTED, model.gamepad.state.value.phase)
            } finally { fixtureScope.cancel(); model.close() }
        }
}
