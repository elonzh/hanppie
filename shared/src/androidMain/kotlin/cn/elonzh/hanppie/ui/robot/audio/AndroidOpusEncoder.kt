package cn.elonzh.hanppie.ui.robot.audio

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Android Opus encode path: PCM in, length-prefixed Opus packets out.
 *
 * The robot speaker transport and the DSP custom-audio container share this exact container, so both
 * callers encode through here; they only differ in sample rate, frame size and bit rate. The platform
 * encoder exists from API 29 on, which is why both callers surface the codec lookup failure directly.
 */
internal fun encodeOpusPackets(
    pcm: ByteArray,
    sampleRate: Int,
    frameSamples: Int,
    bitRate: Int,
    timeout: Long = 10,
    timeoutUnit: TimeUnit = TimeUnit.SECONDS,
): ByteArray {
    require(pcm.isNotEmpty()) { "PCM 为空" }
    val frameBytes = frameSamples * 2
    val padded = framePadded(pcm, frameBytes)
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    val encoded = ByteArrayOutputStream()
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        val frameMicros = 1_000_000L * frameSamples / sampleRate
        var offset = 0
        var frame = 0L
        var eosQueued = false
        var eosRead = false
        val deadline = System.nanoTime() + timeoutUnit.toNanos(timeout)
        while (!eosRead) {
            check(System.nanoTime() < deadline) { "Opus 编码超时" }
            if (!eosQueued) {
                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val buffer = requireNotNull(codec.getInputBuffer(input)).apply { clear() }
                    if (offset < padded.size) {
                        buffer.put(padded, offset, frameBytes)
                        codec.queueInputBuffer(input, 0, frameBytes, frame * frameMicros, 0)
                        offset += frameBytes
                        frame++
                    } else {
                        codec.queueInputBuffer(input, 0, 0, frame * frameMicros, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    }
                }
            }
            var output = codec.dequeueOutputBuffer(info, 10_000)
            while (output >= 0) {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val buffer = requireNotNull(codec.getOutputBuffer(output))
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    require(info.size <= 0xffff) { "Opus 单包过大" }
                    encoded.write(info.size and 0xff)
                    encoded.write(info.size ushr 8)
                    val packet = ByteArray(info.size)
                    buffer.get(packet)
                    encoded.write(packet)
                }
                eosRead = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(output, false)
                output = codec.dequeueOutputBuffer(info, 0)
            }
        }
    } finally {
        runCatching { codec.stop() }
        codec.release()
    }
    return encoded.toByteArray().also { require(it.isNotEmpty()) { "Opus 编码没有输出" } }
}
