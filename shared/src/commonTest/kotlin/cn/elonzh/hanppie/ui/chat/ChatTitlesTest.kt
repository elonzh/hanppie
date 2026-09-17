package cn.elonzh.hanppie.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatTitlesTest {
    @Test fun aFirstMessageBecomesASingleLineTitle() {
        assertEquals("看看电量然后停下", sessionTitleFromMessage("看看电量然后停下"))
        assertEquals("第一行 第二行", sessionTitleFromMessage("第一行\n\n  第二行  "))
    }

    @Test fun aLongFirstMessageIsCappedSoTheRowStaysOneLine() {
        val title = sessionTitleFromMessage("请".repeat(80))
        assertEquals(MAX_SESSION_TITLE_LENGTH, title.length)
    }

}
