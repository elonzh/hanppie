package cn.elonzh.hanppie.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.*

class ReplySpeakerTest {
    private class FakeSpeech : SpeechEngine {
        override val state = MutableStateFlow(SpeechState("fixture", true))
        val spoken = mutableListOf<String>()
        override fun speak(text: String) { spoken += text; state.value = state.value.copy(speaking = true) }
        override fun stop() { state.value = state.value.copy(speaking = false) }
        override fun close() { stop() }
    }
    @Test fun longRepliesAreChunkedWithoutBreakingUnicode() {
        val text = "你好😀".repeat(1100)
        val parts = spokenChunks(text)
        assertTrue(parts.all { it.length <= 1000 && !it.last().isHighSurrogate() })
        assertEquals(text, parts.joinToString(""))
        assertEquals(listOf("参考文档。代码已显示在对话中。"), spokenChunks("参考[文档](https://example.com)。```python\nprint('no')\n```"))
    }
    @Test fun stopDropsRemainingChunksAndReplacementDoesNotGetStoppedByOldJob() = runBlocking {
        val engine = FakeSpeech()
        val reader = ReplySpeaker(engine, Dispatchers.Unconfined)
        try {
            reader.speak("一".repeat(2500))
            assertEquals(1, engine.spoken.size)
            reader.stop(); yield()
            assertEquals(1, engine.spoken.size)
            reader.speak("新回复")
            yield()
            assertTrue(engine.state.value.speaking)
            assertEquals("新回复", engine.spoken.last())
            engine.stop(); yield()
            assertFalse(engine.state.value.speaking)
        } finally { reader.close() }
    }
    @Test fun unavailableSpeechDoesNotConsumeText() {
        val engine = FakeSpeech()
        engine.state.value = engine.state.value.copy(available = false)
        val reader = ReplySpeaker(engine, Dispatchers.Unconfined)
        try { reader.speak("你好"); assertTrue(engine.spoken.isEmpty()) } finally { reader.close() }
    }
}
