package cn.elonzh.hanppie.ui.speech

import cn.elonzh.hanppie.ui.platform.IosPlatformServices
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.uiText

internal class IosSpeechInput(platform: IosPlatformServices) : SpeechInput by SpeechRecognitionInput(
    object : SpeechCapture {
        override fun start(languageTag: String, listener: SpeechCaptureListener) {
            platform.startSpeech(languageTag) { text, final, error ->
                when {
                    error != null -> listener.failed(uiText(Res.string.speech_input_failed_value, error))
                    final -> listener.complete(text)
                    else -> listener.partial(text)
                }
            }
        }
        override fun finish() = platform.finishSpeech()
        override fun cancel() = platform.cancelSpeech()
    }
)
