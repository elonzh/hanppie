package cn.elonzh.hanppie.ui.robot.remote

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Assume
import org.junit.Test

class DesktopMediaTest {
    @Test fun damagedReferencesAreHiddenUntilACleanKeyframe() {
        val health = DecodedFrameHealth()
        fun frame(key: Boolean) = health.accept("[Parsed_showinfo_1] n: 0 pts: 0 iskey:${if (key) 1 else 0} type:${if (key) "I" else "P"}")
        assertEquals(false, frame(false))
        assertEquals(true, frame(true))
        assertEquals(true, frame(false))
        health.accept("[rawvideo @ 0x2] Application provided invalid, non monotonically increasing dts to muxer")
        assertEquals(true, frame(false), "Mux timestamps are not decoder corruption")
        health.accept("[h264 @ 0x1] error while decoding MB 21 5")
        assertEquals(false, frame(false))
        assertEquals(false, frame(false))
        health.accept("[h264 @ 0x1] concealing 80 DC errors in I frame")
        assertEquals(false, frame(true))
        assertEquals(false, frame(false))
        assertEquals(true, frame(true))
        assertEquals(true, frame(false))
        health.accept("[h264 @ 0x1] decode_slice_header error")
        assertEquals(false, frame(false))
        assertEquals(true, health.accept("[Parsed_showinfo_1] n: 45 pts: 1800000 iskey:0 type:I"),
            "An intra picture can restore display even when the encoder does not flag it as an IDR")
        assertEquals(true, frame(false))
    }

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
        val recording = Files.createTempFile("hanppie-recording-", ".mp4").also { Files.deleteIfExists(it) }
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
            val recorder = DesktopVideoRecorder(ffmpeg, recording, withAudio = true)
            repeat(3) { recorder.frame(ByteArray(1280 * 720 * 4)) }
            recorder.audio(ByteArray(9_600))
            assertEquals(recording.toString(), recorder.finish())
            assertTrue(Files.size(recording) > 0)
            val audioTrack = ProcessBuilder(ffmpeg,"-hide_banner","-loglevel","error","-i",recording.toString(),
                "-map","0:a:0","-f","null","-").start()
            assertTrue(audioTrack.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, audioTrack.exitValue(), "Saved MP4 must contain a decodable robot microphone track")
        } finally { file.delete(); Files.deleteIfExists(recording) }
    }
}
