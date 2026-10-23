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
    @Test fun compactLandscape() = scenario(844, 390, 1f, false)
    @Test fun mediumDarkLandscape() = scenario(1024, 768, 1f, true)
    @Test fun desktopLandscape() = scenario(1280, 720, 1f, false)
    @Test fun largeTraditionalLandscape() = scenario(844, 390, 1.4f, true)

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            val store = MemorySettingsStore()
            Localization.initialize("zh-CN", null) { runBlocking { store.saveLanguage(it) } }
            val model = testConsoleModel(settingsStore = store)
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val prefix = "language-$width-$height-$scale-${if (dark) "dark" else "light"}"
            fun capture(name: String, popup: Boolean = false) {
                waitForIdle()
                val node = if (popup) onNode(isRoot() and hasAnyDescendant(hasTestTag("language-zh-Hant")))
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
                onNodeWithTag("language-zh-Hant").assertIsDisplayed(); capture("options", popup = true)
                onNodeWithTag("language-zh-Hant").performClick()
                onNodeWithText("繁體中文").assertIsDisplayed()
                onNodeWithText("語言").assertIsDisplayed()
                onNodeWithText("语言").assertDoesNotExist(); capture("traditional")
                assertEquals("zh-Hant", runBlocking { store.loadUi() }.language)
                runOnIdle { Localization.initialize("en-GB", runBlocking { store.loadUi() }.language) { runBlocking { store.saveLanguage(it) } } }
                onNodeWithText("繁體中文").assertIsDisplayed()
                assertEquals("zh-Hant-TW", Localization.languageTag)
                onNodeWithTag("language-selector").performClick(); onNodeWithTag("language-en").performClick()
                onNodeWithText("Language").assertIsDisplayed()
                onNodeWithTag("language-selector").performClick(); onNodeWithTag("language-zh").performClick()
                onNodeWithText("语言").assertIsDisplayed()
                runOnIdle { Localization.initialize("zh-HK", "system") { runBlocking { store.saveLanguage(it) } } }
                onNodeWithText("語言").assertIsDisplayed()
                onNodeWithText("跟隨系統").assertIsDisplayed()
                categories(); onNodeWithTag("settings-category-control").performScrollTo().performClick()
                onNodeWithTag("settings-detail-control").assertIsDisplayed(); capture("control")
                categories()
                onNodeWithContentDescription("restore-default-settings").performClick()
                onNodeWithText("恢復所有預設設定？").assertIsDisplayed(); capture("reset")
                onNodeWithText("取消").performClick()
                assertEquals("system", Localization.choice)
                onNodeWithText("恢復所有預設設定？").assertDoesNotExist()
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
