package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.ui.app.MemorySettingsStore
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsNavigationUiTest {
    @Test fun phoneUsesCategoryPagesAndPreservesDraftUntilSave() = runDesktopComposeUiTest(width = 393, height = 740) {
        Localization.initialize("zh", null)
        val store = MemorySettingsStore()
        val model = testConsoleModel(settingsStore = store)
        try {
            waitUntil(timeoutMillis = 3_000) { !model.settingsBusy.value }
            setContent { WorkbenchTheme { SettingsPage(model, Modifier.fillMaxSize().padding(horizontal = 20.dp)) } }
            onNodeWithTag("settings-category-model").assertIsDisplayed()
            onNodeWithText("API Key").assertDoesNotExist()
            snapshot("settings-phone-categories", onRoot())
            onNodeWithTag("settings-category-model").performClick()
            onNodeWithTag("settings-category-control").assertDoesNotExist()
            onNodeWithContentDescription("model-provider-selector").performClick()
            onNodeWithContentDescription("model-provider-DEEPSEEK").performClick()
            onNodeWithTag("settings-save").assertIsDisplayed()
            snapshot("settings-phone-model", onRoot())
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-category-general").performClick()
            onNodeWithText("API Key").assertDoesNotExist()
            onNodeWithTag("settings-save").assertDoesNotExist()
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-category-model").performClick()
            runOnIdle { assertEquals(ModelProviderPreset.DEEPSEEK, model.modelSettings.value.provider) }
            onNodeWithTag("settings-save").performClick()
            waitUntil(timeoutMillis = 3_000) { !model.settingsBusy.value }
            runOnIdle { assertEquals(ModelProviderPreset.DEEPSEEK, runBlocking { store.load() }.model.provider) }
        } finally { model.close() }
    }

    @Test fun wideLayoutKeepsSelectionWhenResizedAndSeparatesLights() = runDesktopComposeUiTest(width = 1080, height = 740) {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val width = mutableStateOf(1040.dp)
        try {
            setContent { WorkbenchTheme { Box(Modifier.width(width.value).fillMaxHeight()) { SettingsPage(model, Modifier.fillMaxSize().padding(horizontal = 20.dp)) } } }
            onNodeWithTag("settings-detail-general").assertIsDisplayed()
            onNodeWithTag("settings-category-control").performClick()
            onNodeWithContentDescription("gimbal-sensitivity-selector").assertIsDisplayed()
            onNodeWithContentDescription("remote-led-standby").assertDoesNotExist()
            snapshot("settings-desktop-control", onRoot())
            onNodeWithTag("settings-category-lights").performClick()
            onNodeWithContentDescription("remote-led-standby").assertIsDisplayed()
            onNodeWithContentDescription("gimbal-sensitivity-selector").assertDoesNotExist()
            snapshot("settings-desktop-lights", onRoot())
            runOnIdle { width.value = 393.dp }
            onNodeWithTag("settings-detail-lights").assertIsDisplayed()
            onNodeWithTag("settings-category-model").assertDoesNotExist()
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-category-model").assertIsDisplayed()
        } finally { model.close() }
    }

    @Test fun largeEnglishPhoneCanNavigateWithoutAccordionOrClippedActions() = runDesktopComposeUiTest(width = 320, height = 640) {
        Localization.initialize("en", null)
        val model = testConsoleModel()
        try {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                    WorkbenchTheme(AppearanceController(AppearanceSettings(NightMode.DARK))) {
                        SettingsPage(model, Modifier.fillMaxSize().padding(horizontal = 20.dp))
                    }
                }
            }
            snapshot("settings-small-dark-categories", onRoot())
            onNodeWithTag("settings-category-shortcuts").performScrollTo().performClick()
            onNodeWithTag("settings-back").assertIsDisplayed()
            onNodeWithTag("settings-save").assertIsDisplayed()
            snapshot("settings-small-dark-shortcuts", onRoot())
        } finally { model.close(); Localization.initialize("zh", null) }
    }

    private fun snapshot(name: String, node: SemanticsNodeInteraction) {
        val image = node.captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        bitmap.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val output = File("build/reports/ui/$name.png")
        output.parentFile.mkdirs()
        ImageIO.write(bitmap, "png", output)
    }
}
