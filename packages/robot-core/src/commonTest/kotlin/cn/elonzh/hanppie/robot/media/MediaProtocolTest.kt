package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.protocol.hexBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaProtocolTest {
    @Test
    fun speakerUploadPayloadsMatchRecoveredTransport() {
        assertEquals("000000010002002c010000000000000000", SpeakerAudio.start(2, 300).hex())
        assertEquals("00030000000300010203", SpeakerAudio.block(byteArrayOf(1, 2, 3), 3).hex())
        assertEquals("0b040000010001000001", SpeakerAudio.playPayload.hex())
    }

    @Test
    fun fragmentedAnnexBAndMultiSliceFrames() {
        val parser = AnnexB()
        assertTrue(parser.accept("0000".hexBytes()).isEmpty())
        val nals = parser.accept("00016580000001654000000001618000000109f0".hexBytes())
        assertEquals(3, nals.size)
        val frames = H264AccessUnits()
        assertNull(frames.accept(nals[0]))
        assertNull(frames.accept(nals[1]))
        assertContentEquals(nals[0] + nals[1], frames.accept(nals[2]))
    }
}
