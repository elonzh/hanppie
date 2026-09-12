package cn.elonzh.hanppie.ui.speech

import kotlinx.coroutines.flow.StateFlow

internal data class SpeechState(val engine: String, val available: Boolean, val speaking: Boolean = false,
                                val error: String? = null)

internal interface SpeechEngine {
    val state: StateFlow<SpeechState>
    fun speak(text: String)
    fun stop()
    fun languageChanged() {}
    fun close()
}
