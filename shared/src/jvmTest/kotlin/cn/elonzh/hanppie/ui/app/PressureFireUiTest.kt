package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.display.displayFixtureVideo
import cn.elonzh.hanppie.ui.robot.remote.RemotePage
import cn.elonzh.hanppie.ui.robot.remote.input.LocalPressureTouchAvailable
import cn.elonzh.hanppie.ui.settings.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Capability, settings and default-force touch coverage; this does not inject native UIKit force. */
@OptIn(ExperimentalTestApi::class)
class PressureFireUiTest {
    @Test fun compactLandscape() = scenario(844, 390, "zh", 1f, false)
    @Test fun tabletDarkLandscape() = scenario(1024, 768, "zh", 1f, true)
    @Test fun desktopLandscape() = scenario(1280, 720, "zh", 1f, false)
    @Test fun largeNineLanguageLandscape() = scenario(844, 390, "en", 1.4f, true, allLanguages = true)

    private class InputLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    }

    private fun scenario(width: Int, height: Int, language: String, scale: Float, dark: Boolean, allLanguages: Boolean = false) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, language)
            val store = MemorySettingsStore()
            val session = RobotSessionFixture()
            val model = testConsoleModel(settingsStore = store, robotRuntime = powerRuntime(listOf(session)))
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val video = displayFixtureVideo()
            var settings by mutableStateOf(true)
            var available by mutableStateOf(false)
            val capability: () -> Boolean = { available }
            fun capture(name: String) {
                waitForIdle()
                val image = onRoot().captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/pressure-$width-$height-$language-$scale-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(output, "png", file)
            }
            try {
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5_000) { model.state.value.connected && !model.state.value.busy && !model.settingsBusy.value }
                setContent {
                    LaunchedEffect(model) { while (true) { model.videoFrames.publish(model.state.value.connectedAddress, video); delay(50) } }
                    TestWorkbenchTheme(appearance) {
                        val owner = remember { InputLifecycle() }
                        CompositionLocalProvider(LocalPressureTouchAvailable provides capability, LocalLifecycleOwner provides owner,
                            LocalDensity provides Density(LocalDensity.current.density, scale)) {
                            if (settings) SettingsPage(model, Modifier.fillMaxSize().padding(16.dp))
                            else RemotePage(model, videoContent = { Image(video, "Synthetic pressure fixture", Modifier.fillMaxSize()) })
                        }
                    }
                }
                onNodeWithTag("settings-category-control").performScrollTo().performClick()
                onNodeWithTag("pressure-fire-selector").assertDoesNotExist()
                runOnIdle { available = true }
                onNodeWithTag("pressure-fire-selector").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                assertFalse(model.controlSettings.value.pressureFire)
                onNodeWithTag("pressure-fire-sensitivity-selector").assertDoesNotExist()
                capture("settings-off")
                onNodeWithTag("pressure-fire-selector").performClick()
                onNodeWithTag("pressure-fire-true").performClick()
                onNodeWithTag("pressure-fire-sensitivity-selector").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
                (1..4).forEach { onNodeWithTag("pressure-fire-sensitivity-$it").assertExists() }
                onNodeWithTag("pressure-fire-sensitivity-5").assertDoesNotExist()
                onNodeWithTag("pressure-fire-sensitivity-4").performClick()
                onNodeWithTag("settings-save").performClick()
                waitUntil(timeoutMillis = 5_000) { !model.settingsBusy.value }
                assertTrue(runBlocking { store.load() }.control.pressureFire)
                assertEquals(4, runBlocking { store.load() }.control.pressureFireSensitivity)
                onNodeWithTag("pressure-fire-help").performScrollTo().assertIsDisplayed(); capture("settings-on")
                if (allLanguages) for (locale in listOf("zh", "zh-Hant", "ja", "de", "es", "fr", "ko", "ru", "en")) {
                    runOnIdle { Localization.initialize(locale, locale) }
                    onNodeWithTag("pressure-fire-help").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("pressure-fire-sensitivity-selector").assertIsDisplayed()
                    capture("settings-$locale")
                }
                runOnIdle { settings = false }
                model.enableRemote()
                waitUntil(timeoutMillis = 5_000) { model.remoteEnabled.value }
                onNodeWithContentDescription(if (language == "zh") "红外开火" else "Fire infrared").assertDoesNotExist()
                val before = session.infraredCount.get()
                onNodeWithTag("remote-surface").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(5f, 0f)); up() }
                waitForIdle(); assertEquals(before, session.infraredCount.get(), "Default pressure 1 must not fire")
                onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.Spacebar) }
                waitUntil(timeoutMillis = 5_000) { session.infraredCount.get() >= before + 2 }
                onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.Spacebar) }
                waitUntil(timeoutMillis = 5_000) { !model.firing.value }
                onNodeWithTag("remote-surface").performTouchInput { click(center) }
                capture("cockpit-on")
                if (allLanguages) for (locale in listOf("zh", "zh-Hant", "ja", "de", "es", "fr", "ko", "ru", "en")) {
                    runOnIdle { Localization.initialize(locale, locale) }
                    capture("cockpit-$locale")
                }
                runOnIdle { Localization.initialize(language, language); available = false }
                onNodeWithContentDescription(if (language == "zh") "红外开火" else "Fire infrared").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { session.infraredCount.get() >= before + 3 }
                capture("unsupported-cockpit")
                runOnIdle { settings = true }
                onNodeWithTag("settings-category-control").performScrollTo().performClick()
                onNodeWithTag("pressure-fire-selector").assertDoesNotExist()
                assertTrue(model.controlSettings.value.pressureFire, "Unsupported capability ignores the preference without overwriting it")
            } finally { model.close() }
        }
}
