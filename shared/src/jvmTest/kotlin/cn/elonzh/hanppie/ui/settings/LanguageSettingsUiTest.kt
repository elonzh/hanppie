package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.ui.app.MemorySettingsStore
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Actual settings page and preference callback; no network, hardware or native audio. */
@OptIn(ExperimentalTestApi::class)
class LanguageSettingsUiTest {
    @Test fun chineseCompactLandscape() = scenario(844, 390, 1f, false, "zh")
    @Test fun chineseMediumDarkLandscape() = scenario(1024, 768, 1f, true, "zh")
    @Test fun chineseDesktopLandscape() = scenario(1280, 720, 1f, false, "zh")
    @Test fun chineseLargeLandscape() = scenario(844, 390, 1.4f, true, "zh")
    @Test fun englishCompactLandscape() = scenario(844, 390, 1f, false, "en")
    @Test fun englishMediumDarkLandscape() = scenario(1024, 768, 1f, true, "en")
    @Test fun englishDesktopLandscape() = scenario(1280, 720, 1f, false, "en")
    @Test fun englishLargeLandscape() = scenario(844, 390, 1.4f, true, "en")

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean, language: String) =
        runDesktopComposeUiTest(width = width, height = height) {
            val (nativeName, languageLabel, systemLabel, resetQuestion, cancelLabel) = when (language) {
                "en" -> listOf("English", "Language", "System default", "Restore all defaults?", "Cancel")
                else -> listOf("简体中文", "语言", "跟随系统", "恢复所有默认设置？", "取消")
            }
            val languageTag = if (language == "en") "en-US" else "zh-CN"
            val store = MemorySettingsStore()
            Localization.initialize("zh-CN", null) { runBlocking { store.saveLanguage(it) } }
            val model = testConsoleModel(settingsStore = store)
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val prefix = "language-$language-$width-$height-$scale-${if (dark) "dark" else "light"}"
            fun capture(name: String, popup: Boolean = false) {
                waitForIdle()
                val node = if (popup) onNode(isRoot() and hasAnyDescendant(hasTestTag("language-$language")))
                    else onNode(isRoot() and hasAnyDescendant(hasTestTag("settings-page")))
                val image = node.captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/$prefix-$name.png"); file.parentFile.mkdirs(); ImageIO.write(bitmap, "png", file)
            }
            fun categories() {
                if (onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) onNodeWithTag("settings-back").performClick()
            }
            try {
                waitUntil(timeoutMillis = 5_000) { !model.settingsBusy.value }
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        TestWorkbenchTheme(appearance) {
                            SettingsPage(model, Modifier.fillMaxSize().padding(20.dp))
                        }
                    }
                }
                onNodeWithTag("settings-category-general").performClick()
                onNodeWithTag("language-selector").assertIsDisplayed().performClick()
                for (choice in listOf("system", "zh", "en")) {
                    onNodeWithTag("language-$choice").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                }
                for (choice in listOf("zh-Hant", "ja", "de", "es", "fr", "ko", "ru")) {
                    onNodeWithTag("language-$choice").assertDoesNotExist()
                }
                capture("options", popup = true)
                onNodeWithTag("language-$language").performScrollTo().performClick()
                onNodeWithText(nativeName).assertIsDisplayed()
                onNodeWithText(languageLabel).assertIsDisplayed()
                capture("selected")
                assertEquals(language, runBlocking { store.loadUi() }.language)
                runOnIdle { Localization.initialize("en-GB", runBlocking { store.loadUi() }.language) { runBlocking { store.saveLanguage(it) } } }
                onNodeWithText(nativeName).assertIsDisplayed()
                assertEquals(languageTag, Localization.languageTag)
                onNodeWithTag("language-selector").performClick(); onNodeWithTag("language-en").performScrollTo().performClick()
                onNodeWithText("Language").assertIsDisplayed()
                onNodeWithTag("language-selector").performClick(); onNodeWithTag("language-zh").performScrollTo().performClick()
                onNodeWithText("语言").assertIsDisplayed()
                runOnIdle { Localization.initialize(if (language == "en") "fr-FR" else "zh-HK", "system") { runBlocking { store.saveLanguage(it) } } }
                onNodeWithText(languageLabel).assertIsDisplayed()
                onNodeWithText(systemLabel).assertIsDisplayed()
                categories(); onNodeWithTag("settings-category-control").performScrollTo().performClick()
                onNodeWithTag("settings-detail-control").assertIsDisplayed(); capture("control")
                categories()
                onNodeWithTag("restore-default-settings").performClick()
                onNodeWithText(resetQuestion).assertIsDisplayed()
                onNodeWithTag("restore-defaults-confirm").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                capture("reset")
                onNodeWithText(cancelLabel).performClick()
                assertEquals("system", Localization.choice)
                onNodeWithText(resetQuestion).assertDoesNotExist()
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
