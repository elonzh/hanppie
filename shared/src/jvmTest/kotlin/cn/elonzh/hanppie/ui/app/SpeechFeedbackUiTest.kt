package cn.elonzh.hanppie.ui.app

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.AppLanguage
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import cn.elonzh.hanppie.ui.settings.*
import cn.elonzh.hanppie.ui.speech.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Real workbench and shared recognition owner; no native microphone, recognition service or AI request. */
@OptIn(ExperimentalTestApi::class)
class SpeechFeedbackUiTest {
    @Test fun compact() = scenario(844, 390, 1f, false)
    @Test fun tabletDark() = scenario(1024, 768, 1f, true)
    @Test fun desktop() = scenario(1280, 720, 1f, false)
    @Test fun largeNineLanguages() = scenario(844, 390, 1.4f, true)

    private class Capture : SpeechCapture {
        val listeners = mutableListOf<SpeechCaptureListener>()
        var finish: () -> Unit = {}
        override fun start(languageTag: String, listener: SpeechCaptureListener) { listeners += listener }
        override fun finish() = finish.invoke()
        override fun cancel() = Unit
    }

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize("en", "en")
            val capture = Capture(); val speech = SpeechRecognitionInput(capture)
            val model = testConsoleModel(voiceInput = speech)
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val draft = "Keep this draft"; val words = "recognized words"
            val diagnostic = "synthetic EACCES " + "device/path/".repeat(200) + " END"
            fun picture(name: String) {
                waitForIdle()
                val image = onNode(isRoot() and hasAnyDescendant(hasTestTag("chat-surface"))).captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val path = File("build/reports/ui/speech-feedback-$width-$height-$scale-$name.png")
                path.parentFile.mkdirs(); ImageIO.write(output, "png", path)
            }
            fun idleInput() {
                onNodeWithTag("chat-input").assertIsDisplayed().assertIsEnabled()
                onNodeWithTag("voice-input").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            }
            try {
                setContent {
                    TestWorkbenchTheme(appearance) {
                        CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                            Console(model, remember { mutableStateOf(EditorDocument()) },
                                onVoiceInput = speech::start)
                        }
                    }
                }
                onNodeWithTag("navigate-3").performClick()
                waitUntil(timeoutMillis = 5_000) { model.chat.state.value.ready }
                onNodeWithTag("chat-input").performTextInput(draft)
                onNodeWithTag("voice-input").performClick()
                runOnIdle { capture.listeners.last().partial("synthetic partial ".repeat(200)) }
                onNodeWithTag("chat-input").assertIsNotEnabled()
                assertTrue(onNodeWithTag("voice-input-status").fetchSemanticsNode().boundsInRoot.height <= 80.5f)
                onNodeWithText(tr(Res.string.listening), substring = true).assertIsDisplayed()
                onNodeWithTag("voice-input").assertContentDescriptionEquals(tr(Res.string.finish_recording))
                picture("listening")
                onNodeWithTag("voice-input").performClick()
                assertTrue(speech.state.value.active); assertTrue(speech.state.value.processing)
                onNodeWithTag("chat-input").assertIsNotEnabled()
                onNodeWithText(tr(Res.string.recognizing), substring = true).assertIsDisplayed()
                onNodeWithTag("voice-input").assertContentDescriptionEquals(tr(Res.string.cancel_recognition))
                picture("processing")
                val retainedError = uiText(Res.string.speech_input_failed_value, diagnostic)
                runOnIdle { capture.listeners.last().failed(retainedError) }
                idleInput(); onNodeWithTag("voice-input-error").assertTextEquals(retainedError.resolve())
                assertTrue(onNodeWithTag("voice-input-error").fetchSemanticsNode().boundsInRoot.height <= 80.5f)
                onNodeWithTag("voice-input-error-close").assertHeightIsAtLeast(48.dp); picture("error")
                if (scale > 1f) for (language in AppLanguage.entries) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithTag("voice-input-error").assertTextEquals(retainedError.resolve())
                    assertSame(retainedError, speech.state.value.error)
                    idleInput(); onNodeWithTag("voice-input-error-close").assertIsDisplayed(); picture("error-${language.preference}")
                }
                runOnIdle { Localization.select("en") }
                onNodeWithTag("voice-input-error").performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 10_000f)) }
                picture("error-scrolled")
                onNodeWithTag("voice-input-error-close").performClick()
                assertNull(speech.state.value.error); onNodeWithTag("voice-input-feedback").assertDoesNotExist()
                assertEquals(draft, model.chat.state.value.draft); picture("dismissed")
                onNodeWithTag("voice-input").performClick()
                runOnIdle { capture.finish = { capture.listeners.last().complete(words) } }
                onNodeWithTag("voice-input").performClick()
                waitUntil(timeoutMillis = 5_000) { model.chat.state.value.draft == "$draft\n$words" && speech.state.value.result.isEmpty() }
                assertTrue(model.chat.state.value.lines.isEmpty()); idleInput(); picture("result")
                onNodeWithTag("voice-input").performClick(); val cancelled = capture.listeners.last()
                runOnIdle { model.setForeground(false); cancelled.complete("late background result"); model.setForeground(true) }
                assertFalse(speech.state.value.active); assertEquals("$draft\n$words", model.chat.state.value.draft)
                idleInput(); picture("background")
                onNodeWithTag("voice-input").performClick(); val leaving = capture.listeners.last()
                onNodeWithTag("navigate-4").performClick()
                waitUntil(timeoutMillis = 5_000) { !speech.state.value.active }
                runOnIdle { leaving.complete("late page result") }
                onNodeWithTag("navigate-3").performClick(); idleInput()
                assertEquals("$draft\n$words", model.chat.state.value.draft); picture("returned")
            } finally { model.close(); Localization.initialize("zh", "zh") }
        }
}
