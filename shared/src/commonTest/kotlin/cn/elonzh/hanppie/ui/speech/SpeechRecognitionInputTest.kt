package cn.elonzh.hanppie.ui.speech

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.AppLanguage
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.uiText
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class SpeechRecognitionInputTest {
    private class Capture : SpeechCapture {
        val listeners = mutableListOf<SpeechCaptureListener>()
        val languages = mutableListOf<String>()
        var ends = 0
        var cancels = 0
        var startError: Exception? = null
        var finishAction: () -> Unit = {}
        override fun start(languageTag: String, listener: SpeechCaptureListener) {
            listeners += listener; languages += languageTag; startError?.let { throw it }
        }
        override fun finish() { ends++; finishAction() }
        override fun cancel() { cancels++ }
    }

    @Test fun processingRetainsOwnershipUntilTheFinalCallbackAndDoesNotOverwriteSynchronousCompletion() {
        val capture = Capture(); val input = SpeechRecognitionInput(capture)
        input.start(); input.finish()
        assertTrue(input.state.value.active); assertTrue(input.state.value.processing)
        input.start(); input.finish(); assertEquals(1, capture.listeners.size); assertEquals(1, capture.ends)
        capture.listeners.single().complete(" first result ")
        assertFalse(input.state.value.active); assertFalse(input.state.value.processing)
        assertEquals("first result", input.state.value.result)
        input.consumeResult(); assertEquals("", input.state.value.result)
        input.start(); capture.finishAction = { capture.listeners.last().complete("synchronous final") }
        input.finish(); assertEquals("synchronous final", input.state.value.result)
        assertFalse(input.state.value.processing); assertFalse(input.state.value.active)
        input.close()
    }

    @Test fun nativeFailuresKeepTheirIdentityAcrossAllLanguagesAndAnExplicitNewAttemptClearsThem() {
        val capture = Capture(); val input = SpeechRecognitionInput(capture)
        val failure = uiText(Res.string.speech_input_failed_value, "native EACCES")
        try {
            input.start(); capture.listeners.single().failed(failure)
            for (language in AppLanguage.entries) {
                Localization.initialize("en", language.preference)
                assertSame(failure, input.state.value.error)
                assertTrue(failure.resolve().contains("native EACCES"))
            }
            assertFalse(input.state.value.active); assertEquals(1, capture.listeners.size)
            input.start(); assertNull(input.state.value.error)
            assertEquals(Localization.languageTag, capture.languages.last())
            input.reject(uiText(Res.string.microphone_permission_denied_you_can_still_type))
            assertFalse(input.state.value.active); assertNotNull(input.state.value.error)
            input.dismissError(); assertNull(input.state.value.error)
        } finally { input.close(); Localization.initialize("zh", "zh") }
    }

    @Test fun callbacksFromCancelledClosedOrEarlierAttemptsCannotFillTheDraftOrChangeTheCurrentError() {
        val capture = Capture(); val input = SpeechRecognitionInput(capture)
        input.start(); val old = capture.listeners.single(); old.partial("old partial")
        input.cancel(); input.start(); val current = capture.listeners.last()
        old.processing(); old.partial("late partial"); old.complete("late result"); old.failed(uiText(Res.string.system_speech_failed))
        assertEquals(SpeechInputState(active = true), input.state.value)
        current.partial("current partial"); current.processing(); input.cancel()
        current.complete("cancelled result"); assertEquals(SpeechInputState(), input.state.value)
        input.start(); val last = capture.listeners.last(); input.close(); input.start()
        last.complete("closed result"); last.failed(uiText(Res.string.system_speech_failed))
        assertEquals(3, capture.listeners.size); assertEquals(SpeechInputState(), input.state.value)
    }

    @Test fun onlyOneFinalResultIsPublishedAndEmptyOrOversizedRecognitionIsBounded() {
        val capture = Capture(); val input = SpeechRecognitionInput(capture)
        input.start(); val first = capture.listeners.single()
        first.partial("a".repeat(12_010)); assertEquals(12_000, input.state.value.partial.length)
        first.complete("b".repeat(12_010)); first.complete("duplicate")
        assertEquals(12_000, input.state.value.result.length); assertEquals(1L, input.state.value.resultId)
        input.consumeResult(); input.start(); capture.listeners.last().complete("  \n  ")
        assertEquals(2L, input.state.value.resultId); assertEquals("", input.state.value.result)
        assertEquals(Res.string.no_text_recognized_try_again, input.state.value.error?.resource)
        input.close()
    }

    @Test fun startAndFinishExceptionsAreVisibleAndCanBeRetriedWithoutLeakingAnActiveCapture() {
        val capture = Capture(); val input = SpeechRecognitionInput(capture)
        capture.startError = IllegalStateException("start EIO"); input.start()
        assertFalse(input.state.value.active); assertEquals(listOf("start EIO"), input.state.value.error?.arguments)
        capture.startError = null; input.start(); capture.finishAction = { error("finish EIO") }; input.finish()
        assertFalse(input.state.value.active); assertEquals(listOf("finish EIO"), input.state.value.error?.arguments)
        capture.finishAction = {}; input.start(); assertTrue(input.state.value.active); assertNull(input.state.value.error)
        input.close()
    }

    @Test fun coroutineCancellationIsNotTurnedIntoAnInputError() {
        val capture = Capture(); val input = SpeechRecognitionInput(capture)
        capture.startError = CancellationException("cancel start")
        assertFailsWith<CancellationException> { input.start() }
        assertFalse(input.state.value.active); assertNull(input.state.value.error)
        capture.startError = null; input.start(); capture.finishAction = { throw CancellationException("cancel finish") }
        assertFailsWith<CancellationException> { input.finish() }
        assertFalse(input.state.value.active); assertNull(input.state.value.error)
        input.close()
    }
}
