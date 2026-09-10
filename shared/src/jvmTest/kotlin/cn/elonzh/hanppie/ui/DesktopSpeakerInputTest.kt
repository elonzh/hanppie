package cn.elonzh.hanppie.ui

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals

class DesktopSpeakerInputTest {
    @Test fun extractsPacketsAcrossOggLacingSegments() {
        val head = "OpusHead".toByteArray()
        val tags = "OpusTags".toByteArray()
        val audio = ByteArray(300) { (it * 3).toByte() }
        val ogg = oggPage(listOf(head, tags, audio))
        val expected = ByteArrayOutputStream().apply {
            write(300 and 0xff); write(300 ushr 8); write(audio)
        }.toByteArray()
        assertContentEquals(expected, opusPacketsFromOgg(ogg))
    }

    private fun oggPage(packets: List<ByteArray>): ByteArray {
        val laces = packets.flatMap { packet ->
            buildList {
                var left = packet.size
                while (left >= 255) { add(255); left -= 255 }
                add(left)
            }
        }
        return ByteArrayOutputStream().apply {
            write("OggS".toByteArray()); write(ByteArray(22)); write(laces.size)
            laces.forEach(::write); packets.forEach(::write)
        }.toByteArray()
    }
}
