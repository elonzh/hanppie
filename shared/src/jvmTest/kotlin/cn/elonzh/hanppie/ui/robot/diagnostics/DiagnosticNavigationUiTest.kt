package cn.elonzh.hanppie.ui.robot.diagnostics

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
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
import javax.imageio.ImageIO
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DiagnosticNavigationUiTest {
    @Test fun compactLandscape() = scenario(844, 390, "zh", 1f, false)
    @Test fun mediumDarkLandscape() = scenario(1024, 600, "zh", 1f, true)
    @Test fun desktopLandscape() = scenario(1280, 720, "zh", 1f, false)
    @Test fun largeSupportedLanguages() = scenario(844, 390, "en", 1.4f, false)

    private fun scenario(width: Int, height: Int, language: String, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, language)
            val attempts = AtomicInteger()
            val rawFailure = "OFFLINE synthetic EIO / 原始诊断 / détails\n".repeat(35)
            val runtime = object : RobotRuntime {
                override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = error("No QA discovery")
                override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("No QA pairing")
                override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String): Unit = error("No QA pairing")
                override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
                    onLost: (RobotSession, String) -> Unit): RobotSession {
                    attempts.incrementAndGet(); error(rawFailure)
                }
            }
            val model = testConsoleModel(robotRuntime = runtime)
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val rawLog = "OFFLINE QA / à droite / 原始日志"
            val rawPacket = "00 aa 55 / raw packet"
            fun capture(name: String, node: SemanticsNodeInteraction = onRoot()) {
                val bitmap = node.captureToImage(); val pixels = IntArray(bitmap.width * bitmap.height); bitmap.readPixels(pixels)
                val output = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
                val file = File("build/reports/ui/diagnostic-navigation-$width-$height-$language-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(output, "png", file)
            }
            try {
                runOnIdle { model.state.value = model.state.value.copy(logs = listOf(rawLog), frames = listOf(rawPacket), values = listOf("RAW counter" to "42")) }
                setContent { TestWorkbenchTheme(appearance) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        Console(model, remember { mutableStateOf(EditorDocument()) })
                    }
                } }
                onNodeWithTag("connection-status").performClick()
                onNodeWithTag("connection-diagnostics").performClick()
                mainClock.advanceTimeBy(1000); waitForIdle()
                val languages = if (scale > 1f) AppLanguage.entries else listOf(AppLanguage.entries.single { it.preference == language })
                for (selected in languages) {
                    runOnIdle { Localization.select(selected.preference); model.state.value = model.state.value.copy(logs = listOf(rawLog), frames = listOf(rawPacket), values = listOf("RAW counter" to "42")) }
                    val labels = listOf(tr(Res.string.logs), tr(Res.string.telemetry), tr(Res.string.packets_2), tr(Res.string.ftp_files_tab))
                    for ((index, label) in labels.withIndex()) {
                        val tab = onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                        tab.assertHasClickAction().assertHeightIsAtLeast(48.dp).requestFocus()
                        tab.performKeyInput { pressKey(Key.Enter) }
                        tab.assertIsSelected()
                        val layouts = mutableListOf<TextLayoutResult>()
                        onNode(hasText(label) and hasAnyAncestor(hasTestTag("debug-tabs")), useUnmergedTree = true)
                            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        if (selected == languages.first() || index == 0) capture("${selected.preference}-$index")
                        assertTrue(layouts.isNotEmpty())
                        assertTrue((0 until layouts.single().lineCount).none(layouts.single()::isLineEllipsized), "Complete diagnostic tab must not be ellipsized: $label")
                        when (index) {
                            0 -> onNodeWithText(rawLog).assertIsDisplayed()
                            1 -> onNodeWithTag("signal-quality-raw").assertIsDisplayed()
                            2 -> onNodeWithText(rawPacket).assertIsDisplayed()
                            3 -> onNodeWithTag("robot-files-disconnected").assertIsDisplayed()
                        }
                    }
                    onNode(hasText(labels[0]) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
                    onNodeWithTag("manual-connect").performClick()
                    onNodeWithTag("manual-ip").performScrollTo().performTextReplacement("127.0.0.1")
                    onNodeWithTag("manual-app-id").performScrollTo().performTextReplacement("12345678")
                    onNodeWithTag("manual-connect-submit").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    onNodeWithTag("manual-connect-submit").performClick()
                    waitUntil(timeoutMillis = 5000) { attempts.get() > 0 && !model.state.value.busy && model.state.value.error != null }
                    waitUntil(timeoutMillis = 5000) { onAllNodesWithText(tr(Res.string.error_value, rawFailure)).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("manual-ip").performScrollTo().assertTextContains("127.0.0.1")
                    onNodeWithTag("manual-app-id").performScrollTo().assertTextContains("12345678")
                    onNodeWithTag("manual-connect-cancel").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    capture("${selected.preference}-manual-recovery", onNode(isRoot() and hasAnyDescendant(hasTestTag("manual-connection-body"))))
                    onNodeWithTag("manual-connect-cancel").performClick()
                    mainClock.advanceTimeBy(1000); waitForIdle()
                    onNodeWithTag("manual-connection-body").assertDoesNotExist()
                }
                assertEquals(languages.size, attempts.get())
                assertFalse(model.state.value.connected); assertFalse(model.remoteEnabled.value)
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
