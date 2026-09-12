package cn.elonzh.hanppie.ui

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Platform-neutral lifecycle for a bounded 12 kHz mono PCM capture. */
internal class BoundedPcmCapture(
    private val deviceFactory: () -> PcmCaptureDevice,
    private val encoder: (ByteArray) -> ByteArray,
) : SpeakerInput {
    private val recording = AtomicBoolean(false)
    private var device: PcmCaptureDevice? = null
    private var worker: Thread? = null
    private var pcm = ByteArrayOutputStream()
    @Volatile private var captureFailure: Throwable? = null

    @Synchronized
    override fun start(onReady: () -> Unit) {
        check(recording.compareAndSet(false, true)) { "对讲已在录音" }
        pcm = ByteArrayOutputStream()
        captureFailure = null
        val input = try {
            deviceFactory()
        } catch (error: Throwable) {
            recording.set(false)
            throw error
        }
        try {
            input.start()
            device = input
            worker = thread(name = "hanppie-push-to-talk", isDaemon = true) {
                val buffer = ByteArray(FRAME_BYTES)
                var ready = false
                try {
                    while (recording.get() && pcm.size() < MAXIMUM_BYTES) {
                        val count = input.read(buffer, minOf(buffer.size, MAXIMUM_BYTES - pcm.size()))
                        if (count < 0 && recording.get()) error("麦克风读取失败：$count")
                        if (count > 0) {
                            synchronized(pcm) { pcm.write(buffer, 0, count) }
                            if (!ready) {
                                ready = true
                                onReady()
                            }
                        }
                    }
                } catch (error: Throwable) {
                    if (recording.get()) captureFailure = error
                } finally {
                    recording.set(false)
                }
            }
        } catch (error: Throwable) {
            recording.set(false)
            runCatching { input.stop() }
            runCatching { input.close() }
            throw error
        }
    }

    @Synchronized
    override fun finish(): ByteArray {
        val wasRecording = recording.getAndSet(false)
        check(wasRecording || worker != null) { "对讲未开始" }
        releaseDevice()
        captureFailure?.let { throw IllegalStateException(it.message ?: "麦克风读取失败", it) }
        val captured = synchronized(pcm) { pcm.toByteArray() }
        require(captured.size >= FRAME_BYTES) { "对讲录音过短" }
        return encoder(captured)
    }

    @Synchronized
    override fun cancel() {
        recording.set(false)
        releaseDevice()
        captureFailure = null
        pcm.reset()
    }

    private fun releaseDevice() {
        runCatching { device?.stop() }
        runCatching { device?.close() }
        worker?.join(1_000)
        device = null
        worker = null
    }

    private companion object {
        const val FRAME_BYTES = 480
        const val MAXIMUM_BYTES = 12_000 * 2 * 15
    }
}

internal interface PcmCaptureDevice : AutoCloseable {
    fun start()
    fun read(buffer: ByteArray, length: Int): Int
    fun stop()
}
