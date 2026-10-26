package cn.elonzh.hanppie.ui.robot.device

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.product.WorkingRobotDevice
import cn.elonzh.hanppie.robot.product.RobotVersionTarget
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import cn.elonzh.hanppie.robot.telemetry.BatteryTelemetry
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.settings.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class RobotInformationUiTest {
    @Test fun unevenPixelWidthKeepsIdentityFieldsInTwoColumns() = runDesktopComposeUiTest(width = 1000, height = 720) {
        val model = testConsoleModel()
        model.state.value = model.state.value.copy(connected = true)
        model.robotInformation.attach(InformationFixture(), MutableStateFlow(RobotHealth()))
        try {
            setContent { TestWorkbenchTheme { Column(Modifier.width(731.dp).fillMaxHeight().padding(16.dp)) { RobotInformationContent(model) } } }
            waitUntil(timeoutMillis = 3_000) { model.robotInformation.state.value.activation is InformationValue.Value }
            val modelBounds = onNodeWithTag("robot-info-model").getBoundsInRoot()
            val activation = onNodeWithTag("robot-info-activation").getBoundsInRoot()
            assertEquals(modelBounds.top, activation.top)
            assertTrue(modelBounds.right < activation.left)
        } finally { model.close() }
    }

    @Test fun compact() = scenario(844, 390)
    @Test fun tabletDark() = scenario(1024, 600, dark = true)
    @Test fun desktop() = scenario(1280, 720)
    @Test fun largeEnglish() = scenario(844, 390, "en", 1.4f)

    private fun scenario(width: Int, height: Int, language: String = "zh", scale: Float = 1f, dark: Boolean = false) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel(); val service = InformationFixture()
            val health = MutableStateFlow(RobotHealth(modules = RobotVersionTarget.entries.mapNotNull { it.componentId }.distinct()
                .map { WorkingRobotDevice(it, emptyList()) }))
            service.version = { if (it in setOf(RobotVersionTarget.SYSTEM_CAN, RobotVersionTarget.PERCEPTION)) throw RobotRequestException(RequestProblem.TIMED_OUT) else "01.02.03.04" }
            model.state.value = model.state.value.copy(connected = true)
            model.robotInformation.attach(service, health)
            model.robotHealth.state.value = health.value.copy(battery = BatteryTelemetry(12000, 325, -1600, 72))
            try {
                setContent { TestWorkbenchTheme(AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        SettingsPage(model, Modifier.fillMaxSize().padding(16.dp), showRobotMaintenance = true)
                    }
                } }
                waitUntil(timeoutMillis = 5_000) { model.robotInformation.state.value.activation is InformationValue.Value && !model.robotInformation.state.value.busy }
                assertEquals(RobotVersionTarget.entries.size, service.reads.size)
                onNodeWithTag("robot-info-toggle").assertDoesNotExist()
                onNodeWithTag("robot-info-query-SYSTEM").assertDoesNotExist()
                onNodeWithTag("robot-info-value-SYSTEM").performScrollTo().assertTextEquals("01.02.03.04")
                onNodeWithTag("robot-info-usage").assertDoesNotExist()
                val initial = onRoot().captureToImage()
                val initialPixels = IntArray(initial.width * initial.height); initial.readPixels(initialPixels)
                val initialOut = BufferedImage(initial.width, initial.height, BufferedImage.TYPE_INT_ARGB)
                initialOut.setRGB(0, 0, initial.width, initial.height, initialPixels, 0, initial.width)
                ImageIO.write(initialOut, "png", File("build/reports/ui/status-overview-$width-$height-$language.png").apply { parentFile.mkdirs() })
                onNodeWithTag("robot-info-components").performScrollTo().performClick()
                onNodeWithTag("health-module-1024").performScrollTo().assertIsDisplayed()
                onNodeWithTag("robot-info-value-GIMBAL").assertTextEquals("01.02.03.04")
                onNodeWithTag("health-modules-toggle").assertDoesNotExist()
                onNodeWithTag("robot-info-PERCEPTION").assertDoesNotExist()
                onNodeWithTag("robot-info-SYSTEM_CAN").assertDoesNotExist()
                val image = onRoot().captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                ImageIO.write(out, "png", File("build/reports/ui/info-auto-$width-$height-$language.png").apply { parentFile.mkdirs() })
                service.version = { "02.00.00.00" }
                onNodeWithTag("robot-info-refresh").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { model.robotInformation.state.value.versions[RobotVersionTarget.SYSTEM_CAN] is InformationValue.Value }
                waitUntil(timeoutMillis = 3_000) { !model.robotInformation.state.value.busy }
                onNodeWithTag("robot-info-value-SYSTEM_CAN").performScrollTo().assertTextEquals("02.00.00.00")
                assertEquals(RobotVersionTarget.entries.size * 2, service.reads.size)
                service.version = { awaitCancellation() }
                onNodeWithTag("robot-info-refresh").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { model.robotInformation.state.value.busy }
                onNodeWithTag("robot-info-loading").performScrollTo().assertIsDisplayed()
                runOnIdle { model.robotInformation.cancelQueries() }
                waitUntil(timeoutMillis = 3_000) { !model.robotInformation.state.value.busy }
                assertTrue(model.robotInformation.state.value.versions.values.all { it == InformationValue.Unread })
                runOnIdle { model.robotInformation.clear(); model.state.value = model.state.value.copy(connected = false) }
                onNodeWithTag("robot-info-refresh").assertDoesNotExist()
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
