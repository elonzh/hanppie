package cn.elonzh.hanppie.ui.speech

import cn.elonzh.hanppie.ui.platform.IosPlatformServices
import cn.elonzh.hanppie.ui.i18n.Localization
import kotlinx.coroutines.flow.MutableStateFlow

internal class IosSpeechInput(private val platform: IosPlatformServices) : SpeechInput {
    override val state = MutableStateFlow(SpeechInputState())
    private var generation = 0
    override fun start() {
        val current = ++generation
        state.value = state.value.copy(active = true, processing = false, partial = "", error = null)
        platform.startSpeech(Localization.languageTag) { text, final, error ->
            if (current == generation) {
                state.value = state.value.copy(active = !final && error == null, processing = false,
                    partial = if (final) "" else text, result = if (final) text else state.value.result,
                    resultId = if (final) state.value.resultId + 1 else state.value.resultId, error = error)
            }
        }
    }
    override fun finish() { platform.finishSpeech(); state.value = state.value.copy(active = false, processing = true) }
    override fun cancel() { generation++; platform.cancelSpeech(); state.value = state.value.copy(active = false, processing = false, partial = "") }
    override fun consumeResult() { state.value = state.value.copy(result = "") }
    override fun close() = cancel()
}
