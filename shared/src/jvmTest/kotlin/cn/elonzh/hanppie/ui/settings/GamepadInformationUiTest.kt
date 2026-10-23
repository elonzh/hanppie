package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.gamepad.*
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.*

/** Actual control-settings entry; synthetic accessory bytes, never an OS USB or robot result. */
@OptIn(ExperimentalTestApi::class)
class GamepadInformationUiTest {
    @Test fun shortLandscape() = scenario(844, 390, "zh", 1f, false)
    @Test fun mediumDarkLandscape() = scenario(1024, 768, "zh", 1f, true)
    @Test fun desktopLandscape() = scenario(1280, 720, "zh", 1f, false)
    @Test fun largeEnglishLandscape() = scenario(844, 390, "en", 1.4f, false)

    private class Wire : GamepadTransport {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val requests = CopyOnWriteArrayList<DussFrame>()
        var invalid = false
        var replyGate: CompletableDeferred<Unit>? = null
        override suspend fun read() = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = Protocol.frames(bytes).single(); requests.add(request)
            replyGate?.await()
            val payload = if (invalid) byteArrayOf(0) else if (request.id == 1)
                ByteArray(26).apply { this[22] = 4; this[23] = 3; this[24] = 2; this[25] = 1 }
                else ByteArray(10).apply { this[1] = 1 }
            incoming.send(Protocol.duss(6, 2, 0x80, 0, request.id, payload, request.sequence))
        }
        fun report() { incoming.trySend(Protocol.duss(6, 2, 0, 0x3f, 0x90, ByteArray(19).apply {
            this[1] = 0x18; this[18] = 88
        }, 9)) }
        override fun close() { incoming.close() }
    }
    private class Connector : GamepadConnector {
        override val available = true
        var devices: List<GamepadAccessory> = emptyList()
        var gate: CompletableDeferred<Unit>? = null
        var opened: Wire? = null
        override fun accessories() = devices
        override suspend fun open(id: String): GamepadTransport { gate?.await(); return Wire().also { opened = it } }
    }

    private fun scenario(width: Int, height: Int, language: String, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, language)
            val connector = Connector(); val model = testConsoleModel(gamepadConnector = connector)
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val prefix = "gamepad-info-$width-$height-$language-${if (dark) "dark" else "light"}"
            fun capture(name: String) {
                waitForIdle(); val image = onRoot().captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/$prefix-$name.png"); file.parentFile.mkdirs(); ImageIO.write(output, "png", file)
            }
            try {
                setContent { TestWorkbenchTheme(appearance) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        Console(model, remember { mutableStateOf(EditorDocument()) })
                    }
                } }
                onNodeWithTag("navigate-4").performClick()
                onNodeWithTag("settings-category-control").performScrollTo().performClick()
                onNodeWithTag("gamepad-discover").assertDoesNotExist()
                onNodeWithTag("settings-gamepad-open").performScrollTo().assertIsDisplayed(); capture("settings-entry")
                onNodeWithTag("settings-gamepad-open").performClick()
                onNodeWithTag("gamepad-page").assertIsDisplayed()
                onNodeWithTag("gamepad-back").assertIsDisplayed()
                onNodeWithTag("settings-save").assertDoesNotExist()
                onNodeWithTag("navigate-0").assertDoesNotExist()
                onNodeWithTag("gamepad-empty").performScrollTo().assertIsDisplayed(); capture("empty")
                connector.devices = listOf(GamepadAccessory("fixture", "Synthetic Gamepad"))
                onNodeWithTag("gamepad-discover").performScrollTo().performClick()
                onNodeWithTag("gamepad-connect-fixture").performScrollTo().assertIsDisplayed(); capture("discovered")
                val gate = CompletableDeferred<Unit>(); connector.gate = gate
                onNodeWithTag("gamepad-connect-fixture").performClick()
                waitUntil(timeoutMillis = 5000) { model.gamepad.state.value.phase == GamepadPhase.CONNECTING }
                onNodeWithTag("gamepad-disconnect").performScrollTo().assertIsDisplayed(); capture("opening")
                onNodeWithTag("gamepad-disconnect").performClick()
                assertEquals(GamepadPhase.DISCONNECTED, model.gamepad.state.value.phase)
                connector.gate = null
                onNodeWithTag("gamepad-connect-fixture").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { model.gamepad.state.value.phase == GamepadPhase.CONNECTED }
                val wire = checkNotNull(connector.opened)
                val reply = CompletableDeferred<Unit>(); wire.replyGate = reply
                onNodeWithTag("gamepad-firmware-query").performScrollTo().performClick()
                onNodeWithTag("gamepad-querying").performScrollTo().assertIsDisplayed(); capture("querying")
                onNodeWithTag("gamepad-disconnect").assertIsEnabled()
                reply.complete(Unit); wire.replyGate = null
                waitUntil(timeoutMillis = 5000) { model.gamepad.state.value.firmware == "01.02.03.04" }
                onNodeWithTag("gamepad-activation-query").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { model.gamepad.state.value.activated == true }
                wire.report(); waitUntil(timeoutMillis = 5000) { model.gamepad.state.value.report != null }
                onNodeWithTag("gamepad-phase").performScrollTo().assertIsDisplayed(); capture("connected-actions")
                onNodeWithTag("gamepad-activation").performScrollTo().assertIsDisplayed(); capture("connected-results")
                waitUntil(timeoutMillis = 5000) { !model.gamepad.state.value.reportFresh }
                onNodeWithTag("gamepad-stale").performScrollTo().assertIsDisplayed(); capture("stale")
                wire.invalid = true
                onNodeWithTag("gamepad-firmware-query").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { model.gamepad.state.value.problem == GamepadProblem.INVALID_RESPONSE }
                onNodeWithTag("gamepad-error").performScrollTo().assertIsDisplayed(); capture("error")
                assertEquals(3, wire.requests.size)
                assertTrue(wire.requests.all { it.receiver == 6 && it.set == 0 && it.id in listOf(1, 0x32) })
                assertFalse(model.state.value.connected); assertFalse(model.remoteEnabled.value)
                assertTrue(model.remoteInput.value.all { it == 0.0 })
                model.setForeground(false); assertEquals(GamepadPhase.DISCONNECTED, model.gamepad.state.value.phase)
                model.setForeground(true); waitForIdle(); assertEquals(GamepadPhase.DISCONNECTED, model.gamepad.state.value.phase)
                gate.complete(Unit)
                assertNull(model.gamepad.state.value.firmware)
                onNodeWithTag("gamepad-back").performClick()
                onNodeWithTag("settings-gamepad-open").performScrollTo().assertIsDisplayed()
                onNodeWithTag("gamepad-discover").assertDoesNotExist()
                onNodeWithTag("gamepad-page").assertDoesNotExist()
            } finally { model.close() }
        }
}
