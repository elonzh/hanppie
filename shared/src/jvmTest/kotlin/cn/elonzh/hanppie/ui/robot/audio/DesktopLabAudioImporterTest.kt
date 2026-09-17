package cn.elonzh.hanppie.ui.robot.audio

import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.ui.robot.remote.OpusOgg
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume

class DesktopLabAudioImporterTest {
    private val ffmpeg = System.getenv("HANPPIE_FFMPEG") ?: "ffmpeg"

    private fun requireFfmpeg() = Assume.assumeTrue(
        runCatching { ProcessBuilder(ffmpeg, "-version").start().waitFor() == 0 }.getOrDefault(false),
    )

    /** One second of 440 Hz, stereo 44.1 kHz, MP3: a container and shape the DSP does not accept as-is. */
    private fun sourceMp3(): ByteArray {
        val process = ProcessBuilder(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi",
            "-i", "sine=frequency=440:duration=1", "-ar", "44100", "-ac", "2",
            "-c:a", "libmp3lame", "-f", "mp3", "pipe:1",
        ).start()
        val output = ByteArrayOutputStream()
        val reader = kotlin.concurrent.thread(isDaemon = true) { process.inputStream.copyTo(output) }
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "无法生成测试音频" }
        reader.join(1_000)
        return output.toByteArray()
    }

    @Test fun importsHostAudioIntoTheDspContainer() = runBlocking {
        requireFfmpeg()
        val decoded = DesktopLabAudioImporter().decode("sample.mp3", sourceMp3())
        assertTrue(decoded.packets.isNotEmpty())
        assertTrue(decoded.durationMillis in 900..1_100, "时长 ${decoded.durationMillis} ms 超出预期")
        val packets = decodeContainer(decoded.packets)
        assertTrue(packets.size in 40..60, "1 秒 20 ms 帧的包数异常：${packets.size}")
        // The container must be decodable as 48 kHz mono by the same FFmpeg the app already depends on.
        val samples = countDecodedSamples(decoded.packets)
        assertTrue(samples in 43_200..52_800, "解码回 $samples 个采样点，与 1 秒 48 kHz 不符")
    }

    @Test fun rejectsEmptyAndUndecodableInput() = runBlocking {
        requireFfmpeg()
        assertFailsWith<IllegalArgumentException> { DesktopLabAudioImporter().decode("empty", ByteArray(0)) }
        assertFailsWith<IllegalStateException> {
            DesktopLabAudioImporter().decode("noise.bin", ByteArray(4_096) { (it * 31).toByte() })
        }
        Unit
    }

    private fun decodeContainer(container: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        var index = 0
        while (index < container.size) {
            val length = (container[index].toInt() and 255) or ((container[index + 1].toInt() and 255) shl 8)
            packets += container.copyOfRange(index + 2, index + 2 + length)
            index += 2 + length
        }
        require(index == container.size) { "容器长度前缀与数据不匹配" }
        return packets
    }

    /** Re-wraps the client container as Ogg and measures how much audio FFmpeg gets back. */
    private fun countDecodedSamples(container: ByteArray): Int {
        val ogg = OpusOgg()
        val pages = ByteArrayOutputStream().apply {
            write(ogg.headers())
            decodeContainer(container).forEach { write(ogg.packet(it)) }
        }.toByteArray()
        val process = ProcessBuilder(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-i", "pipe:0",
            "-f", "s16le", "-ar", "${LabAudioClip.SAMPLE_RATE}", "-ac", "1", "pipe:1",
        ).start()
        val output = ByteArrayOutputStream()
        val writer = kotlin.concurrent.thread(isDaemon = true) { process.outputStream.use { it.write(pages) } }
        val reader = kotlin.concurrent.thread(isDaemon = true) { process.inputStream.copyTo(output) }
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "无法解码导入结果" }
        writer.join(1_000)
        reader.join(1_000)
        assertEquals(0, output.size() % 2)
        return output.size() / 2
    }
}
