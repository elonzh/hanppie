package cn.elonzh.hanppie.ui.robot.arm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import cn.elonzh.hanppie.robot.extensions.arm.*
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.remote.RemotePage
import cn.elonzh.hanppie.ui.settings.*
import kotlinx.coroutines.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RobotArmLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun englishLargeTextDark() = layout(844, 390, "en", 1.4f)

    private fun layout(width: Int, height: Int, language: String = "zh", scale: Float = 1f) = runDesktopComposeUiTest(width = width, height = height) {
        Localization.initialize(language, null)
        val service = ArmServiceFixture(); val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(armSession(service))))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val appearance = AppearanceController(AppearanceSettings(if (language == "en") NightMode.DARK else NightMode.LIGHT))
        var remote by mutableStateOf(false)
        val prefix = "arm-$width-$height-$language"
        try {
            setContent { TestWorkbenchTheme(appearance) { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                if (remote) RemotePage(model, videoContent = { Box(Modifier.fillMaxSize().background(Color(0xff27313a))) }, manageSession = false)
                else RobotArmPage(model, onBack = {}, onAddressing = {}, onCalibration = {})
            } } }
            saveArm("$prefix-offline", onNodeWithTag("arm-page"))
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && armIdle(model) }
            model.robotArm.setActive(true); waitUntil(timeoutMillis = 3000) { service.state.value.observation == ArmObservation.ACTIVE && armIdle(model) }
            onNodeWithTag("arm-main-3").performScrollTo().performClick()
            onNodeWithTag("arm-bind").performScrollTo().assertIsDisplayed().assertIsEnabled()
            saveArm("$prefix-installation", onNodeWithTag("arm-page"))
            onNodeWithTag("arm-guide-toggle").performScrollTo().performClick()
            onNodeWithTag("arm-guide").performScrollTo().assertIsDisplayed()
            saveArm("$prefix-guide", onNodeWithTag("arm-page"))
            onNodeWithTag("arm-load-on").performScrollTo().assertIsDisplayed().assertIsEnabled()
            saveArm("$prefix-load", onNodeWithTag("arm-page"))
            onNodeWithTag("arm-load-on").performClick(); onNodeWithTag("arm-confirm").assertIsDisplayed()
            saveArm("$prefix-confirm", onNode(isRoot() and hasAnyDescendant(hasTestTag("arm-confirm"))))
            onNodeWithTag("arm-confirm-cancel").performClick()
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("arm-confirm").fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("arm-details-toggle").performScrollTo().performClick()
            onNodeWithTag("arm-position").performScrollTo().assertIsDisplayed()
            if (width == 1280) saveArm("$prefix-data", onNodeWithTag("arm-page"))
            assertTrue(service.calls.none { it.startsWith("bind:") || it.startsWith("mode:") })
            scope.launch { while (isActive) { model.videoFrames.publish(model.state.value.connectedAddress, ImageBitmap(16, 9)); delay(100) } }
            waitUntil(timeoutMillis = 3000) { model.videoFrames.isFresh(model.state.value.connectedAddress) }
            model.enableRemote(); waitUntil(timeoutMillis = 3000) { model.remoteEnabled.value && !model.state.value.busy }
            runOnIdle { remote = true }
            onNodeWithTag("remote-arm-toggle").performClick()
            onNodeWithTag("arm-hold-forward").assertIsDisplayed().assertIsEnabled()
            onNodeWithTag("arm-hold-close").assertIsDisplayed().assertIsEnabled()
            saveArm("$prefix-remote", onNodeWithTag("remote-surface"))
            runOnIdle { service.state.value = service.state.value.copy(position = ArmPosition(1, 100, 120)) }
            waitUntil(timeoutMillis = 3000) { model.robotArm.state.value.task.position?.limitX == true }
            saveArm("$prefix-remote-limit", onNodeWithTag("remote-surface"))
            runOnIdle { service.state.value = service.state.value.copy(positionFresh = false, clawFresh = false) }
            waitUntil(timeoutMillis = 3000) { !model.robotArm.state.value.task.positionFresh }
            onNodeWithTag("arm-hold-forward").assertIsNotEnabled(); onNodeWithTag("arm-hold-close").assertIsNotEnabled()
            if (language == "en") {
                saveArm("$prefix-remote-stale", onNodeWithTag("remote-surface"))
                runOnIdle { service.state.value = service.state.value.copy(observation = ArmObservation.FAILED) }
                waitUntil(timeoutMillis = 3000) { model.robotArm.state.value.task.observation == ArmObservation.FAILED }
                onNodeWithTag("arm-retry-status").assertIsDisplayed().assertIsEnabled()
                saveArm("$prefix-remote-retry", onNodeWithTag("remote-surface"))
            }
        } finally { scope.cancel(); model.close() }
    }
}

private fun saveArm(name: String, node: SemanticsNodeInteraction) {
    val image = node.captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
    val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
    ImageIO.write(output, "png", File("build/reports/ui/$name.png").apply { parentFile.mkdirs() })
}
