package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Owns a cancellable reply queue; no microphone or robot dependencies. */
internal class ReplySpeaker(private val engine: SpeechEngine, dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private var generation = 0L

    fun speak(text: String) {
        stop()
        val token = generation
        if (!engine.state.value.available) return
        job = scope.launch {
            try {
                for (chunk in spokenChunks(text)) {
                    ensureActive()
                    engine.speak(chunk)
                    withTimeout(180_000) { engine.state.first { !it.speaking } }
                    if (engine.state.value.error != null) break
                }
            } catch (e: CancellationException) { throw e }
            finally { if (token == generation) engine.stop() }
        }
    }
    fun stop() { generation++; job?.cancel(); job = null; engine.stop() }
    fun close() { stop(); scope.cancel() }
}

internal fun spokenChunks(markdown: String): List<String> {
    val text = markdown.replace(Regex("```[\\s\\S]*?```"), tr(Res.string.code_is_shown_in_the_conversation))
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        .replace("**", "").replace("`", "").trim()
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 1000, text.length)
        if (end < text.length) {
            val boundary = (end - 1 downTo start + 500).firstOrNull { text[it] in "。！？.!?\n" }
            if (boundary != null) end = boundary + 1
            else if (text[end - 1].isHighSurrogate()) end--
        }
        text.substring(start, end).trim().takeIf { it.isNotBlank() }?.let(chunks::add)
        start = end
    }
    return chunks
}
