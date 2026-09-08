package cn.elonzh.hanppie.desktop

import org.junit.Test
import org.junit.Assume
import kotlin.test.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DesktopMediaTest {
    @Test fun opusPagesHaveValidHeadersLacingAndChecksum() {
        val ogg = OpusOgg()
        val headers = ogg.headers()
        assertEquals("OggS",headers.copyOfRange(0,4).decodeToString())
        assertEquals("OpusHead",headers.copyOfRange(28,36).decodeToString())
        val page = ogg.packet(ByteArray(255).also { it[0] = 0xf8.toByte() })
        assertEquals(2,page[26].toInt())
        assertEquals(255,page[27].toInt() and 255); assertEquals(0,page[28].toInt())
        assertEquals(960,ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).getLong(6))
        val expected = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).getInt(22)
        page.fill(0,22,26)
        // Independent bit-by-bit Ogg CRC reference.
        var crc = 0L
        page.forEach { b -> for (bit in 7 downTo 0) {
            val carry = ((crc ushr 31) xor ((b.toInt() ushr bit).toLong() and 1)) != 0L
            crc = (crc shl 1) and 0xffffffffL
            if (carry) crc = crc xor 0x04c11db7L
        } }
        assertEquals(expected,crc.toInt())
        assertFailsWith<IllegalArgumentException> { ogg.packet(byteArrayOf(3,0)) }
    }
    @Test fun missingDecoderFailsClearly() {
        val error = assertFailsWith<IllegalStateException> { DesktopMedia(false,{}, {}, executable="hanppie-nonexistent-decoder") }
        assertTrue(error.message!!.contains("HANPPIE_FFMPEG"))
    }
    @Test fun realFfmpegDecodesBothStreamsAndCloses() {
        val ffmpeg = System.getenv("HANPPIE_FFMPEG") ?: "ffmpeg"
        Assume.assumeTrue(runCatching { ProcessBuilder(ffmpeg,"-version").start().waitFor() == 0 }.getOrDefault(false))
        val file = Files.createTempFile("hanppie-test-", ".h264").toFile()
        try {
            val generator = ProcessBuilder(ffmpeg,"-hide_banner","-loglevel","error","-y","-f","lavfi","-i",
                "color=c=blue:s=1280x720:r=30","-t","2","-c:v","libx264","-tune","zerolatency","-f","h264",file.path).start()
            assertTrue(generator.waitFor(10,TimeUnit.SECONDS)); assertEquals(0,generator.exitValue())
            val video = CountDownLatch(1); val audio = CountDownLatch(1)
            val media = DesktopMedia(true,{ if(it.size==1280*720*4) video.countDown() },{},
                pcmSink={ _, n -> if(n>0) audio.countDown() },executable=ffmpeg)
            try {
                media.video(file.readBytes())
                repeat(60) { media.audio(byteArrayOf(0xf8.toByte(),0xff.toByte(),0xfe.toByte())) }
                assertTrue(video.await(10,TimeUnit.SECONDS),"H264 frame missing")
                assertTrue(audio.await(10,TimeUnit.SECONDS),"Opus PCM missing")
                assertTrue(media.running)
            } finally { media.close() }
            assertFalse(media.running)
        } finally { file.delete() }
    }
}
