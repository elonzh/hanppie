package cn.elonzh.hanppie.ui.robot.extensions

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import cn.elonzh.hanppie.robot.extensions.*
import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.settings.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class RobotExtensionLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun englishLargeTextDark() = layout(844, 390, "en", 1.4f)

    private fun layout(width: Int, height: Int, language: String = "zh", scale: Float = 1f) = runDesktopComposeUiTest(width = width, height = height) {
        Localization.initialize(language, null)
        val service = ExtensionServiceFixture(); val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(extensionSession(service))))
        val appearance = AppearanceController(AppearanceSettings(if (language == "en") NightMode.DARK else NightMode.LIGHT))
        val prefix = "extensions-$width-$height-$language"
        try {
            setContent { TestWorkbenchTheme(appearance) { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                RobotExtensionPage(model, onBack = {}, onAddressing = {})
            } } }
            val backY = onNodeWithTag("extensions-back").fetchSemanticsNode().boundsInRoot.top
            saveExtension("$prefix-offline", onNodeWithTag("extensions-page"))
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && extensionIdle(model) }
            model.robotExtensions.setActive(true)
            onNodeWithTag("extensions-module-SERVO_4").assertIsDisplayed()
            assertTrue(service.calls.none { it.startsWith("identify:") || it.startsWith("observe:SERVO") })
            saveExtension("$prefix-list", onNodeWithTag("extensions-page"))
            onNodeWithTag("extensions-module-SERVO_4").performClick()
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.observation == ExtensionObservation.ACTIVE }
            service.sample(); waitUntil(timeoutMillis = 3000) { model.robotExtensions.state.value.task.fresh }
            onNodeWithTag("robot-info-value-extension-SERVO_4").performScrollTo().assertTextEquals("01.02.03.04")
            saveExtension("$prefix-servo", onNodeWithTag("extensions-page"))
            runOnIdle { service.state.value = service.state.value.copy(readings = mapOf(RobotComponent.SERVO_4 to ServoReading(RobotComponent.SERVO_4, false, -32768, -999))) }
            waitUntil(timeoutMillis = 3000) { (model.robotExtensions.state.value.task.readings[RobotComponent.SERVO_4] as? ServoReading)?.valid == false }
            onNodeWithTag("extensions-raw-toggle").performScrollTo().performClick()
            onNodeWithTag("extensions-raw").performScrollTo().assertIsDisplayed()
            onNodeWithTag("extensions-servo-angle").assertDoesNotExist()
            saveExtension("$prefix-invalid", onNodeWithTag("extensions-page"))
            onNodeWithTag("extensions-family-TOF").performScrollTo().performClick()
            onNodeWithTag("extensions-module-TOF_4").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.kind == ExtensionKind.TOF }
            service.sample(); waitUntil(timeoutMillis = 3000) { model.robotExtensions.state.value.task.fresh }
            onNodeWithTag("extensions-raw-toggle").performScrollTo().performClick()
            onNodeWithTag("extensions-raw").performScrollTo().assertIsDisplayed()
            saveExtension("$prefix-tof", onNodeWithTag("extensions-page"))
            onNodeWithTag("extensions-family-ADAPTER").performScrollTo().performClick()
            onNodeWithTag("extensions-module-SENSOR_ADAPTER_6").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.kind == ExtensionKind.ADAPTER }
            service.sample(); waitUntil(timeoutMillis = 3000) { model.robotExtensions.state.value.task.fresh }
            onNodeWithTag("extensions-identify").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.identifying == RobotComponent.SENSOR_ADAPTER_6 }
            onNodeWithTag("robot-info-value-extension-ADAPTER_6").performScrollTo().assertTextEquals("01.02.03.04")
            saveExtension("$prefix-adapter-identifying", onNodeWithTag("extensions-page"))
            onNodeWithTag("extensions-stop-identify").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { service.state.value.identifying == null }
            runOnIdle { service.state.value = service.state.value.copy(fresh = false, ageSeconds = 12) }
            waitUntil(timeoutMillis = 3000) { !model.robotExtensions.state.value.task.fresh }
            onNodeWithTag("extensions-data-status").performScrollTo().assertIsDisplayed()
            saveExtension("$prefix-stale", onNodeWithTag("extensions-page"))
            service.onObserve = { throw ExtensionOperationException(ExtensionProblem.REQUEST_REJECTED) }
            onNodeWithTag("extensions-family-SERVO").performScrollTo().performClick()
            onNodeWithTag("extensions-module-SERVO_1").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && model.robotExtensions.state.value.error != null }
            onNodeWithTag("extensions-retry").performScrollTo().assertIsDisplayed().assertIsEnabled()
            saveExtension("$prefix-retry", onNodeWithTag("extensions-page"))
            onNodeWithTag("extensions-family-TOF").performScrollTo().performClick()
            runOnIdle { service.state.value = service.state.value.copy(online = service.state.value.online!!.filterTo(linkedSetOf()) { ExtensionKind.TOF.index(it) == null }) }
            waitUntil(timeoutMillis = 3000) { model.robotExtensions.state.value.task.online!!.none { ExtensionKind.TOF.index(it) != null } && extensionIdle(model) }
            onNodeWithTag("extensions-empty").performScrollTo().assertIsDisplayed()
            saveExtension("$prefix-empty", onNodeWithTag("extensions-page"))
            assertEquals(backY, onNodeWithTag("extensions-back").fetchSemanticsNode().boundsInRoot.top)
        } finally { model.close() }
    }
}

internal fun saveExtension(name: String, node: SemanticsNodeInteraction) {
    val image = node.captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
    val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
    ImageIO.write(output, "png", File("build/reports/ui/$name.png").apply { parentFile.mkdirs() })
}
