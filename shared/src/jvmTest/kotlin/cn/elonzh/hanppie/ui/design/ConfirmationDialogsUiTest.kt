package cn.elonzh.hanppie.ui.design

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.i18n.*
import cn.elonzh.hanppie.ui.robot.maintenance.*
import cn.elonzh.hanppie.ui.settings.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Actual settings and maintenance confirmations at the dialog's desktop-height boundary. */
@OptIn(ExperimentalTestApi::class)
class ConfirmationDialogsUiTest {
    @Test fun compactLandscape() = scenario(844,390,1f,false,listOf(AppLanguage.SIMPLIFIED_CHINESE))
    @Test fun tabletDark() = scenario(1024,768,1f,true,listOf(AppLanguage.SIMPLIFIED_CHINESE))
    @Test fun desktopLandscape() = scenario(1280,720,1f,false,listOf(AppLanguage.SIMPLIFIED_CHINESE))
    @Test fun minimumLandscapeWithLargeText() = scenario(844,480,1.4f,true,AppLanguage.entries)

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean, languages: List<AppLanguage>) =
        runDesktopComposeUiTest(width = width, height = height) {
            val service = CalibrationServiceFixture()
            val session = RobotSessionFixture().apply { calibration = service; health.value = RobotHealth(modules = emptyList()) }
            val store = MemorySettingsStore()
            val model = testConsoleModel(settingsStore = store, robotRuntime = AddressingRuntime(listOf(session)))
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            var calibration by mutableStateOf(false)
            fun picture(name: String, action: String) {
                val image = onNode(isRoot() and hasAnyDescendant(hasTestTag(action))).captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val out = BufferedImage(image.width,image.height,BufferedImage.TYPE_INT_ARGB)
                out.setRGB(0,0,image.width,image.height,pixels,0,image.width)
                val file = File("build/reports/ui/confirmation-$width-$height-$scale-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(out,"png",file)
            }
            fun checkSummary(text: String) {
                val layouts = mutableListOf<TextLayoutResult>()
                onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue(layouts.isNotEmpty())
                for (layout in layouts) {
                    assertFalse(layout.didOverflowWidth)
                    assertEquals(text.length,layout.getLineEnd(layout.lineCount-1))
                    assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
                }
            }
            try {
                Localization.initialize("de","de")
                waitUntil(timeoutMillis=5_000) { !model.settingsBusy.value }
                setContent { TestWorkbenchTheme(appearance) {
                    CompositionLocalProvider(LocalDensity provides Density(1f,scale)) {
                        Box(Modifier.fillMaxSize().padding(16.dp)) {
                            if(calibration) RobotCalibrationPage(model,onBack={}) else SettingsPage(model)
                        }
                    }
                } }
                for(language in languages) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithTag("restore-default-settings").performClick()
                    if(language==AppLanguage.GERMAN) picture("${language.preference}-reset-top","restore-defaults-confirm")
                    checkSummary(tr(Res.string.restore_default_settings_summary))
                    onNodeWithTag("restore-defaults-confirm").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    picture("${language.preference}-reset","restore-defaults-confirm")
                    onNodeWithText(tr(Res.string.cancel)).performClick()
                    waitUntil(timeoutMillis=5_000) { onAllNodesWithTag("restore-defaults-confirm").fetchSemanticsNodes().isEmpty() }
                    assertNull(model.settingsMessage.value)
                }
                model.connect("127.0.0.1","12345678")
                waitUntil(timeoutMillis=5_000) { model.state.value.connected && !model.state.value.busy }
                runOnIdle { calibration=true }
                onNodeWithTag("calibration-kind-chassis_and_gimbal").performScrollTo().performClick()
                for(language in languages) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithTag("calibration-start").performScrollTo().performClick()
                    if(language==AppLanguage.GERMAN) picture("${language.preference}-calibration-top","calibration-confirm")
                    checkSummary(tr(Res.string.calibration_start_confirm))
                    onNodeWithTag("calibration-confirm").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    picture("${language.preference}-calibration","calibration-confirm")
                    onNodeWithTag("calibration-confirm-cancel").performClick()
                    waitUntil(timeoutMillis=5_000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() }
                    assertTrue(service.calls.isEmpty(), "Cancel must not send a calibration command")
                }
            } finally { model.close(); Localization.initialize("zh",null) }
        }
}
