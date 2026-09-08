package cn.elonzh.hanppie.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class SpeechInputState(
    val active: Boolean = false,
    val processing: Boolean = false,
    val partial: String = "",
    val result: String = "",
    val resultId: Long = 0,
    val error: String? = null,
)

/** Recognized text only. The agent never receives an audio device or starts recording. */
internal interface SpeechInput {
    val state: StateFlow<SpeechInputState>
    fun start()
    fun finish()
    fun cancel()
    fun consumeResult()
    fun close()
}

internal class NoSpeechInput : SpeechInput {
    override val state = MutableStateFlow(SpeechInputState())
    override fun start() {}
    override fun finish() {}
    override fun cancel() {}
    override fun consumeResult() {}
    override fun close() {}
}
