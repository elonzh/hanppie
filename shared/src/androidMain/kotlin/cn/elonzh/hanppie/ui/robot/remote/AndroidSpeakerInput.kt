package cn.elonzh.hanppie.ui.robot.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

internal class AndroidSpeakerInput(private val context: Context) : SpeakerInput {
    private val capture = BoundedPcmCapture(::createDevice, ::encodeAndroidSpeakerPcm)

    override fun start(onReady: () -> Unit) {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "需要麦克风权限才能对讲"
        }
        capture.start(onReady)
    }

    override suspend fun finish(): ByteArray = capture.finish()

    override fun cancel() = capture.cancel()

    private fun createDevice(): PcmCaptureDevice {
        val minimum = AudioRecord.getMinBufferSize(12_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "设备不支持 12 kHz 单声道录音" }
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(12_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 1_920)).build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            return object : PcmCaptureDevice {
                override fun start() = recorder.startRecording()
                override fun read(buffer: ByteArray, length: Int): Int = recorder.read(buffer, 0, length)
                override fun stop() = recorder.stop()
                override fun close() = recorder.release()
            }
        } catch (error: Throwable) {
            recorder.release()
            throw error
        }
    }
}

private fun encodeAndroidSpeakerPcm(source: ByteArray): ByteArray {
    val frameBytes = 480
    val padded = source.copyOf(((source.size + frameBytes - 1) / frameBytes) * frameBytes)
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 12_000, 1).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, 10_000)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    val encoded = ByteArrayOutputStream()
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var offset = 0
        var frame = 0L
        var eosQueued = false
        var eosRead = false
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!eosRead) {
            check(System.nanoTime() < deadline) { "Opus 编码超时" }
            if (!eosQueued) {
                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val buffer = requireNotNull(codec.getInputBuffer(input)).apply { clear() }
                    if (offset < padded.size) {
                        buffer.put(padded, offset, frameBytes)
                        codec.queueInputBuffer(input, 0, frameBytes, frame * 20_000, 0)
                        offset += frameBytes; frame++
                    } else {
                        codec.queueInputBuffer(input, 0, 0, frame * 20_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    }
                }
            }
            var output = codec.dequeueOutputBuffer(info, 10_000)
            while (output >= 0) {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val buffer = requireNotNull(codec.getOutputBuffer(output))
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    require(info.size <= 0xffff)
                    encoded.write(info.size and 0xff); encoded.write(info.size ushr 8)
                    val packet = ByteArray(info.size); buffer.get(packet); encoded.write(packet)
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
