package cn.elonzh.hanppie.ui.settings

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
import cn.elonzh.hanppie.robot.media.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

private class CameraConfigurationFixture : RobotCameraService {
    override val state = MutableStateFlow(CameraState(100, timeSynced = true))
    var antiFlicker = 2
    var dateError = false
    var date = CameraDate(2020, 1, 1, 0, 0, 0)
    override suspend fun setZoom(hundredths: Int) = error("unused")
    override suspend fun formatStorage() { error("unused") }
    override suspend fun getAntiFlicker(): Int = antiFlicker
    override suspend fun setAntiFlicker(mode: AntiFlicker): Int { antiFlicker = mode.wireValue; return antiFlicker }
    override suspend fun getDate(): CameraDate {
        if (dateError) throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
        return date
    }
    override suspend fun syncClock(): CameraDate {
        dateError = false; date = CameraDate(2026, 10, 23, 1, 2, 3); return date
    }
}

class CameraSettingsUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun actualMediaSettingsCanChangeFrequencySyncTimeAndRecoverAnIndependentDateError() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val camera = CameraConfigurationFixture()
        model.state.value = model.state.value.copy(connected = true)
        model.cameraSettings.attach(camera)
        try {
            rule.setContent { TestWorkbenchTheme { SettingsPage(model) } }
            rule.onNodeWithTag("settings-category-media").performScrollTo().performClick()
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("camera-anti-flicker-selector")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("camera-anti-flicker-selector").performScrollTo().performClick()
            rule.onNodeWithContentDescription("camera-anti-flicker-HZ_60").performClick()
            rule.waitUntil(3_000) { camera.antiFlicker == 1 }
            rule.onNodeWithTag("camera-sync-clock").performScrollTo().performClick()
            rule.waitUntil(3_000) { camera.date.year == 2026 }
            rule.onNodeWithText("相机日期：2026-10-23 01:02:03").performScrollTo().assertIsDisplayed()
            camera.dateError = true
            rule.onNodeWithTag("camera-settings-refresh").performScrollTo().performClick()
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("camera-date-error")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("camera-date-value").assertDoesNotExist()
            rule.onNodeWithTag("camera-anti-flicker-selector").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("camera-sync-clock").performScrollTo().performClick()
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("camera-date-value")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("camera-date-error").assertDoesNotExist()
            camera.antiFlicker = -1
            rule.onNodeWithTag("camera-settings-refresh").performScrollTo().performClick()
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("camera-anti-flicker-selector")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("camera-anti-flicker-selector").performScrollTo().assertTextContains("未知", substring = true)
            camera.antiFlicker = 0
            rule.onNodeWithTag("camera-settings-refresh").performScrollTo().performClick()
            rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("camera-anti-flicker-selector")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("camera-anti-flicker-selector").performScrollTo().assertTextContains("自动", substring = true).performClick()
            rule.onNodeWithContentDescription("camera-anti-flicker-AUTO").assertDoesNotExist()
            rule.onNodeWithContentDescription("camera-anti-flicker-HZ_50").performClick()
            rule.runOnIdle { model.cameraSettings.clear(); model.state.value = model.state.value.copy(connected = false) }
            rule.onNodeWithTag("camera-sync-clock").assertDoesNotExist()
            rule.onNodeWithTag("camera-anti-flicker-selector").assertDoesNotExist()
        } finally { model.close() }
    }
}

@OptIn(ExperimentalTestApi::class)
class CameraSettingsLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)

    private fun layout(width: Int, height: Int, language: String = "zh", fontScale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel()
            model.state.value = model.state.value.copy(connected = true)
            model.cameraSettings.attach(CameraConfigurationFixture())
            try {
                setContent { TestWorkbenchTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        Box(Modifier.fillMaxSize().testTag("camera-settings-layout").padding(16.dp)) { SettingsPage(model) }
                    }
                } }
                onNodeWithTag("settings-category-media").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodes(hasTestTag("camera-anti-flicker-selector")).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("camera-anti-flicker-selector").performScrollTo().assertIsDisplayed()
                onNodeWithTag("camera-settings-refresh").performScrollTo().assertIsDisplayed()
                onNodeWithTag("camera-sync-clock").assertIsDisplayed()
                assertTrue(onNodeWithTag("camera-sync-clock").fetchSemanticsNode().boundsInRoot.height >= 48)
                val image = onNodeWithTag("camera-settings-layout").captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/camera-settings-$width-$height-$language.png").apply { parentFile.mkdirs() }
                ImageIO.write(out, "png", file)
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
