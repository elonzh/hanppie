package cn.elonzh.hanppie.desktop

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal class AndroidSpeech(context: Context) : SpeechEngine {
    override val state = MutableStateFlow(SpeechState(tr("正在初始化系统语音…"), false))
    private var tts: TextToSpeech? = null
    private var closed = false
    private val generation = AtomicLong()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (!closed) {
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    state.value = SpeechState(tr("系统语音不可用"), false, error = tr("请在系统语音设置中配置引擎"))
                } else {
                    languageChanged()
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) { if (id == generation.get().toString()) state.update { it.copy(speaking = true) } }
                        override fun onDone(id: String?) { finish(id, null) }
                        @Deprecated("Platform compatibility") override fun onError(id: String?) { finish(id, tr("系统播报失败")) }
                        override fun onError(id: String?, errorCode: Int) { finish(id, tr("系统播报失败：{0}",errorCode)) }
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
        state.value = SpeechState(tr("系统文字转语音"), ready,
            error = if(ready) null else tr("请安装当前语言的离线语音包"))
    }

    override fun speak(text: String) {
        if (closed || !state.value.available || text.isBlank()) return
        require(text.length <= minOf(4000, TextToSpeech.getMaxSpeechInputLength()))
        val id = generation.incrementAndGet().toString()
        state.update { it.copy(speaking = true, error = null) }
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id, tr("无法开始播报"))
    }

    override fun stop() {
        generation.incrementAndGet()
        tts?.stop()
        state.update { it.copy(speaking = false) }
    }

    override fun close() { closed = true; stop(); tts?.shutdown(); tts = null }
}
