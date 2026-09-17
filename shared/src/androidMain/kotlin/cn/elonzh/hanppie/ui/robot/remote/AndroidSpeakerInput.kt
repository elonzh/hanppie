package cn.elonzh.hanppie.ui.robot.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import cn.elonzh.hanppie.ui.robot.audio.encodeOpusPackets

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

private fun encodeAndroidSpeakerPcm(source: ByteArray): ByteArray = encodeOpusPackets(
    pcm = source,
    sampleRate = 12_000,
    frameSamples = 240,
    bitRate = 10_000,
)
