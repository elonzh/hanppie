package cn.elonzh.hanppie.ui.robot.network

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.wifi_no_active_link
import cn.elonzh.hanppie.resources.wifi_network_tab
import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.AppLanguage
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import cn.elonzh.hanppie.ui.settings.AppearanceController
import cn.elonzh.hanppie.ui.settings.AppearanceSettings
import cn.elonzh.hanppie.ui.settings.NightMode
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class RobotWifiUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun maintenanceNavigationEditsSavesRestartsAndExplicitlyReconnects() {
        Localization.initialize("zh", null)
        val service = WifiFixture()
        val runtime = object : RobotRuntime {
            override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = error("Do not discover during explicit recovery")
            override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("unused")
            override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String) = error("unused")
            override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
                onLost: (RobotSession, String) -> Unit): RobotSession = RobotSessionFixture().apply { wifi = service }
        }
        val model = testConsoleModel(robotRuntime = runtime)
        var systemSettings = 0
        try {
            model.connect("127.0.0.1", "12345678")
            rule.setContent { TestWorkbenchTheme { Console(model, remember { mutableStateOf(EditorDocument()) }, onOpenWifiSettings = { systemSettings++ }) } }
            rule.waitUntil(5_000) { model.state.value.connected && !model.state.value.busy }
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithTag("settings-category-robot").performScrollTo().performClick()
            rule.onNodeWithTag("settings-wifi-open").performScrollTo().performClick()
            rule.waitUntil(3_000) { model.robotWifi.state.value.profile != null }
            rule.onNodeWithTag("robot-wifi-page").assertIsDisplayed()
            rule.onNodeWithTag("wifi-ssid").performScrollTo().performTextReplacement("bad name")
            rule.onNodeWithTag("wifi-apply").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithTag("wifi-ssid").performScrollTo().performTextReplacement("RMS1_New")
            rule.onNodeWithTag("wifi-password").performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                .performTextReplacement("new_pass")
            val masked = mutableListOf<TextLayoutResult>()
            rule.onNodeWithTag("wifi-password").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(masked) }
            assertTrue(masked.isNotEmpty()); assertFalse(masked.any { it.layoutInput.text.text == "new_pass" })
            rule.onNodeWithTag("wifi-password-visibility").performScrollTo().performClick()
            val visible = mutableListOf<TextLayoutResult>()
            rule.onNodeWithTag("wifi-password").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(visible) }
            assertTrue(visible.any { it.layoutInput.text.text == "new_pass" })
            rule.onNodeWithTag("wifi-apply").performScrollTo().performClick()
            assertTrue(service.writes.isEmpty())
            rule.onNodeWithTag("wifi-confirm").performClick()
            rule.waitUntil(3_000) { model.robotWifi.state.value.phase == WifiPhase.READY }
            assertEquals(0, service.reboots)
            rule.onNodeWithTag("wifi-ssid-ack").assertIsDisplayed()
            rule.onNodeWithTag("wifi-password-ack").assertIsDisplayed()
            rule.onNodeWithTag("wifi-reboot").performScrollTo().performClick()
            assertEquals(0, service.reboots)
            rule.onNodeWithTag("wifi-confirm").performClick()
            rule.waitUntil(3_000) { model.robotWifi.state.value.phase == WifiPhase.NETWORK_REQUIRED }
            rule.onNodeWithTag("wifi-open-system").performScrollTo().performClick()
            assertEquals(1, systemSettings); assertFalse(model.state.value.connected)
            rule.onNodeWithTag("wifi-reconnect").performScrollTo().performClick()
            rule.waitUntil(3_000) { model.robotWifi.state.value.phase == WifiPhase.VERIFIED }
            rule.onNodeWithTag("wifi-verified").assertIsDisplayed()
            rule.onNodeWithTag("wifi-query").assertIsEnabled()
            rule.onNodeWithTag("wifi-back").performClick()
            rule.onNodeWithTag("settings-detail-robot").assertIsDisplayed()
        } finally { model.close() }
    }

    @Test fun rebindInvalidatesConfirmationAndStationModeDoesNotExposeApCredentials() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val service = WifiFixture()
        val origin = RobotTarget("127.0.0.1", "12345678")
        model.robotWifi.attach(service, origin)
        model.state.value = model.state.value.copy(connected = true)
        try {
            rule.setContent { TestWorkbenchTheme { RobotWifiPage(model, onBack = {}) } }
            rule.waitUntil(3_000) { model.robotWifi.state.value.profile != null }
            rule.onNodeWithTag("wifi-apply").performScrollTo().performClick()
            rule.onNodeWithTag("wifi-confirm").assertIsDisplayed()
            rule.runOnIdle { model.robotWifi.attach(service, origin) }
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("wifi-confirm")).fetchSemanticsNodes().isEmpty() }
            assertTrue(service.writes.isEmpty())
            rule.runOnIdle { service.profile = RobotWifiProfile(2, "router", null); model.robotWifi.attach(service, origin) }
            rule.waitUntil(3_000) { model.robotWifi.state.value.profile?.modeCode == 2 }
            rule.onNodeWithTag("wifi-ssid").assertDoesNotExist()
            rule.onNodeWithTag("wifi-password").assertDoesNotExist()
            rule.onNodeWithTag("wifi-reboot").performScrollTo().assertIsEnabled()
        } finally { model.close() }
    }

    @Test fun channelViewRequiresConfirmationVerifiesSettingsAndStopsReportsWhenLeaving() {
        Localization.initialize("zh", null)
        val service = WifiFixture()
        val runtime = object : RobotRuntime {
            override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = error("unused")
            override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("unused")
            override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String) = error("unused")
            override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
                onLost: (RobotSession, String) -> Unit): RobotSession = RobotSessionFixture().apply { wifi = service }
        }
        val model = testConsoleModel(robotRuntime = runtime)
        try {
            model.connect("127.0.0.1", "12345678")
            rule.setContent { TestWorkbenchTheme { RobotWifiPage(model, onBack = {}) } }
            rule.waitUntil(5_000) { model.robotWifi.state.value.profile != null && !model.state.value.busy }
            rule.onNodeWithText("信道与干扰").performScrollTo().performClick()
            rule.waitUntil(3_000) { model.robotWifi.state.value.radio != null }
            rule.onNodeWithTag("wifi-channel-149").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithTag("wifi-channel-mode-1").performScrollTo().performClick()
            assertTrue(service.modeWrites.isEmpty())
            rule.onNodeWithTag("wifi-confirm").performClick()
            rule.waitUntil(3_000) { model.robotWifi.state.value.monitoring }
            service.signals.value = WifiSignals(listOf(WifiChannelSignal(149, -72)), 1)
            rule.waitUntil(3_000) { model.robotWifi.state.value.signals.interferenceCode == 1 }
            rule.onNodeWithTag("wifi-channel-149").performScrollTo().performClick()
            assertTrue(service.channelWrites.isEmpty())
            rule.onNodeWithTag("wifi-confirm").performClick()
            rule.waitUntil(3_000) { model.robotWifi.state.value.radio?.currentChannel == 149 }
            rule.onNodeWithTag("wifi-radio-result").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("网络信息").performScrollTo().performClick()
            rule.waitUntil(3_000) { service.reportWrites.lastOrNull() == false }
            rule.runOnIdle { service.profile = RobotWifiProfile(2, "router", null); service.radio = service.radio.copy(modeCode = 2); model.robotWifi.refresh() }
            rule.waitUntil(3_000) { model.robotWifi.state.value.profile?.modeCode == 2 }
            rule.onNodeWithText("信道与干扰").performScrollTo().performClick()
            rule.onNodeWithTag("wifi-radio-query").performScrollTo().performClick()
            rule.waitUntil(3_000) { model.robotWifi.state.value.radio?.modeCode == 2 }
            rule.onNodeWithTag("wifi-channel-mode-1").assertDoesNotExist()
        } finally { model.close() }
    }
}

@OptIn(ExperimentalTestApi::class)
class RobotWifiLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)

    private fun layout(width: Int, height: Int, language: String = "zh", fontScale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel()
            val origin = RobotTarget("127.0.0.1", "12345678")
            val service = WifiFixture()
            model.robotWifi.attach(service, origin)
            model.state.value = model.state.value.copy(connected = true)
            try {
                setContent { TestWorkbenchTheme(AppearanceController(AppearanceSettings(
                    if (width == 1024 || fontScale > 1f) NightMode.DARK else NightMode.LIGHT))) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        RobotWifiPage(model, Modifier.fillMaxSize().padding(16.dp), onBack = {}, onOpenWifiSettings = {})
                    }
                } }
                waitUntil(timeoutMillis = 3_000) { model.robotWifi.state.value.profile != null }
                snapshot("wifi-edit-$width-$height-$language", onNodeWithTag("robot-wifi-page"))
                onNodeWithTag("wifi-apply").performScrollTo().assertIsDisplayed().assertIsEnabled()
                assertTrue(onNodeWithTag("wifi-apply").fetchSemanticsNode().boundsInRoot.height >= 48)
                runOnIdle {
                    service.radio = service.radio.copy(selectionCode = 1)
                    model.robotWifi.state.value = model.robotWifi.state.value.copy(radio = service.radio)
                    service.signals.value = WifiSignals(listOf(WifiChannelSignal(1, -90), WifiChannelSignal(6, -73),
                        WifiChannelSignal(11, -82), WifiChannelSignal(36, -95), WifiChannelSignal(149, -67)), 2)
                }
                onNodeWithTag("wifi-body").performScrollToNode(hasTestTag("wifi-tabs"))
                onNodeWithText(if (language == "zh") "信道与干扰" else "Channels and interference").performClick()
                waitUntil(timeoutMillis = 3_000) { model.robotWifi.state.value.signals.channels != null }
                onNodeWithTag("wifi-body").performScrollToNode(hasTestTag("wifi-channel-149"))
                onNodeWithTag("wifi-channel-149").assertIsDisplayed().assertIsEnabled()
                snapshot("wifi-channels-$width-$height-$language", onNodeWithTag("robot-wifi-page"))
                runOnIdle {
                    model.robotWifi.state.value = RobotWifiState(phase = WifiPhase.NETWORK_REQUIRED,
                        confirmed = setOf(WifiConfirmedField.SSID), recovery = WifiRecovery(origin, service.profile,
                            RobotWifiProfile(0, "RMS1_New_Network", "secret_password"), true))
                    model.state.value = model.state.value.copy(connected = false)
                    model.robotWifi.detach()
                }
                onNodeWithTag("wifi-body").performScrollToIndex(0)
                snapshot("wifi-recovery-$width-$height-$language", onNodeWithTag("robot-wifi-page"))
                onNodeWithText("secret_password").assertDoesNotExist()
                onNodeWithTag("wifi-body").performScrollToNode(hasTestTag("wifi-reconnect"))
                onNodeWithTag("wifi-reconnect").assertIsDisplayed().assertIsEnabled()
                onNodeWithTag("wifi-body").performScrollToNode(hasTestTag("wifi-end"))
                onNodeWithTag("wifi-end").performClick()
                onNodeWithTag("wifi-confirm").performClick()
                waitUntil(timeoutMillis = 3_000) { model.robotWifi.state.value.recovery == null }
                assertNull(model.robotWifi.state.value.draft)
                runOnIdle { model.state.value = model.state.value.copy(connected = true) }
                for (code in listOf(2, 1, 4, 255)) {
                    runOnIdle {
                        service.profile = RobotWifiProfile(code, if (code == 2) "router" else null, null)
                        model.robotWifi.attach(service, origin)
                    }
                    waitUntil(timeoutMillis = 3_000) { model.robotWifi.state.value.profile?.modeCode == code }
                    onNodeWithTag("wifi-mode").assertIsDisplayed()
                    onNodeWithTag("wifi-ssid").assertDoesNotExist()
                    onNodeWithTag("wifi-password").assertDoesNotExist()
                    if (code == 2) {
                        onNodeWithTag("wifi-tabs").assertIsDisplayed()
                        onNodeWithText(uiText(Res.string.wifi_network_tab).resolve()).performClick()
                        onNodeWithTag("wifi-reboot").performScrollTo().assertIsEnabled()
                        onNodeWithTag("wifi-body").performScrollToIndex(0)
                    } else {
                        onNodeWithTag("wifi-tabs").assertDoesNotExist()
                        onNodeWithTag("wifi-reboot").assertDoesNotExist()
                    }
                    if (code == 1) onNodeWithTag("wifi-mode").assertTextContains("SDR", substring = true)
                    if (code == 4) onNodeWithTag("wifi-mode").assertTextContains(uiText(Res.string.wifi_no_active_link).resolve(), substring = true)
                    onNodeWithTag("wifi-query").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    snapshot("wifi-mode-$code-$width-$height-$language", onNodeWithTag("robot-wifi-page"))
                    if (width == 844 && fontScale == 1.4f && code in listOf(1, 4)) {
                        for (locale in AppLanguage.entries) {
                            runOnIdle { Localization.select(locale.preference) }
                            if (code == 1) onNodeWithTag("wifi-mode").assertTextContains("SDR", substring = true)
                            else onNodeWithTag("wifi-mode").assertTextContains(uiText(Res.string.wifi_no_active_link).resolve(), substring = true)
                            onNodeWithTag("wifi-query").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                            snapshot("wifi-mode-$code-$width-$height-${locale.preference}-large", onNodeWithTag("robot-wifi-page"))
                        }
                        runOnIdle { Localization.select(language) }
                    }
                }
                val beforeFailure = service.reads
                runOnIdle { service.readError = RobotRequestException(RequestProblem.TIMED_OUT); model.robotWifi.refresh() }
                waitUntil(timeoutMillis = 3_000) { model.robotWifi.state.value.error != null && !model.robotWifi.state.value.busy }
                onNodeWithTag("wifi-mode").assertDoesNotExist()
                onNodeWithTag("wifi-error").assertIsDisplayed()
                mainClock.advanceTimeBy(500); waitForIdle()
                assertEquals(beforeFailure + 1, service.reads, "A failed mode query must wait for the user's retry")
                snapshot("wifi-mode-error-$width-$height-$language", onNodeWithTag("robot-wifi-page"))
                runOnIdle { service.readError = null; service.profile = RobotWifiProfile(1, null, null) }
                onNodeWithTag("wifi-query").performClick()
                waitUntil(timeoutMillis = 3_000) { model.robotWifi.state.value.profile?.modeCode == 1 }
                onNodeWithTag("wifi-mode").assertTextContains("SDR", substring = true)
                onNodeWithTag("wifi-error").assertDoesNotExist()
                snapshot("wifi-mode-retry-$width-$height-$language", onNodeWithTag("robot-wifi-page"))
            } finally { model.close(); Localization.initialize("zh", null) }
        }

    private fun snapshot(name: String, node: SemanticsNodeInteraction) {
        val image = node.captureToImage()
        val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        ImageIO.write(out, "png", File("build/reports/ui/$name.png").apply { parentFile.mkdirs() })
    }
}
