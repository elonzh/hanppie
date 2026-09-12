package cn.elonzh.hanppie.ui

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

internal class DesktopSpeakerInput(
    private val executable: String = System.getenv("HANPPIE_FFMPEG") ?: "ffmpeg",
    private val lineFactory: (AudioFormat) -> TargetDataLine = AudioSystem::getTargetDataLine,
    private val encoder: (ByteArray) -> ByteArray = { encodeDesktopSpeakerPcm(it, executable) },
) : SpeakerInput {
    private val recording = AtomicBoolean(false)
    private var line: TargetDataLine? = null
    private var worker: Thread? = null
    private var pcm = ByteArrayOutputStream()

    @Synchronized override fun start(onReady: () -> Unit) {
        check(recording.compareAndSet(false, true)) { "对讲已在录音" }
        pcm = ByteArrayOutputStream()
        val format = AudioFormat(12_000f, 16, 1, true, false)
        val input = try { lineFactory(format) } catch (error: Exception) { recording.set(false); throw error }
        try {
            input.open(format, maxOf(1_920, input.bufferSize))
            input.start()
            line = input
            worker = thread(name = "hanppie-push-to-talk", isDaemon = true) {
                val buffer = ByteArray(480)
                var ready = false
                val maximum = 12_000 * 2 * 15
                while (recording.get() && pcm.size() < maximum) {
                    val count = input.read(buffer, 0, minOf(buffer.size, maximum - pcm.size()))
                    if (count > 0) {
                        synchronized(pcm) { pcm.write(buffer, 0, count) }
                        if (!ready) { ready = true; onReady() }
                    }
                }
                recording.set(false)
            }
        } catch (error: Exception) {
            recording.set(false)
            runCatching { input.close() }
            throw error
        }
    }

    override fun finish(): ByteArray {
        val wasRecording = recording.getAndSet(false)
        check(wasRecording || worker != null) { "对讲未开始" }
        runCatching { line?.stop() }
        line?.close()
        worker?.join(1_000)
        line = null
        worker = null
        val captured = synchronized(pcm) { pcm.toByteArray() }
        require(captured.size >= 480) { "对讲录音过短" }
        return encoder(captured)
    }

    override fun cancel() {
        recording.set(false)
        runCatching { line?.stop() }
        line?.close()
        worker?.join(1_000)
        line = null
        worker = null
        pcm.reset()
    }
}

internal fun encodeDesktopSpeakerPcm(pcm: ByteArray, executable: String = "ffmpeg"): ByteArray {
    require(pcm.isNotEmpty() && pcm.size % 2 == 0)
    val process = ProcessBuilder(executable, "-hide_banner", "-loglevel", "error", "-nostdin",
        "-f", "s16le", "-ar", "12000", "-ac", "1", "-i", "pipe:0",
        "-c:a", "libopus", "-application", "voip", "-frame_duration", "20",
        "-b:a", "10k", "-vbr", "on", "-f", "opus", "pipe:1").start()
    val errors = ByteArrayOutputStream()
    val errorReader = thread(isDaemon = true) { process.errorStream.use { it.copyTo(errors) } }
    val writer = thread(isDaemon = true) {
        process.outputStream.use { it.write(pcm) }
    }
    val ogg = process.inputStream.use { it.readBytes() }
    writer.join(2_000)
    val exit = process.waitFor()
    errorReader.join(1_000)
    check(exit == 0) { errors.toString(Charsets.UTF_8).trim().ifBlank { "FFmpeg Opus 编码失败" } }
    return opusPacketsFromOgg(ogg)
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
