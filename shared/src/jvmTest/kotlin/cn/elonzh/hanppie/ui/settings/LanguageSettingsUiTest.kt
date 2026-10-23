package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
    @Test fun compactLandscape() = scenario(844, 390, 1f, false)
    @Test fun mediumDarkLandscape() = scenario(1024, 768, 1f, true)
    @Test fun desktopLandscape() = scenario(1280, 720, 1f, false)
    @Test fun largeTraditionalLandscape() = scenario(844, 390, 1.4f, true)

    @Test fun japaneseCompactLandscape() = scenario(844, 390, 1f, false, "ja")
    @Test fun japaneseMediumDarkLandscape() = scenario(1024, 768, 1f, true, "ja")
    @Test fun japaneseDesktopLandscape() = scenario(1280, 720, 1f, false, "ja")
    @Test fun japaneseLargeLandscape() = scenario(844, 390, 1.4f, true, "ja")

    @Test fun germanCompactLandscape() = scenario(844, 390, 1f, false, "de")
    @Test fun germanMediumDarkLandscape() = scenario(1024, 768, 1f, true, "de")
    @Test fun germanDesktopLandscape() = scenario(1280, 720, 1f, false, "de")
    @Test fun germanLargeLandscape() = scenario(844, 390, 1.4f, true, "de")

    @Test fun spanishCompactLandscape() = scenario(844, 390, 1f, false, "es")
    @Test fun spanishMediumDarkLandscape() = scenario(1024, 768, 1f, true, "es")
    @Test fun spanishDesktopLandscape() = scenario(1280, 720, 1f, false, "es")
    @Test fun spanishLargeLandscape() = scenario(844, 390, 1.4f, true, "es")

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean, language: String = "zh-Hant") =
        runDesktopComposeUiTest(width = width, height = height) {
            val (nativeName, languageLabel, systemLabel, resetQuestion, cancelLabel) = when (language) {
                "ja" -> listOf("日本語", "言語", "システムに従う", "すべての設定を既定値に戻しますか？", "キャンセル")
                "de" -> listOf("Deutsch", "Sprache", "Systemsprache", "Alle Einstellungen zurücksetzen?", "Abbrechen")
                "es" -> listOf("Español", "Idioma", "Idioma del sistema", "¿Restablecer todos los ajustes?", "Cancelar")
                else -> listOf("繁體中文", "語言", "跟隨系統", "恢復所有預設設定？", "取消")
            }
            val languageTag = when (language) { "ja" -> "ja-JP"; "de" -> "de-DE"; "es" -> "es-ES"; else -> "zh-Hant-TW" }
            val captureLanguage = when (language) { "ja" -> "japanese"; "de" -> "german"; "es" -> "spanish"; else -> "traditional" }
            val store = MemorySettingsStore()
            Localization.initialize("zh-CN", null) { runBlocking { store.saveLanguage(it) } }
            val model = testConsoleModel(settingsStore = store)
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val prefix = "language-${if (language == "zh-Hant") "" else "$language-"}$width-$height-$scale-${if (dark) "dark" else "light"}"
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
                onNodeWithTag("language-zh").assertIsDisplayed()
                onNodeWithTag("language-en").assertIsDisplayed()
                onNodeWithTag("language-zh-Hant").assertIsDisplayed()
                onNodeWithTag("language-ja").assertIsDisplayed()
                onNodeWithTag("language-de").assertIsDisplayed()
                onNodeWithTag("language-es").assertIsDisplayed(); capture("options", popup = true)
                onNodeWithTag("language-$language").performClick()
                onNodeWithText(nativeName).assertIsDisplayed()
                onNodeWithText(languageLabel).assertIsDisplayed()
                onNodeWithText("语言").assertDoesNotExist(); capture(captureLanguage)
                assertEquals(language, runBlocking { store.loadUi() }.language)
                runOnIdle { Localization.initialize("en-GB", runBlocking { store.loadUi() }.language) { runBlocking { store.saveLanguage(it) } } }
                onNodeWithText(nativeName).assertIsDisplayed()
                assertEquals(languageTag, Localization.languageTag)
                onNodeWithTag("language-selector").performClick(); onNodeWithTag("language-en").performClick()
                onNodeWithText("Language").assertIsDisplayed()
                onNodeWithTag("language-selector").performClick(); onNodeWithTag("language-zh").performClick()
                onNodeWithText("语言").assertIsDisplayed()
                runOnIdle { Localization.initialize(if (language == "zh-Hant") "zh-HK" else languageTag, "system") { runBlocking { store.saveLanguage(it) } } }
                onNodeWithText(languageLabel).assertIsDisplayed()
                onNodeWithText(systemLabel).assertIsDisplayed()
                categories(); onNodeWithTag("settings-category-control").performScrollTo().performClick()
                onNodeWithTag("settings-detail-control").assertIsDisplayed(); capture("control")
                categories()
                onNodeWithContentDescription("restore-default-settings").performClick()
                onNodeWithText(resetQuestion).assertIsDisplayed()
                onNodeWithTag("restore-defaults-confirm").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                if (language == "de" || language == "es") {
                    val confirmText = if (language == "es") "Restablecer" else "Zurücksetzen"
                    val layouts = mutableListOf<TextLayoutResult>()
                    onNode(hasText(confirmText) and hasAnyAncestor(hasTestTag("restore-defaults-confirm")), useUnmergedTree = true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue(layouts.isNotEmpty())
                    assertTrue(layouts.all { it.lineCount == 1 }, "The complete reset action must fit without splitting its word")
                }
                capture("reset")
                onNodeWithText(cancelLabel).performClick()
                assertEquals("system", Localization.choice)
                onNodeWithText(resetQuestion).assertDoesNotExist()
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
