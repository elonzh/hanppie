package cn.elonzh.hanppie.ui.robot.device

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.telemetry.RobotSystemStatus
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.settings.SettingsPage
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class RobotPowerUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun maintenanceRequiresConfirmationVerifiesPolicyAndEndsOnlyTheConfirmedPowerAction() {
        Localization.initialize("zh", null)
        val service = PowerFixture(); val information = InformationFixture()
        information.systemStatus.value = RobotSystemStatus(0, 0, false, 123)
        val session = RobotSessionFixture().apply { power = service; this.information = information }
        val model = testConsoleModel(robotRuntime = powerRuntime(listOf(session)))
        val gate = CompletableDeferred<Unit>()
        try {
            rule.setContent { TestWorkbenchTheme { SettingsPage(model, showRobotMaintenance = true) } }
            model.connect("127.0.0.1", "12345678")
            rule.waitUntil(5000) { model.state.value.connected && !model.state.value.busy }
            rule.onNodeWithTag("power-query").performScrollTo().performClick()
            rule.waitUntil(3000) { model.robotPower.state.value.policy != null }
            rule.onNodeWithTag("power-policy").performScrollTo().assertTextEquals("编程节能已开启 · 时限 300 秒")
            rule.onNodeWithTag("power-delay-30").performScrollTo().performClick()
            rule.onNodeWithTag("power-confirm-cancel").performClick()
            assertEquals(0, service.writes)
            rule.onNodeWithTag("power-delay-30").performClick(); rule.onNodeWithTag("power-confirm").performClick()
            rule.waitUntil(3000) { model.robotPower.state.value.verified }
            rule.onNodeWithTag("power-verified").performScrollTo().assertIsDisplayed()
            assertEquals(LowPowerPolicy(true, 30), service.policy)
            rule.onNodeWithTag("power-action-SHUTDOWN").performScrollTo().performClick()
            rule.onNodeWithTag("power-confirm-cancel").performClick(); assertEquals(0, service.actions)
            service.action = { _, dispatched -> dispatched(); gate.await() }
            rule.onNodeWithTag("power-action-SHUTDOWN").performClick(); rule.onNodeWithTag("power-confirm").performClick()
            rule.waitUntil(3000) { service.actions == 1 }
            rule.onNodeWithTag("power-result").assertDoesNotExist()
            rule.onNodeWithTag("power-cancel-read").assertDoesNotExist()
            rule.runOnIdle { gate.complete(Unit) }
            rule.waitUntil(3000) { !model.state.value.connected && !model.robotPower.state.value.busy }
            rule.onNodeWithTag("power-result").assertIsDisplayed().assertTextContains("设备已应答电源请求", substring = true)
            rule.onNodeWithTag("power-query").assertDoesNotExist()
            assertTrue(session.closed); assertEquals(1, service.actions)
        } finally { model.close() }
    }
}

@OptIn(ExperimentalTestApi::class)
class RobotPowerLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun chineseLargeText() = layout(844, 390, "zh", 1.4f)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)
    private fun layout(width: Int, height: Int, language: String = "zh", scale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel(); val service = PowerFixture()
            service.policy = LowPowerPolicy(true, 65535)
            model.state.value = model.state.value.copy(connected = true)
            val information = InformationFixture().apply { systemStatus.value = RobotSystemStatus(123, 456, true, 123) }
            model.robotInformation.attach(information, MutableStateFlow(cn.elonzh.hanppie.robot.telemetry.RobotHealth()))
            model.robotPower.attach(service, information.systemStatus)
            model.robotHealth.state.value = healthFixture()
            model.robotPower.refresh()
            try {
                setContent { TestWorkbenchTheme { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    Box(Modifier.fillMaxSize().testTag("power-layout")) { Console(model, remember { mutableStateOf(EditorDocument()) }) }
                } } }
                onNodeWithTag("health-alert-open").performClick()
                onNodeWithTag("power-delay-300").performScrollTo().assertIsDisplayed()
                assertTrue(onNodeWithTag("power-delay-300").fetchSemanticsNode().boundsInRoot.height >= 48)
                save("power-policy-$width-$height-$language-$scale", onNodeWithTag("power-layout"))
                onNodeWithTag("power-delay-30").performScrollTo().performClick()
                onNodeWithTag("power-confirm").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                save("power-policy-confirm-$width-$height-$language-$scale", onNode(isRoot() and hasAnyDescendant(hasTestTag("power-confirm"))))
                onNodeWithTag("power-confirm-cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
                for (action in RobotPowerAction.entries) {
                    onNodeWithTag("power-action-${action.name}").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
                    onNodeWithTag("power-confirm").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    save("power-confirm-${action.name}-$width-$height-$language-$scale", onNode(isRoot() and hasAnyDescendant(hasTestTag("power-confirm"))))
                    onNodeWithTag("power-confirm-cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
                }
                assertEquals(0, service.writes)
                assertEquals(0, service.actions)
                runOnIdle {
                    model.robotPower.state.value = model.robotPower.state.value.copy(connected = false, policy = null, report = null,
                        result = PowerResult(RobotPowerAction.SHUTDOWN, PowerOutcome.UNCONFIRMED))
                    model.state.value = model.state.value.copy(connected = false)
                }
                onNodeWithTag("power-result").assertIsDisplayed()
                save("power-unconfirmed-$width-$height-$language-$scale", onNodeWithTag("power-layout"))
            } finally { model.close(); Localization.initialize("zh", null) }
        }
    private fun save(name: String, node: SemanticsNodeInteraction) {
        val image = node.captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        ImageIO.write(out, "png", File("build/reports/ui/$name.png").apply { parentFile.mkdirs() })
    }
}
