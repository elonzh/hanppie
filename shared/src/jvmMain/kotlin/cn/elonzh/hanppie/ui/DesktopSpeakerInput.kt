package cn.elonzh.hanppie.ui

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

internal class DesktopSpeakerInput(
    private val executable: String = System.getenv("HANPPIE_FFMPEG") ?: "ffmpeg",
    private val lineFactory: (AudioFormat) -> TargetDataLine = AudioSystem::getTargetDataLine,
    encoder: ((ByteArray) -> ByteArray)? = null,
    private val encoderTimeoutMillis: Long = 10_000,
    private val processFactory: (List<String>) -> Process = { ProcessBuilder(it).start() },
) : SpeakerInput {
    private val format = AudioFormat(12_000f, 16, 1, true, false)
    private val activeEncoderProcess = AtomicReference<Process?>()
    private val pcmEncoder = encoder ?: { pcm: ByteArray ->
        encodeDesktopSpeakerPcm(pcm, executable, encoderTimeoutMillis, processFactory, activeEncoderProcess::set)
    }
    private val capture = BoundedPcmCapture(
        deviceFactory = {
            val input = lineFactory(format)
            object : PcmCaptureDevice {
                override fun start() {
                    input.open(format, maxOf(1_920, input.bufferSize))
                    input.start()
                }

                override fun read(buffer: ByteArray, length: Int): Int = input.read(buffer, 0, length)
                override fun stop() = input.stop()
                override fun close() = input.close()
            }
        },
        encoder = pcmEncoder,
    )

    override fun start(onReady: () -> Unit) = capture.start(onReady)
    override fun finish(): ByteArray = capture.finish()
    override fun cancel() {
        activeEncoderProcess.getAndSet(null)?.destroyForcibly()
        capture.cancel()
    }
}

internal fun encodeDesktopSpeakerPcm(
    pcm: ByteArray,
    executable: String = "ffmpeg",
    timeoutMillis: Long = 10_000,
    processFactory: (List<String>) -> Process = { ProcessBuilder(it).start() },
    onProcessChanged: (Process?) -> Unit = {},
): ByteArray {
    require(pcm.isNotEmpty() && pcm.size % 2 == 0)
    val command = listOf(executable, "-hide_banner", "-loglevel", "error", "-nostdin",
        "-f", "s16le", "-ar", "12000", "-ac", "1", "-i", "pipe:0",
        "-c:a", "libopus", "-application", "voip", "-frame_duration", "20",
        "-b:a", "10k", "-vbr", "on", "-f", "opus", "pipe:1")
    val process = processFactory(command)
    onProcessChanged(process)
    val ogg = ByteArrayOutputStream()
    val errors = ByteArrayOutputStream()
    val streamFailure = AtomicReference<Throwable?>()
    val outputReader = thread(isDaemon = true) {
        runCatching { process.inputStream.use { it.copyTo(ogg) } }
            .onFailure { streamFailure.compareAndSet(null, it) }
    }
    val errorReader = thread(isDaemon = true) {
        runCatching { process.errorStream.use { it.copyTo(errors) } }
            .onFailure { streamFailure.compareAndSet(null, it) }
    }
    val writer = thread(isDaemon = true) {
        runCatching { process.outputStream.use { it.write(pcm) } }
            .onFailure { streamFailure.compareAndSet(null, it) }
    }
    try {
        check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "FFmpeg Opus 编码超时" }
        writer.join(1_000)
        outputReader.join(1_000)
        errorReader.join(1_000)
        check(process.exitValue() == 0) {
            errors.toString(Charsets.UTF_8).trim().ifBlank { "FFmpeg Opus 编码失败" }
        }
        streamFailure.get()?.let { throw IllegalStateException("FFmpeg 数据传输失败", it) }
        return opusPacketsFromOgg(ogg.toByteArray())
    } finally {
        onProcessChanged(null)
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

private fun AutoCloseable.closeQuietly() {
    runCatching { close() }
}

internal fun opusPacketsFromOgg(ogg: ByteArray): ByteArray {
    val packets = mutableListOf<ByteArray>()
    var pending = ByteArrayOutputStream()
    var offset = 0
    while (offset < ogg.size) {
        require(offset + 27 <= ogg.size && ogg.copyOfRange(offset, offset + 4).contentEquals("OggS".toByteArray())) {
            "无效的 Ogg Opus 数据"
        }
        val segments = ogg[offset + 26].toInt() and 0xff
        require(offset + 27 + segments <= ogg.size)
        val table = offset + 27
        var data = table + segments
        repeat(segments) { index ->
            val length = ogg[table + index].toInt() and 0xff
            require(data + length <= ogg.size)
            pending.write(ogg, data, length)
            data += length
            if (length < 255) {
                val packet = pending.toByteArray()
                if (!packet.startsWithAscii("OpusHead") && !packet.startsWithAscii("OpusTags")) packets += packet
                pending = ByteArrayOutputStream()
            }
        }
        offset = data
    }
    require(pending.size() == 0 && packets.isNotEmpty()) { "Ogg 中没有完整的 Opus 音频包" }
    return ByteArrayOutputStream().apply {
        packets.forEach { packet ->
            require(packet.size <= 0xffff)
            write(packet.size and 0xff); write(packet.size ushr 8); write(packet)
        }
    }.toByteArray()
}

private fun ByteArray.startsWithAscii(text: String): Boolean =
    size >= text.length && text.indices.all { this[it] == text[it].code.toByte() }
