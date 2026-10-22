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
import cn.elonzh.hanppie.robot.product.WorkingRobotDevice
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.*
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.settings.SettingsPage
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import cn.elonzh.hanppie.ui.robot.remote.RemotePage
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

internal fun healthFixture(): RobotHealth = RobotHealth(
    modules = listOf(WorkingRobotDevice(0x300, listOf(0x8204)), WorkingRobotDevice(0x400, listOf(0x8007)),
        WorkingRobotDevice(0x1700, listOf(0x8004)), WorkingRobotDevice(0x1234, emptyList())),
    seenDeviceIds = setOf(0x300, 0x400, 0x1700, 0x1234, 0x314),
    battery = BatteryTelemetry(12000, 325, -1600, 72))

class RobotHealthUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun homeAlertOpensMaintenanceDirectlyAndStopsInputWithoutAutomaticallyResumingIt() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        model.state.value = model.state.value.copy(connected = true)
        model.robotHealth.state.value = healthFixture()
        model.remoteInput.value = listOf(0.5, 0.0, 0.0, 0.0, 0.0)
        try {
            rule.setContent { TestWorkbenchTheme { Console(model, remember { mutableStateOf(EditorDocument()) }) } }
            rule.onNodeWithTag("navigate-4").performClick()
            rule.onNodeWithTag("settings-category-media").performScrollTo().performClick()
            rule.onNodeWithTag("navigate-0").performClick()
            rule.onNodeWithTag("health-alert-open").performClick()
            rule.onNodeWithTag("settings-detail-robot").assertIsDisplayed()
            rule.onNodeWithTag("health-status").assertTextEquals("报告了 3 条警告或错误")
            assertTrue(model.remoteInput.value.all { it == 0.0 })
        } finally { model.close() }
    }
    @Test fun currentSessionHealthShowsWarningsMissingReportsAndClearsOnReconnect() {
        Localization.initialize("zh", null)
        val sessions = List(2) { RobotSessionFixture() }; sessions[0].health.value = healthFixture()
        var opened = 0
        val runtime = object : RobotRuntime {
            override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = error("unused")
            override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("unused")
            override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String) = error("unused")
            override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
                onLost: (RobotSession, String) -> Unit): RobotSession = sessions[opened++]
        }
        val model = testConsoleModel(robotRuntime = runtime)
        try {
            rule.setContent { TestWorkbenchTheme { SettingsPage(model) } }
            rule.onNodeWithTag("settings-category-robot").performScrollTo().performClick()
            rule.onNodeWithTag("health-status").assertTextEquals("连接机器人后可查看状态")
            model.connect("127.0.0.1", "12345678")
            rule.waitUntil(5_000) { model.state.value.connected && !model.state.value.busy }
            rule.onNodeWithTag("health-status").performScrollTo().assertTextEquals("报告了 3 条警告或错误")
            rule.onNodeWithText("电流原始值：-1600").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("health-issue-0x04008007").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("health-modules-toggle").performScrollTo().performClick()
            rule.onNodeWithTag("health-module-4660").performScrollTo().assertTextContains("未知模块 0x1234", substring = true)
            rule.onNodeWithTag("health-module-788").performScrollTo().assertTextContains("本次未报告在线", substring = true)
            sessions[0].health.value = healthFixture().copy(modules = listOf(WorkingRobotDevice(0x300, emptyList())))
            rule.waitUntil(3_000) { model.robotHealth.state.value.modules?.size == 1 }
            rule.onNodeWithTag("health-status").performScrollTo().assertTextEquals("本次状态未报告警告或错误")
            rule.onNodeWithTag("health-issue-0x04008007").assertDoesNotExist()
            sessions[0].health.value = RobotHealth()
            rule.waitUntil(3_000) { model.robotHealth.state.value.modules == null }
            rule.onNodeWithTag("health-status").performScrollTo().assertTextEquals("等待当前模块状态…")
            rule.onNodeWithTag("health-battery-waiting").performScrollTo().assertIsDisplayed()
            model.disconnect(); rule.waitUntil(3_000) { !model.state.value.connected && !model.state.value.busy }
            assertTrue(sessions[0].closed)
            model.connect("127.0.0.1", "12345678"); rule.waitUntil(5_000) { model.state.value.connected && !model.state.value.busy }
            sessions[0].health.value = healthFixture()
            rule.runOnIdle { assertEquals(RobotHealth(), model.robotHealth.state.value) }
            rule.onNodeWithTag("health-status").performScrollTo().assertTextEquals("等待当前模块状态…")
        } finally { model.close() }
    }

    @Test fun unknownCodesRemainVisibleAndInformationalRecordsAreOptional() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        model.state.value = model.state.value.copy(connected = true)
        model.robotHealth.state.value = RobotHealth(modules = listOf(
            WorkingRobotDevice(0xabcd, listOf(0xffff)), WorkingRobotDevice(0x100, listOf(6, 0x14))))
        try {
            rule.setContent { TestWorkbenchTheme { SettingsPage(model) } }
            rule.onNodeWithTag("settings-category-robot").performScrollTo().performClick()
            rule.onNodeWithText("0xABCDFFFF").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("health-issue-0x01000014").assertDoesNotExist()
            rule.onNodeWithTag("health-message-toggle").performScrollTo().performClick()
            rule.onNodeWithTag("health-issue-0x01000014").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("health-message-toggle").performScrollTo().performClick()
            rule.onNodeWithTag("health-issue-0x01000014").assertDoesNotExist()
            rule.runOnIdle { model.robotHealth.state.value = RobotHealth(modules = emptyList(), moduleReportAgeSeconds = 123) }
            rule.onNodeWithTag("health-status").performScrollTo().assertTextEquals("本次未报告在线模块")
            rule.onNodeWithTag("health-report-age").performScrollTo().assertTextEquals("模块报告：123 秒前")
        } finally { model.close() }
    }
}

@OptIn(ExperimentalTestApi::class)
class RobotHealthLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)
    private fun layout(width: Int, height: Int, language: String = "zh", scale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel()
            model.state.value = model.state.value.copy(connected = true)
            model.robotHealth.state.value = healthFixture()
            var cockpit by mutableStateOf(false)
            try {
                setContent { TestWorkbenchTheme { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    Box(Modifier.fillMaxSize().padding(16.dp).testTag("health-layout")) {
                        if (cockpit) RemotePage(model, onBack = {}, manageSession = false, inputEnabled = false,
                            navigationEnabled = true, onHealth = {}, videoContent = { Box(Modifier.fillMaxSize()) })
                        else SettingsPage(model)
                    }
                } } }
                onNodeWithTag("settings-category-robot").performScrollTo().performClick()
                onNodeWithTag("health-status").performScrollTo().assertIsDisplayed()
                save("health-$width-$height-$language", onNodeWithTag("health-layout"))
                onNodeWithTag("health-issue-0x04008007").performScrollTo().assertIsDisplayed()
                save("health-alerts-$width-$height-$language", onNodeWithTag("health-layout"))
                onNodeWithTag("health-modules-toggle").performScrollTo().performClick()
                assertTrue(onNodeWithTag("health-modules-toggle").fetchSemanticsNode().boundsInRoot.height >= 48)
                onNodeWithTag("health-module-4660").performScrollTo().assertIsDisplayed()
                save("health-modules-$width-$height-$language", onNodeWithTag("health-layout"))
                runOnIdle { cockpit = true }
                onNodeWithTag("health-alert-open").assertIsDisplayed()
                save("health-cockpit-$width-$height-$language", onNodeWithTag("health-layout"))
            } finally { model.close(); Localization.initialize("zh", null) }
        }
    private fun save(name: String, node: SemanticsNodeInteraction) {
        val image = node.captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        ImageIO.write(out, "png", File("build/reports/ui/$name.png").apply { parentFile.mkdirs() })
    }
}
