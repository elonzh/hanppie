package cn.elonzh.hanppie.ui.robot.network

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.settings.SettingsPage
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class RobotSdrUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun maintenanceOnlyShowsVerifiedSdrCapabilitiesAndConfirmsEveryWrite() {
        Localization.initialize("zh", null)
        val service = SdrFixture()
        val runtime = object : RobotRuntime {
            override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = error("unused")
            override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("unused")
            override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String) = error("unused")
            override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
                onLost: (RobotSession, String) -> Unit): RobotSession = RobotSessionFixture().apply { sdr = service }
        }
        val model = testConsoleModel(robotRuntime = runtime)
        try {
            rule.setContent { TestWorkbenchTheme { SettingsPage(model) } }
            rule.onNodeWithTag("settings-category-robot").performScrollTo().performClick()
            rule.onNodeWithTag("sdr-settings").assertDoesNotExist()
            model.connect("127.0.0.1", "12345678")
            rule.waitUntil(5_000) { model.robotSdr.state.value.visible && !model.state.value.busy }
            rule.onNodeWithTag("sdr-mode-1").performScrollTo().performClick()
            assertTrue(service.writes.isEmpty())
            rule.onNodeWithTag("sdr-confirm").performClick()
            rule.waitUntil(3_000) { model.robotSdr.state.value.settings?.selectionCode == 1 }
            rule.onNodeWithTag("sdr-frequency").assertDoesNotExist()
            rule.onNodeWithTag("sdr-mode-0").performScrollTo().performClick()
            rule.onNodeWithTag("sdr-confirm").performClick()
            rule.waitUntil(3_000) { model.robotSdr.state.value.settings?.selectionCode == 0 }
            rule.onNodeWithTag("sdr-width-1").performScrollTo().performClick()
            rule.onNodeWithTag("sdr-confirm").performClick()
            rule.waitUntil(3_000) { model.robotSdr.state.value.settings?.bandwidthCode == 1 }
            rule.onNodeWithTag("sdr-frequency").performScrollTo().performTextReplacement("2411.0")
            rule.onNodeWithTag("sdr-point-apply").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithTag("sdr-frequency").performScrollTo().performTextReplacement("2411.5")
            rule.onNodeWithTag("sdr-point-apply").performScrollTo().performClick()
            rule.onNodeWithTag("sdr-confirm").performClick()
            rule.waitUntil(3_000) { model.robotSdr.state.value.settings?.point == 1012 }
            rule.onNodeWithTag("sdr-verified").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("sdr-band-2").performScrollTo().performClick()
            rule.runOnIdle { model.robotSdr.attach(service, RobotTarget("127.0.0.1", "12345678")) }
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("sdr-confirm")).fetchSemanticsNodes().isEmpty() }
            assertFalse(service.writes.any { it is SdrChange.Band })
            rule.runOnIdle { service.settings = service.settings?.copy(dualBandSupported = false); model.robotSdr.refresh() }
            rule.waitUntil(3_000) { model.robotSdr.state.value.settings?.dualBandSupported == false }
            rule.onNodeWithTag("sdr-band-2").assertDoesNotExist()
            service.error = RobotRequestException(RequestProblem.TIMED_OUT)
            rule.onNodeWithTag("sdr-width-0").performScrollTo().performClick()
            rule.onNodeWithTag("sdr-confirm").performClick()
            rule.waitUntil(3_000) { model.robotSdr.state.value.phase == SdrPhase.NETWORK_REQUIRED && !model.state.value.connected }
            rule.onNodeWithTag("sdr-reconnect").performScrollTo().assertIsEnabled()
            service.error = null; service.settings = service.settings?.copy(bandwidthCode = 0)
            rule.onNodeWithTag("sdr-reconnect").performClick()
            rule.waitUntil(3_000) { model.robotSdr.state.value.phase == SdrPhase.VERIFIED && !model.state.value.busy }
            rule.onNodeWithTag("sdr-verified").performScrollTo().assertIsDisplayed()
            model.disconnect()
            rule.waitUntil(3_000) { !model.robotSdr.state.value.visible }
            rule.onNodeWithTag("sdr-settings").assertDoesNotExist()
        } finally { model.close() }
    }
}

@OptIn(ExperimentalTestApi::class)
class RobotSdrLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)
    private fun layout(width: Int, height: Int, language: String = "zh", scale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel()
            model.state.value = model.state.value.copy(connected = true)
            model.robotSdr.attach(SdrFixture(), RobotTarget("127.0.0.1", "12345678"))
            try {
                setContent { TestWorkbenchTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        Box(Modifier.fillMaxSize().padding(16.dp).testTag("sdr-layout")) { SettingsPage(model) }
                    }
                } }
                onNodeWithTag("settings-category-robot").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { model.robotSdr.state.value.settings != null }
                onNodeWithTag("sdr-point-apply").performScrollTo().assertIsDisplayed()
                assertTrue(onNodeWithTag("sdr-point-apply").fetchSemanticsNode().boundsInRoot.height >= 48)
                saveImage("sdr-$width-$height-$language", onNodeWithTag("sdr-layout"))
                onNodeWithTag("sdr-width-1").performScrollTo().performClick()
                onNodeWithTag("sdr-confirm").assertIsDisplayed()
                saveImage("sdr-confirm-$width-$height-$language", onNodeWithTag("sdr-confirm").onParent().onParent())
                onNodeWithText(if (language == "zh") "取消" else "Cancel").performClick()
                runOnIdle {
                    model.robotSdr.state.value = model.robotSdr.state.value.copy(phase = SdrPhase.NETWORK_REQUIRED,
                        recovery = SdrRecovery(RobotTarget("127.0.0.1", "12345678"), SdrChange.Bandwidth(SdrBandwidth.MHZ_10)))
                    model.robotSdr.detach()
                    model.state.value = model.state.value.copy(connected = false)
                }
                onNodeWithTag("sdr-reconnect").performScrollTo().assertIsEnabled()
                saveImage("sdr-recovery-$width-$height-$language", onNodeWithTag("sdr-layout"))
                onNodeWithTag("sdr-finish").performScrollTo().performClick()
                onNodeWithTag("sdr-confirm").performClick()
                waitUntil(timeoutMillis = 3_000) { !model.robotSdr.state.value.visible }
            } finally { model.close(); Localization.initialize("zh", null) }
        }
    private fun saveImage(name: String, node: SemanticsNodeInteraction) {
        val image = node.captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        ImageIO.write(out, "png", File("build/reports/ui/$name.png").apply { parentFile.mkdirs() })
    }
}
