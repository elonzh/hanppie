package cn.elonzh.hanppie.ui.speech

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Platform microphone/recognizer only. Commands and callbacks run on the UI thread. */
internal interface SpeechCapture {
    fun start(languageTag: String, listener: SpeechCaptureListener)
    fun finish()
    fun cancel()
}

internal interface SpeechCaptureListener {
    fun processing()
    fun partial(text: String)
    fun complete(text: String)
    fun failed(message: UiText)
}

/** Shared recognition lifecycle: a result fills the composer and never sends a message. */
internal class SpeechRecognitionInput(private val capture: SpeechCapture) : SpeechInput {
    private val mutable = MutableStateFlow(SpeechInputState())
    override val state = mutable.asStateFlow()
    private var generation = 0L
    private var closed = false

    override fun start() {
        if (closed || state.value.active) return
        cancel()
        val token = ++generation
        mutable.value = state.value.copy(active = true, processing = false, partial = "", result = "", error = null)
        try {
            capture.start(Localization.languageTag, object : SpeechCaptureListener {
                private fun current() = !closed && generation == token && state.value.active
                override fun processing() { if (current()) mutable.value = state.value.copy(processing = true) }
                override fun partial(text: String) { if (current()) mutable.value = state.value.copy(partial = text.take(12_000)) }
                override fun complete(text: String) {
                    if (!current()) return
                    val result = text.trim().take(12_000)
                    cancel()
                    mutable.value = state.value.copy(result = result, resultId = state.value.resultId + 1,
                        error = if (result.isEmpty()) uiText(Res.string.no_text_recognized_try_again) else null)
                }
                override fun failed(message: UiText) { if (current()) reject(message) }
            })
        } catch (error: CancellationException) { cancel(); throw error }
        catch (error: Exception) { reject(failure(error)) }
    }

    override fun finish() {
        if (closed || !state.value.active || state.value.processing) return
        // Publish first: a platform may synchronously deliver its final callback from finish().
        mutable.value = state.value.copy(processing = true)
        try { capture.finish() }
        catch (error: CancellationException) { cancel(); throw error }
        catch (error: Exception) { reject(failure(error)) }
    }

    fun reject(message: UiText) {
        if (closed) return
        cancel()
        mutable.value = state.value.copy(error = message)
    }

    override fun cancel() {
        ++generation
        mutable.value = state.value.copy(active = false, processing = false, partial = "")
        capture.cancel()
    }
    override fun consumeResult() { mutable.value = state.value.copy(result = "") }
    override fun dismissError() { mutable.value = state.value.copy(error = null) }
    override fun close() { if (!closed) { closed = true; cancel() } }

    private fun failure(error: Exception) = uiText(Res.string.speech_input_failed_value,
        error.message ?: error::class.simpleName.orEmpty())
}
