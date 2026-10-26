package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.remote.ChassisParameter
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.remote.MotionFixture
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Real settings navigation and root controller; all device values are synthetic fixtures. */
@OptIn(ExperimentalTestApi::class)
class MotionSettingsUiTest {
    @Test fun shortLandscape() = scenario(844, 390, "zh", 1f, false)
    @Test fun mediumDarkLandscape() = scenario(1024, 600, "zh", 1f, true)
    @Test fun desktopLandscape() = scenario(1280, 720, "zh", 1f, false)
    @Test fun largeChineseLandscape() = scenario(844, 390, "zh", 1.4f, true)
    @Test fun largeEnglishLandscape() = scenario(844, 390, "en", 1.4f, false)

    private fun scenario(width: Int, height: Int, language: String, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, language)
            val peer = MotionFixture(); val session = RobotSessionFixture().apply { motion = peer }
            val model = testConsoleModel(robotRuntime = powerRuntime(listOf(session)))
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val prefix = "motion-settings-$width-$height-$language-$scale-${if (dark) "dark" else "light"}"
            fun capture(state: String) {
                waitForIdle()
                val image = onRoot().captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/$prefix-$state.png"); file.parentFile.mkdirs(); ImageIO.write(output, "png", file)
            }
            try {
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5_000) { model.state.value.connected && !model.state.value.busy && !model.settingsBusy.value }
                setContent {
                    WorkbenchTheme(appearance) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                            Box(Modifier.fillMaxSize().padding(16.dp)) { SettingsPage(model) }
                        }
                    }
                }
                onNodeWithTag("settings-category-control").performScrollTo().performClick()

                onNodeWithTag("input-profile-selector").performScrollTo().performClick()
                onNodeWithTag("input-profile-${language == "en"}").performClick()
                onNodeWithTag("input-response-selector").performClick()
                onNodeWithTag("input-response-CUSTOM").performClick()
                onNodeWithTag("input-axis-FORWARD").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(68f) }
                val profiles = model.controlSettings.value.inputSensitivities
                assertEquals(68, (if (language == "en") profiles.keyboard else profiles.screen).custom.forward)
                assertEquals(SensitivityPreset.STANDARD, (if (language == "en") profiles.screen else profiles.keyboard).preset)
                onNodeWithTag("input-response-selector").performScrollTo()
                capture("input-top")
                onNodeWithTag("input-axis-PITCH").performScrollTo().assertIsDisplayed()
                capture("input-bottom")
                onNodeWithTag("settings-save").performClick()
                waitUntil(timeoutMillis = 5_000) { !model.settingsBusy.value }
                assertEquals(0, peer.writes, "Saving terminal preferences must not write robot settings")

                onNodeWithTag("settings-motion-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { model.robotMotion.state.value.configuration != null && !model.state.value.busy }
                onNodeWithTag("motion-write-level").performScrollTo().assertIsNotEnabled()
                capture("device-preset")
                onNodeWithTag("motion-level-selector").performScrollTo().performClick()
                onNodeWithTag("motion-level-4").performClick()
                onNodeWithTag("motion-write-level").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { model.robotMotion.state.value.confirmed && !model.state.value.busy }
                for (parameter in ChassisParameter.entries) {
                    onNodeWithTag("motion-parameter-selector").performScrollTo().performClick()
                    onNodeWithTag("motion-parameter-${parameter.name}").performClick()
                    onNodeWithTag("motion-parameter-range").performScrollTo().assertIsDisplayed()
                }
                onNodeWithTag("motion-parameter-selector").performScrollTo().performClick()
                onNodeWithTag("motion-parameter-FORWARD_SPEED").performClick()
                onNodeWithTag("motion-parameter-value").performScrollTo().performTextReplacement("99")
                onNodeWithTag("motion-write-parameter").performScrollTo().assertIsNotEnabled()
                onNodeWithTag("motion-parameter-value").performScrollTo().performTextReplacement("0.75")
                onNodeWithTag("motion-write-parameter").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { peer.changed.size == 1 && model.robotMotion.state.value.confirmed && !model.state.value.busy }
                assertEquals(.75f, peer.configuration.parameters.getValue(ChassisParameter.FORWARD_SPEED).value)
                assertEquals(ControlSettings().translationSpeeds, model.controlSettings.value.translationSpeeds)
                onNodeWithTag("motion-parameter-selector").performScrollTo()
                capture("device-custom")

                peer.write = { dispatched -> dispatched(); throw RobotRequestException(RequestProblem.TIMED_OUT) }
                onNodeWithTag("motion-parameter-value").performScrollTo().performTextReplacement("0.8")
                onNodeWithTag("motion-write-parameter").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { model.robotMotion.state.value.uncertain && !model.state.value.busy }
                onNodeWithTag("motion-settings-error").performScrollTo().assertIsDisplayed()
                onNodeWithTag("motion-write-parameter").assertDoesNotExist()
                capture("error")
                val gate = CompletableDeferred<Unit>()
                peer.read = { gate.await(); peer.configuration }
                onNodeWithTag("motion-refresh").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { model.robotMotion.state.value.busy }
                onNodeWithTag("motion-cancel-read").performScrollTo().assertIsDisplayed()
                capture("reading")
                onNodeWithTag("motion-cancel-read").performClick()
                waitUntil(timeoutMillis = 5_000) { !model.robotMotion.state.value.busy && !model.state.value.busy }
                assertNull(model.robotMotion.state.value.configuration)
                runBlocking { model.pauseConnection() }
                waitUntil(timeoutMillis = 5_000) { !model.state.value.connected }
                onNodeWithTag("motion-settings-title").performScrollTo()
                capture("disconnected")
            } finally { model.close() }
        }
}
