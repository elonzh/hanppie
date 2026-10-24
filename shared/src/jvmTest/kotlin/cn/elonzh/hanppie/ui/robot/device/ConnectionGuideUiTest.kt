package cn.elonzh.hanppie.ui.robot.device

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ConnectionGuideUiTest {
    @Test fun compactLandscape() = scenario(844, 390, "zh", 1f, false)
    @Test fun mediumDarkLandscape() = scenario(1024, 600, "zh", 1f, true)
    @Test fun desktopLandscape() = scenario(1280, 720, "zh", 1f, false)
    @Test fun largeSupportedLanguages() = scenario(844, 390, "en", 1.4f, false)

    private fun scenario(width: Int, height: Int, language: String, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, language)
            val discoveries = AtomicInteger()
            val runtime = object : RobotRuntime {
                override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> { discoveries.incrementAndGet(); return emptyList() }
                override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("No pairing in offline UI validation")
                override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String): Unit = error("No pairing in offline UI validation")
                override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
                    onLost: (RobotSession, String) -> Unit): RobotSession = error("No device connections in offline UI validation")
            }
            val model = testConsoleModel(robotRuntime = runtime)
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            var wifiSettings = 0
            fun settle() { mainClock.advanceTimeBy(1000); waitForIdle() }
            fun top() { waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("connection-guide-page").fetchSemanticsNodes().isNotEmpty() }; onNodeWithTag("connection-guide-page").performScrollToIndex(0); waitForIdle() }
            fun back() { top(); onNodeWithTag("connection-guide-back").performClick(); settle() }
            fun capture(name: String, node: SemanticsNodeInteraction = onRoot()) {
                val bitmap = node.captureToImage(); val pixels = IntArray(bitmap.width * bitmap.height); bitmap.readPixels(pixels)
                val output = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
                val file = File("build/reports/ui/connection-guide-$width-$height-$language-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(output, "png", file)
            }
            fun title(text: String, name: String) {
                top()
                val layouts = mutableListOf<TextLayoutResult>()
                onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("connection-guide-page")), useUnmergedTree = true)[0]
                    .assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                capture(name)
                assertTrue(layouts.isNotEmpty())
                assertTrue((0 until layouts.single().lineCount).none(layouts.single()::isLineEllipsized), "Complete connection title must not be ellipsized: $text")
                onNodeWithTag("connection-guide-back").assertHeightIsAtLeast(48.dp)
            }
            try {
                setContent { TestWorkbenchTheme(appearance) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        Console(model, remember { mutableStateOf(EditorDocument()) }, onOpenWifiSettings = { wifiSettings++ })
                    }
                } }
                onNodeWithTag("connection-status").performClick()
                capture("connection-dialog", onNode(isRoot() and hasAnyDescendant(hasTestTag("connection-guide"))))
                onNodeWithTag("connection-guide").performScrollTo().assertIsDisplayed().performClick(); settle()
                val languages = if (scale > 1f) AppLanguage.entries else listOf(AppLanguage.entries.single { it.preference == language })
                for ((index, selected) in languages.withIndex()) {
                    runOnIdle { Localization.select(selected.preference) }
                    title(tr(Res.string.connect_robot), "${selected.preference}-modes")
                    onNodeWithTag("direct-mode").performScrollTo().performClick(); settle()
                    title(tr(Res.string.direct_mode), "${selected.preference}-direct")
                    onNodeWithTag("open-wifi-settings").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp)
                    if (index == 0) onNodeWithTag("open-wifi-settings").performClick()
                    onNodeWithTag("discover-robot").performScrollTo().assertIsEnabled()
                    runOnIdle { model.state.value = model.state.value.copy(busy = true) }
                    waitUntil { onNodeWithTag("discover-robot").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
                    settle()
                    onNodeWithTag("discover-robot").assertIsNotEnabled()
                    onNodeWithTag("open-wifi-settings").assertIsNotEnabled()
                    runOnIdle { model.state.value = model.state.value.copy(busy = false) }
                    settle(); back()
                    onNodeWithTag("router-mode").performScrollTo().performClick(); settle()
                    title(tr(Res.string.router_mode), "${selected.preference}-router")
                    onNodeWithTag("router-ssid").performScrollTo().performTextReplacement("X".repeat(33))
                    onNodeWithTag("router-password").performScrollTo().performTextReplacement("offline-qa-password")
                    onNodeWithTag("pair-router").performScrollTo().assertIsNotEnabled()
                    onNodeWithTag("router-ssid").performScrollTo().performTextReplacement("Offline-QA-日本語")
                    onNodeWithTag("pair-router").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp)
                    onNodeWithTag("router-qr-code").performScrollTo().assertIsDisplayed()
                    capture("${selected.preference}-qr")
                    val alternate = languages[(index + 1) % languages.size]
                    runOnIdle { Localization.select(alternate.preference) }
                    onNodeWithTag("router-ssid").performScrollTo().assertTextContains("Offline-QA-日本語")
                    onNodeWithTag("pair-router").performScrollTo().assertIsEnabled()
                    runOnIdle { Localization.select(selected.preference); model.state.value = model.state.value.copy(busy = true) }
                    waitUntil { onNodeWithTag("pair-router").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
                    onNodeWithTag("pair-router").assertIsNotEnabled()
                    runOnIdle { model.state.value = model.state.value.copy(busy = false) }
                    settle(); back()
                }
                runOnIdle { assertEquals(1, wifiSettings); assertEquals(0, discoveries.get()); Localization.select(language) }
                onNodeWithTag("direct-mode").performScrollTo().performClick(); settle()
                onNodeWithTag("discover-robot").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { discoveries.get() == 1 && !model.state.value.busy }
                onNodeWithTag("connection-guide-page").assertDoesNotExist()
                assertFalse(model.remoteEnabled.value); assertFalse(model.state.value.connected)
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
