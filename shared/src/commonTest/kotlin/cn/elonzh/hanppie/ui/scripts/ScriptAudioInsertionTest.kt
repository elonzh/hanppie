package cn.elonzh.hanppie.ui.scripts

import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptAudioInsertionTest {
    private val statement = scriptAudioStatement("rm_define.media_custom_audio_0")

    private fun inserted(prefix: String, suffix: String): String =
        prefix + scriptAudioInsertion(prefix, suffix, statement) + suffix

    @Test fun statementCallsTheLabApiWithTheResourceConstant() {
        assertEquals("media_ctrl.play_sound(rm_define.media_custom_audio_0)", statement)
    }

    @Test fun insertionIntoAnEmptyDocumentStartsTheFirstLine() {
        assertEquals("$statement\n", inserted("", ""))
    }

    @Test fun insertionAtAnIndentedLineKeepsThatIndentation() {
        assertEquals("def start():\n    $statement\n", inserted("def start():\n    ", "\n"))
    }

    @Test fun insertionAfterCodeStartsANewIndentedLine() {
        assertEquals(
            "def start():\n    x = 1\n    $statement\n    pass\n",
            inserted("def start():\n    x = 1", "\n    pass\n"),
        )
    }

    @Test fun insertionAtTheEndOfTheDocumentClosesTheLine() {
        assertEquals("def start():\n    pass\n    $statement\n", inserted("def start():\n    pass", ""))
    }
}
