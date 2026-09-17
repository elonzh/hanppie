package cn.elonzh.hanppie.ui.robot.audio

import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.ui.robot.remote.opusPacketsFromOgg
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop import path: FFmpeg decodes any container it understands, resamples to the DSP container's
 * 48 kHz mono, and re-encodes with libopus in the same parameters the RoboMaster client records with.
 * The executable is overridable through `HANPPIE_FFMPEG`, matching the host PTT encoder.
 */
internal class DesktopLabAudioImporter(
    private val executable: String = System.getenv("HANPPIE_FFMPEG") ?: "ffmpeg",
    private val timeoutMillis: Long = 60_000,
    private val processFactory: (List<String>) -> Process = { ProcessBuilder(it).start() },
) : LabAudioImporter {
    override suspend fun decode(name: String, bytes: ByteArray): DecodedLabAudio = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "音频文件为空：$name" }
        val process = processFactory(
            listOf(executable, "-hide_banner", "-loglevel", "error", "-nostdin",
                "-i", "pipe:0", "-vn", "-map", "a:0", "-ac", "1", "-ar", "${LabAudioClip.SAMPLE_RATE}",
                "-c:a", "libopus", "-application", "audio",
                "-frame_duration", "${LabAudioClip.FRAME_DURATION_MILLIS}",
                "-b:a", "${LabAudioClip.BIT_RATE / 1000}k", "-vbr", "on", "-f", "opus", "pipe:1"),
        )
        val encoded = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val streamFailure = AtomicReference<Throwable?>()
        val writer = thread(isDaemon = true) {
            runCatching { process.outputStream.use { it.write(bytes) } }
                .onFailure { streamFailure.compareAndSet(null, it) }
        }
        val outputReader = thread(isDaemon = true) {
            runCatching { process.inputStream.use { it.copyTo(encoded) } }
                .onFailure { streamFailure.compareAndSet(null, it) }
        }
        val errorReader = thread(isDaemon = true) {
            runCatching { process.errorStream.use { it.copyTo(errors) } }
                .onFailure { streamFailure.compareAndSet(null, it) }
        }
        try {
            check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "音频转码超时：$name" }
            writer.join(1_000)
            outputReader.join(1_000)
            errorReader.join(1_000)
            check(process.exitValue() == 0) {
                errors.toString(Charsets.UTF_8).trim().lineSequence().firstOrNull { it.isNotBlank() }
                    ?: "无法解码该音频文件：$name"
            }
            streamFailure.get()?.let { throw IllegalStateException("音频转码失败：$name", it) }
            val packets = opusPacketsFromOgg(encoded.toByteArray())
            DecodedLabAudio(packetCount(packets) * LabAudioClip.FRAME_DURATION_MILLIS, packets)
        } finally {
            process.outputStream.closeQuietly()
            process.inputStream.closeQuietly()
            process.errorStream.closeQuietly()
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
            writer.join(1_000)
            outputReader.join(1_000)
            errorReader.join(1_000)
        }
    }

    /** Counts packets in the client container; every packet is one 20 ms frame of this encoder. */
    private fun packetCount(packets: ByteArray): Int {
        var index = 0
        var count = 0
        while (index + 2 <= packets.size) {
            val length = (packets[index].toInt() and 255) or ((packets[index + 1].toInt() and 255) shl 8)
            index += 2 + length
            count++
        }
        check(index == packets.size && count > 0) { "Opus 编码结果无法解析" }
        return count
    }
}

private fun AutoCloseable.closeQuietly() {
    runCatching { close() }
}
