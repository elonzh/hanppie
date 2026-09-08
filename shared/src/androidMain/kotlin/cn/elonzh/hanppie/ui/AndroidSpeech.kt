package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal class AndroidSpeech(context: Context) : SpeechEngine {
    override val state = MutableStateFlow(SpeechState(tr(Res.string.initializing_system_speech), false))
    private var tts: TextToSpeech? = null
    private var closed = false
    private val generation = AtomicLong()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (!closed) {
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    state.value = SpeechState(tr(Res.string.system_speech_unavailable), false, error = tr(Res.string.configure_an_engine_in_system_speech_settings))
                } else {
                    languageChanged()
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) { if (id == generation.get().toString()) state.update { it.copy(speaking = true) } }
                        override fun onDone(id: String?) { finish(id, null) }
                        @Deprecated("Platform compatibility") override fun onError(id: String?) { finish(id, tr(Res.string.system_speech_failed)) }
                        override fun onError(id: String?, errorCode: Int) { finish(id, tr(Res.string.system_speech_failed_value,errorCode)) }
                        override fun onStop(id: String?, interrupted: Boolean) { finish(id, null) }
                    })
                }
            }
        }
    }

    private fun finish(id: String?, error: String?) {
        if (!closed && id == generation.get().toString()) state.update { it.copy(speaking = false, error = error) }
    }

    override fun languageChanged() {
        val engine = tts ?: return
        val language = Locale.forLanguageTag(Localization.languageTag).language
        val offline = engine.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == language }
        val ready = offline != null && engine.setVoice(offline) == TextToSpeech.SUCCESS
        state.value = SpeechState(tr(Res.string.system_text_to_speech), ready,
            error = if(ready) null else tr(Res.string.install_an_offline_speech_pack_for_this_language))
    }

    override fun speak(text: String) {
        if (closed || !state.value.available || text.isBlank()) return
        require(text.length <= minOf(4000, TextToSpeech.getMaxSpeechInputLength()))
        val id = generation.incrementAndGet().toString()
        state.update { it.copy(speaking = true, error = null) }
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id, tr(Res.string.could_not_start_speaking))
    }

    override fun stop() {
        generation.incrementAndGet()
        tts?.stop()
        state.update { it.copy(speaking = false) }
    }

    override fun close() { closed = true; stop(); tts?.shutdown(); tts = null }
}
