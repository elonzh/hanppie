package cn.elonzh.hanppie.ui.robot.device

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.*
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import cn.elonzh.hanppie.ui.settings.*
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ConnectionFeedbackUiTest {
    @Test fun compactLandscape() = feedback(844, 390)
    @Test fun tabletDark() = feedback(1024, 600, dark = true)
    @Test fun desktopLandscape() = feedback(1280, 720)
    @Test fun largeSupportedLanguages() = feedback(844, 390, scale = 1.4f)

    private fun feedback(width: Int, height: Int, scale: Float = 1f, dark: Boolean = false) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize("en", "en")
            val preparations = AtomicInteger()
            val opens = AtomicInteger()
            val session = RobotSessionFixture()
            val rawFailure = "OFFLINE connection EIO / 原始诊断 / détails\n".repeat(25)
            var reject = true
            var opened: RobotTarget? = null
            val runtime = object : RobotRuntime {
                override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = error("No QA discovery")
                override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("No QA pairing")
                override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String): Unit = error("No QA pairing")
                override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
                    onLost: (RobotSession, String) -> Unit): RobotSession {
                    opens.incrementAndGet(); opened = target
                    if (reject) error(rawFailure)
                    return session
                }
            }
            val model = testConsoleModel(robotRuntime = runtime, prepareNetwork = { preparations.incrementAndGet() })
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            fun capture(name: String) {
                val bitmap = onNode(isRoot() and hasAnyDescendant(hasTestTag("manual-connection-body"))).captureToImage()
                val pixels = IntArray(bitmap.width * bitmap.height); bitmap.readPixels(pixels)
                val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
                image.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
                val output = File("build/reports/ui/connection-feedback-$width-$height-$scale-$name.png")
                output.parentFile.mkdirs(); ImageIO.write(image, "png", output)
            }
            fun submit() {
                onNodeWithTag("manual-connect-submit").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
                waitUntil(timeoutMillis = 5000) { !model.state.value.busy && model.state.value.error != null }
            }
            val languages = AppLanguage.entries
            try {
                setContent { TestWorkbenchTheme(appearance) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        Console(model, remember { mutableStateOf(EditorDocument()) })
                    }
                } }
                onNodeWithTag("connection-status").performClick()
                onNodeWithTag("connection-diagnostics").performClick()
                onNodeWithTag("manual-connect").performClick()
                submit()
                capture("baseline-ip")
                val addressFeedback = assertNotNull(model.state.value.error)
                for (language in languages) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithText(tr(Res.string.specify_a_robot_ipv4_address)).performScrollTo().assertIsDisplayed()
                    assertSame(addressFeedback, model.state.value.error)
                    capture("${language.preference}-ip")
                }
                assertEquals(0, preparations.get()); assertEquals(0, opens.get())
                onNodeWithTag("manual-ip").performScrollTo().performTextReplacement(" 127.0.0.1 ")
                onNodeWithTag("manual-app-id").performScrollTo().performTextReplacement("bad")
                submit()
                val idFeedback = assertNotNull(model.state.value.error)
                for (language in languages) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithText(tr(Res.string.appid_must_contain_8_hex_characters)).performScrollTo().assertIsDisplayed()
                    assertSame(idFeedback, model.state.value.error)
                    capture("${language.preference}-appid")
                }
                assertEquals(0, preparations.get()); assertEquals(0, opens.get())
                onNodeWithTag("manual-app-id").performScrollTo().performTextReplacement(" AbCd1234 ")
                submit()
                val transportFeedback = assertNotNull(model.state.value.error)
                for (language in languages) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithText(tr(Res.string.error_value, rawFailure)).performScrollTo().assertIsDisplayed()
                    assertSame(transportFeedback, model.state.value.error)
                    onNodeWithTag("manual-connect-cancel").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    capture("${language.preference}-raw")
                }
                assertEquals(1, preparations.get()); assertEquals(1, opens.get())
                assertEquals("127.0.0.1", opened?.ip); assertEquals("AbCd1234", opened?.appId)
                onNodeWithTag("manual-ip").performScrollTo().assertTextContains(" 127.0.0.1 ")
                onNodeWithTag("manual-app-id").performScrollTo().assertTextContains(" AbCd1234 ")
                runOnIdle { reject = false }
                onNodeWithTag("manual-connect-submit").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                assertNull(model.state.value.error)
                assertEquals(2, preparations.get()); assertEquals(2, opens.get())
                assertFalse(model.remoteEnabled.value)
                waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("manual-connection-body").fetchSemanticsNodes().isEmpty() }
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
