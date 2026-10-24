package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.ui.app.MemorySettingsStore
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme as WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.AppLanguage
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsNavigationUiTest {
    @Test
    fun dataDirectoryShowsActualPathAndAllowsRetryAfterFailure() =
        runDesktopComposeUiTest(width = 700, height = 390) {
            Localization.initialize("zh", null)
            var attempts = 0
            val directory =
                DataDirectoryAccess("/Users/example/Library/Application Support/cn.elonzh.hanppie") {
                    attempts++
                    if (attempts == 1) error("File manager unavailable")
                }
            setContent {
                WorkbenchTheme {
                    CompositionLocalProvider(LocalDataDirectoryAccess provides directory) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) { DataDirectorySetting() }
                    }
                }
            }
            onNodeWithTag("data-directory-path").assertTextEquals(directory.path)
                .assertIsDisplayed()
            snapshot("settings-data-directory", onRoot())
            onNodeWithTag("open-data-directory").performClick()
            onNodeWithText("无法打开目录，请复制路径并在文件管理器中打开。").assertIsDisplayed()
            snapshot("settings-data-directory-narrow", onRoot())
            onNodeWithTag("open-data-directory").assertIsEnabled().performClick()
            onNodeWithText("无法打开目录，请复制路径并在文件管理器中打开。").assertDoesNotExist()
            runOnIdle { assertEquals(2, attempts) }
        }

    @Test
    fun dataDirectoryIsAbsentWithoutPlatformAccess() = runDesktopComposeUiTest(width = 844, height = 390) {
        setContent { WorkbenchTheme { DataDirectorySetting() } }
        onNodeWithTag("data-directory-path").assertDoesNotExist()
        onNodeWithTag("open-data-directory").assertDoesNotExist()
    }

    @Test fun compactLandscapeUsesCategoryPagesAndPreservesDraftUntilSave() = runDesktopComposeUiTest(width = 700, height = 390) {
        Localization.initialize("zh", null)
        val store = MemorySettingsStore()
        val model = testConsoleModel(settingsStore = store)
        try {
            waitUntil(timeoutMillis = 3_000) { !model.settingsBusy.value }
            setContent { WorkbenchTheme { SettingsPage(model, Modifier.fillMaxSize().padding(horizontal = 20.dp)) } }
            onNodeWithTag("settings-category-model").assertIsDisplayed()
            onNodeWithText("API Key").assertDoesNotExist()
            snapshot("settings-compact-categories", onRoot())
            onNodeWithTag("settings-category-model").performScrollTo().performClick()
            onNodeWithTag("settings-category-control").assertDoesNotExist()
            onNodeWithTag("model-provider-selector").performClick()
            onNodeWithTag("model-provider-DEEPSEEK").performClick()
            onNodeWithTag("settings-save").assertIsDisplayed()
            snapshot("settings-compact-model", onRoot())
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-category-general").performScrollTo().performClick()
            onNodeWithText("API Key").assertDoesNotExist()
            onNodeWithTag("settings-save").assertDoesNotExist()
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-category-model").performScrollTo().performClick()
            runOnIdle { assertEquals(ModelProviderPreset.DEEPSEEK, model.modelSettings.value.provider) }
            onNodeWithTag("settings-save").performClick()
            waitUntil(timeoutMillis = 3_000) { !model.settingsBusy.value }
            runOnIdle { assertEquals(ModelProviderPreset.DEEPSEEK, runBlocking { store.load() }.model.provider) }
        } finally { model.close() }
    }

    @Test fun wideLayoutKeepsSelectionWhenResizedAndSeparatesLights() = runDesktopComposeUiTest(width = 1080, height = 600) {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val width = mutableStateOf(1040.dp)
        try {
            setContent { WorkbenchTheme { Box(Modifier.width(width.value).height(390.dp)) { SettingsPage(model, Modifier.fillMaxSize().padding(horizontal = 20.dp)) } } }
            onNodeWithTag("settings-detail-general").assertIsDisplayed()
            onNodeWithTag("settings-category-control").performScrollTo().performClick()
            onNodeWithTag("settings-gamepad-open").assertDoesNotExist()
            onNodeWithTag("gamepad-response-defaults").assertDoesNotExist()
            onNodeWithTag("gimbal-sensitivity-selector").assertIsDisplayed()
            onNodeWithTag("remote-led-standby").assertDoesNotExist()
            snapshot("settings-desktop-control", onRoot())
            onNodeWithTag("settings-category-lights").performScrollTo().performClick()
            onNodeWithTag("remote-led-standby").assertIsDisplayed()
            onNodeWithTag("gimbal-sensitivity-selector").assertDoesNotExist()
            snapshot("settings-desktop-lights", onRoot())
            runOnIdle { width.value = 700.dp }
            onNodeWithTag("settings-detail-lights").assertIsDisplayed()
            onNodeWithTag("settings-category-model").assertDoesNotExist()
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings-category-model").assertIsDisplayed()
        } finally { model.close() }
    }

    @Test fun mediaSettingsCanBeSelectedAndSaved() = runDesktopComposeUiTest(width = 1080, height = 600) {
        Localization.initialize("zh", null)
        val store = MemorySettingsStore()
        val model = testConsoleModel(settingsStore = store)
        try {
            waitUntil(timeoutMillis = 3_000) { !model.settingsBusy.value }
            setContent { WorkbenchTheme { Box(Modifier.fillMaxSize()) { SettingsPage(model, Modifier.fillMaxSize().padding(horizontal = 20.dp)) } } }
            onNodeWithTag("settings-category-media").performScrollTo().performClick()
            onNodeWithTag("settings-detail-media").assertIsDisplayed()
            onNodeWithTag("video-resolution-selector").assertIsDisplayed()
            onNodeWithTag("speaker-volume-card").assertIsDisplayed()
            onNodeWithTag("speaker-volume-slider").assertIsDisplayed()
            snapshot("settings-desktop-media", onRoot())
            onNodeWithTag("video-resolution-selector").performClick()
            onNodeWithTag("video-resolution-R1080P").performClick()
            onNodeWithTag("settings-save").performClick()
            waitUntil(timeoutMillis = 3_000) { !model.settingsBusy.value }
            runOnIdle {
                assertEquals(cn.elonzh.hanppie.robot.media.VideoResolution.R1080P, model.mediaSettings.value.videoResolution)
                assertEquals(cn.elonzh.hanppie.robot.media.VideoResolution.R1080P, runBlocking { store.load() }.media.videoResolution)
            }
        } finally { model.close() }
    }

    @Test fun largeEnglishLandscapeCanNavigateAndCaptureWithoutClippedActions() = runDesktopComposeUiTest(width = 844, height = 390) {
        Localization.initialize("en", null)
        val model = testConsoleModel()
        try {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.4f)) {
                    WorkbenchTheme(AppearanceController(AppearanceSettings(NightMode.DARK))) {
                        SettingsPage(model, Modifier.fillMaxSize().padding(horizontal = 20.dp))
                    }
                }
            }
            snapshot("settings-landscape-dark-categories", onRoot())
            onNodeWithTag("settings-category-shortcuts").performScrollTo().performClick()
            onNodeWithTag("settings-back").assertIsDisplayed()
            onNodeWithTag("settings-save").assertIsDisplayed()
            snapshot("settings-landscape-dark-shortcuts", onRoot())
            val before = model.controlSettings.value.shortcuts
            onNode(hasText("W") and hasClickAction()).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            snapshot("settings-landscape-dark-shortcut-capture", onRoot())
            onRoot().performKeyInput { pressKey(Key.Escape) }
            assertEquals(before, model.controlSettings.value.shortcuts)
            onNode(hasText("W") and hasClickAction()).performClick()
            onRoot().performKeyInput { pressKey(Key.S) }
            assertEquals(ControlKey.S, model.controlSettings.value.shortcuts[ControlAction.Forward].key)
            assertEquals(ControlKey.W, model.controlSettings.value.shortcuts[ControlAction.Backward].key)
            onNode(hasText("S") and hasClickAction()).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            snapshot("settings-landscape-dark-shortcut-assigned", onRoot())
        } finally { model.close(); Localization.initialize("zh", null) }
    }

    @Test fun aboutPageShowsBuildInfoAndHidesSaveButton() = runDesktopComposeUiTest(width = 700, height = 390) {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        try {
            setContent { WorkbenchTheme { CompositionLocalProvider(LocalDensity provides Density(1f, 1.4f)) {
                SettingsPage(model, Modifier.fillMaxSize().padding(horizontal = 20.dp))
            } } }
            onNodeWithTag("settings-category-about").performScrollTo().performClick()
            for (language in AppLanguage.entries) {
                runOnIdle { Localization.select(language.preference) }
                onNodeWithTag("about-settings-content").assertIsDisplayed()
                onNodeWithTag("settings-save").assertDoesNotExist()
                onNodeWithText(if (language == AppLanguage.ENGLISH) "Build Information" else "构建信息").performScrollTo().assertIsDisplayed()
                onNodeWithTag("copy-diagnostic-info").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                snapshot("settings-about-${language.preference}-build", onRoot())
                onNodeWithText(if (language == AppLanguage.ENGLISH) "Released under" else "开源协议发布", substring = true)
                    .performScrollTo().assertIsDisplayed()
                snapshot("settings-about-${language.preference}-footer", onRoot())
            }
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
