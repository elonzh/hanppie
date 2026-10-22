package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.audio.RobotAudioController
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

private class AudioFixture : RobotAudioService {
    var settings = RobotAudioSettings(38, 0)
    var failure = false
    override suspend fun read(): RobotAudioSettings {
        if (failure) throw RobotRequestException(RequestProblem.TIMED_OUT)
        return settings
    }
    override suspend fun applyVolume(volume: Int): RobotAudioSettings { settings = settings.copy(volume = volume); return read() }
    override suspend fun setLanguage(language: SpeakerLanguage): RobotAudioSettings {
        settings = settings.copy(languageCode = language.wireValue); return read()
    }
}

class RobotAudioUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun verifiesVolumeChangesLanguageAndOffersRefreshAfterAnError() {
        Localization.initialize("zh", null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val audio = AudioFixture()
        val controller = RobotAudioController(scope).apply { attach(audio) }
        try {
            rule.setContent { TestWorkbenchTheme {
                Column(Modifier.width(600.dp).verticalScroll(rememberScrollState())) {
                    RobotAudioSettingsContent(controller, connected = true, volume = 65, enabled = true)
                }
            } }
            rule.onNodeWithText("机器人返回的音量：38%").assertIsDisplayed()
            rule.onNodeWithTag("robot-audio-apply-volume").performClick()
            rule.onNodeWithText("机器人返回的音量：65%").assertIsDisplayed()
            rule.onNodeWithTag("robot-speaker-language-selector").performClick()
            rule.onNodeWithContentDescription("robot-speaker-language-CHINESE").performClick()
            rule.waitForIdle()
            assertEquals(SpeakerLanguage.CHINESE, audio.settings.language)
            audio.failure = true
            rule.onNodeWithTag("robot-audio-refresh").performClick()
            rule.onNodeWithTag("robot-audio-error").assertIsDisplayed()
            rule.onNodeWithTag("robot-audio-apply-volume").assertDoesNotExist()
            rule.onNodeWithTag("robot-speaker-language-selector").assertDoesNotExist()
            audio.failure = false
            rule.onNodeWithTag("robot-audio-refresh").performClick()
            rule.onNodeWithTag("robot-audio-error").assertDoesNotExist()
            rule.onNodeWithText("机器人返回的音量：65%").assertIsDisplayed()
        } finally { controller.clear(); scope.cancel() }
    }
}

@OptIn(ExperimentalTestApi::class)
class RobotAudioLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)

    private fun layout(width: Int, height: Int, language: String = "zh", fontScale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel()
            model.state.value = model.state.value.copy(connected = true)
            model.robotAudio.attach(AudioFixture())
            try {
                setContent { TestWorkbenchTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        Box(Modifier.fillMaxSize().testTag("audio-layout").padding(16.dp)) { SettingsPage(model) }
                    }
                } }
                onNodeWithTag("settings-category-media").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodes(hasTestTag("robot-audio-apply-volume")).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("robot-audio-apply-volume").performScrollTo().assertIsDisplayed()
                onNodeWithTag("robot-audio-refresh").performScrollTo().assertIsDisplayed()
                val image = onNodeWithTag("audio-layout").captureToImage()
                assertEquals(width, image.width); assertEquals(height, image.height)
                val pixels = IntArray(width * height); image.readPixels(pixels)
                val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                out.setRGB(0, 0, width, height, pixels, 0, width)
                val file = File("build/reports/ui/audio-$width-$height-$language.png").apply { parentFile.mkdirs() }
                ImageIO.write(out, "png", file)
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
