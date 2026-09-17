package cn.elonzh.hanppie.ui.robot.audio

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android import path: the platform extractor and decoder turn any supported host file into PCM, the
 * shared resampler moves it to the DSP container's 48 kHz mono, and the platform Opus encoder produces
 * the client's length-prefixed packets.
 */
internal class AndroidLabAudioImporter(timeoutSeconds: Long = 60) : LabAudioImporter {
    private val timeoutNanos = timeoutSeconds * 1_000_000_000L

    override suspend fun decode(name: String, bytes: ByteArray): DecodedLabAudio = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "音频文件为空：$name" }
        val decoded = decodeToPcm(name, bytes)
        val mono = downmixToMono(decoded.pcm, decoded.channels)
        val resampled = resampleMono16(mono, decoded.sampleRate, LabAudioClip.SAMPLE_RATE)
        val durationMillis = resampled.size.toLong() / 2 * 1_000 / LabAudioClip.SAMPLE_RATE
        val packets = encodeOpusPackets(
            pcm = resampled,
            sampleRate = LabAudioClip.SAMPLE_RATE,
            frameSamples = LabAudioClip.FRAME_SAMPLES,
            bitRate = LabAudioClip.BIT_RATE,
        )
        DecodedLabAudio(durationMillis, packets)
    }

    private class DecodedPcm(val pcm: ByteArray, val channels: Int, val sampleRate: Int)

    private fun decodeToPcm(name: String, bytes: ByteArray): DecodedPcm {
        val source = ByteArrayMediaDataSource(bytes)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: error("文件中没有音频轨道：$name")
            extractor.selectTrack(track)
            val trackFormat = extractor.getTrackFormat(track)
            decoder = MediaCodec.createDecoderByType(
                requireNotNull(trackFormat.getString(MediaFormat.KEY_MIME)),
            )
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()
            var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val output = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + timeoutNanos
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                check(System.nanoTime() < deadline) { "音频解码超时：$name" }
                if (!inputDone) {
                    val input = decoder.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(input)).apply { clear() }
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        checkPcm16(format, name)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        if (info.size > 0) {
                            val buffer = requireNotNull(decoder.getOutputBuffer(index))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            output.write(chunk)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
            val pcm = output.toByteArray()
            require(pcm.isNotEmpty()) { "音频解码没有输出：$name" }
            return DecodedPcm(pcm, channels, sampleRate)
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
            runCatching { source.close() }
        }
    }

    private fun checkPcm16(format: MediaFormat, name: String) {
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            android.media.AudioFormat.ENCODING_PCM_16BIT
        }
        check(encoding == android.media.AudioFormat.ENCODING_PCM_16BIT) {
            "解码输出不是 16 位 PCM，无法导入：$name"
        }
    }
}

/** Extractor source over an in-memory file, so import needs no temporary file and no storage grant. */
private class ByteArrayMediaDataSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(size.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit
}
