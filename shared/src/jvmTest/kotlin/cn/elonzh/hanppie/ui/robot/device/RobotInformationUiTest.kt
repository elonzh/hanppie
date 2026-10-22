package cn.elonzh.hanppie.ui.robot.device

import cn.elonzh.hanppie.robot.telemetry.RobotSystemStatus
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
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.settings.SettingsPage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class RobotInformationUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun maintenanceQueriesRetryIndependentlyCancelWaitingAndClearOnDisconnect() {
        Localization.initialize("zh", null)
        val model = testConsoleModel(); val service = InformationFixture()
        val health = MutableStateFlow(RobotHealth(modules = listOf(WorkingRobotDevice(0x104, emptyList()))))
        service.version = { if (it == RobotVersionTarget.CAMERA) throw RobotRequestException(RequestProblem.TIMED_OUT) else "01.02.03.04" }
        model.state.value = model.state.value.copy(connected = true)
        model.robotInformation.attach(service, health)
        try {
            rule.setContent { TestWorkbenchTheme { SettingsPage(model, showRobotMaintenance = true) } }
            rule.onNodeWithTag("robot-info-toggle").performScrollTo().performClick()
            rule.onNodeWithTag("robot-info-value-activation").performScrollTo().assertTextEquals("尚未查询")
            rule.onNodeWithTag("robot-info-refresh").performScrollTo().performClick()
            rule.waitUntil(3000) { !model.robotInformation.state.value.busy }
            rule.onNodeWithTag("robot-info-value-SYSTEM").performScrollTo().assertTextEquals("01.02.03.04")
            rule.onNodeWithTag("robot-info-value-CAMERA").performScrollTo().assertTextContains("机器人未及时回复", substring = true)
            service.version = { "02.00.00.00" }
            rule.onNodeWithTag("robot-info-query-CAMERA").performScrollTo().performClick()
            rule.waitUntil(3000) { model.robotInformation.state.value.versions[RobotVersionTarget.CAMERA] is InformationValue.Value }
            rule.onNodeWithTag("robot-info-value-CAMERA").assertTextEquals("02.00.00.00")
            rule.onNodeWithTag("robot-info-value-SYSTEM").performScrollTo().assertTextEquals("01.02.03.04")
            service.version = { awaitCancellation() }
            rule.onNodeWithTag("robot-info-refresh").performScrollTo().performClick()
            rule.waitUntil(3000) { model.robotInformation.state.value.busy }
            rule.onNodeWithTag("robot-info-cancel").performScrollTo().performClick()
            rule.waitUntil(3000) { !model.robotInformation.state.value.busy }
            assertTrue(model.robotInformation.state.value.versions.values.all { it == InformationValue.Unread })
            rule.runOnIdle { service.systemStatus.value = RobotSystemStatus(null, 0, false, 123) }
            rule.waitUntil(3000) { model.robotInformation.state.value.usage == RobotSystemStatus(null, 0, false, 123) }
            rule.waitForIdle()
            rule.onNodeWithTag("robot-info-mileage").performScrollTo().assertTextEquals("累计里程：— 米")
            rule.onNodeWithTag("robot-info-driving").performScrollTo().assertTextEquals("累计驾驶时长：0 秒")
            rule.runOnIdle { model.robotInformation.clear(); model.state.value = model.state.value.copy(connected = false) }
            rule.onNodeWithText("连接机器人后可查看设备信息").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("robot-info-toggle").assertDoesNotExist()
            rule.onNodeWithTag("robot-info-mileage").assertDoesNotExist()
        } finally { model.close() }
    }
}

@OptIn(ExperimentalTestApi::class)
class RobotInformationLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)
    private fun layout(width: Int, height: Int, language: String = "zh", scale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel(); val service = InformationFixture()
            service.systemStatus.value = RobotSystemStatus(1234567, 987654, false, 123)
            service.version = { if (it == RobotVersionTarget.SYSTEM_CAN) throw RobotRequestException(RequestProblem.TIMED_OUT) else "01.02.03.04" }
            service.activation = { false }
            model.state.value = model.state.value.copy(connected = true, robotProduct = RobotProduct(model = RobotModel.ROBOMASTER_S1))
            model.robotInformation.attach(service, MutableStateFlow(RobotHealth(modules = listOf(WorkingRobotDevice(0x400, emptyList())))))
            try {
                setContent { TestWorkbenchTheme { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    Box(Modifier.fillMaxSize().padding(16.dp).testTag("info-layout")) { SettingsPage(model, showRobotMaintenance = true) }
                } } }
                onNodeWithTag("robot-info-toggle").performScrollTo().assertIsDisplayed()
                save("info-overview-$width-$height-$language", onNodeWithTag("info-layout"))
                onNodeWithTag("robot-info-toggle").performClick()
                onNodeWithTag("robot-info-refresh").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3000) { !model.robotInformation.state.value.busy }
                onNodeWithTag("robot-info-value-activation").performScrollTo().assertIsDisplayed()
                save("info-activation-$width-$height-$language", onNodeWithTag("info-layout"))
                onNodeWithTag("robot-info-query-SYSTEM_CAN").performScrollTo().assertIsDisplayed()
                save("info-failure-$width-$height-$language", onNodeWithTag("info-layout"))
                onNodeWithTag("robot-info-query-GIMBAL_MOTOR").performScrollTo().assertIsDisplayed()
                assertTrue(onNodeWithTag("robot-info-query-GIMBAL_MOTOR").fetchSemanticsNode().boundsInRoot.height >= 48)
                save("info-module-$width-$height-$language", onNodeWithTag("info-layout"))
            } finally { model.close(); Localization.initialize("zh", null) }
        }
    private fun save(name: String, node: SemanticsNodeInteraction) {
        val image = node.captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        ImageIO.write(out, "png", File("build/reports/ui/$name.png").apply { parentFile.mkdirs() })
    }
}
