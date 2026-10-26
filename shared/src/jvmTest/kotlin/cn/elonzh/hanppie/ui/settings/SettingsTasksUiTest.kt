package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.display.DisplayFixture
import cn.elonzh.hanppie.ui.robot.display.DisplayTarget
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsTasksUiTest {
    @Test fun returningFromAnAlertToOrdinarySettingsClearsTheNestedTask() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val model = testConsoleModel()
            var fromAlert by mutableStateOf(true)
            try {
                setContent { TestWorkbenchTheme { SettingsPage(model, showRobotMaintenance = fromAlert) } }
                onNodeWithTag("health-status").assertExists()
                runOnIdle { fromAlert = false }
                onNodeWithTag("settings-detail-general").assertIsDisplayed()
                onNodeWithTag("health-status").assertDoesNotExist()
                onNodeWithTag("settings-category-robot").performScrollTo().performClick()
                onNodeWithTag("settings-maintenance-health").assertIsDisplayed()
                onNodeWithTag("health-status").assertDoesNotExist()
            } finally { model.close() }
        }

    @Test fun compactChinese() = tasks(844, 390, "zh", 1f, false)
    @Test fun mediumDark() = tasks(1024, 600, "zh", 1f, true)
    @Test fun desktop() = tasks(1280, 720, "zh", 1f, false)
    @Test fun largeEnglish() = tasks(844, 390, "en", 1.4f, true)

    private fun tasks(width: Int, height: Int, language: String, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel()
            val device = DisplayFixture().apply {
                read = { if (it == DisplayTarget.HEADLIGHTS) throw RobotRequestException(RequestProblem.REJECTED, 2) }
            }
            fun capture(name: String) {
                val image = onRoot().captureToImage()
                val pixels = IntArray(image.width * image.height)
                image.readPixels(pixels)
                val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/settings-tasks-$width-$height-$language-$scale-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(bitmap, "png", file)
            }
            fun backToCategories() {
                if (width - 32 < (240 + 480) * scale + 16) onNodeWithTag("settings-back").performClick()
            }
            try {
                waitUntil(timeoutMillis = 5000) { !model.settingsBusy.value }
                model.state.value = model.state.value.copy(connected = true)
                model.robotDisplay.attach(device)
                waitUntil(timeoutMillis = 5000) { !model.robotDisplay.state.value.busy }
                setContent {
                    TestWorkbenchTheme(AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))) {
                        CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                            SettingsPage(model, Modifier.fillMaxSize().padding(16.dp), onAlbum = {}, onWifi = {},
                                onAddressing = {}, onCalibration = {}, onFirmware = {}, onArm = {}, onExtensions = {})
                        }
                    }
                }
                onNodeWithTag("settings-category-robot").performScrollTo().performClick()
                onNodeWithTag("settings-maintenance-health").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                onNodeWithTag("health-battery").assertDoesNotExist()
                onNodeWithTag("power-action-SHUTDOWN").assertDoesNotExist()
                onNodeWithTag("camera-storage-format").assertDoesNotExist()
                capture("maintenance-top")
                onNodeWithTag("settings-firmware-open").performScrollTo().assertIsDisplayed()
                capture("maintenance-service")
                onNodeWithTag("settings-maintenance-lighting").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { model.robotDisplay.state.value.headlightsUnavailable }
                onNodeWithTag("display-lights-unavailable").performScrollTo().assertIsDisplayed()
                onNodeWithTag("display-lights-read").assertDoesNotExist()
                onNodeWithTag("display-lights-apply").assertDoesNotExist()
                onNodeWithTag("display-device-error").assertDoesNotExist()
                capture("lighting-unavailable")
                onNodeWithTag("settings-back").performClick()
                onNodeWithTag("settings-maintenance-lighting").performScrollTo().performClick()
                onNodeWithTag("display-lights-unavailable").performScrollTo().assertIsDisplayed()
                assertEquals(2, device.reads, "One sight query and one lighting query; no retry loop on rejection")
                onNodeWithTag("settings-back").performClick()
                backToCategories()
                onNodeWithTag("settings-category-media").performScrollTo().performClick()
                onNodeWithTag("speaker-volume-slider").performScrollTo().assertIsDisplayed()
                onNodeWithTag("camera-sync-clock").assertDoesNotExist()
                onNodeWithTag("robot-sound-refresh").assertDoesNotExist()
                onNodeWithTag("settings-camera-open").performScrollTo().assertIsDisplayed()
                capture("media")
                backToCategories()
                onNodeWithTag("settings-category-display").performScrollTo().performClick()
                onNodeWithTag("display-lights-read").assertDoesNotExist()
                onNodeWithTag("display-framing-selector").assertIsDisplayed()
                capture("display")
                assertEquals(0, device.writes)
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
