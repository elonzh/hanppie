@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.robot.remote

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Common lifecycle for a bounded 12 kHz mono PCM capture backed by a platform device. */
internal class BoundedPcmCapture(
    private val deviceFactory: () -> PcmCaptureDevice,
    private val encoder: (ByteArray) -> ByteArray,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SpeakerInput {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val recording = AtomicBoolean(false)
    private val device = AtomicReference<PcmCaptureDevice?>(null)
    private val worker = AtomicReference<Job?>(null)
    private val captureFailure = AtomicReference<Throwable?>(null)
    private val captured = AtomicReference<ByteArray?>(null)
    private val capturedSize = AtomicInt(0)

    override fun start(onReady: () -> Unit) {
        check(recording.compareAndSet(expectedValue = false, newValue = true)) { "对讲已在录音" }
        val buffer = ByteArray(MAXIMUM_BYTES)
        captured.store(buffer)
        capturedSize.store(0)
        captureFailure.store(null)
        val input = try {
            deviceFactory()
        } catch (error: Throwable) {
            recording.store(false)
            throw error
        }
        try {
            input.start()
            device.store(input)
            worker.store(scope.launch {
                val frame = ByteArray(FRAME_BYTES)
                var ready = false
                try {
                    while (recording.load()) {
                        val retained = capturedSize.load()
                        if (retained >= buffer.size) break
                        val count = input.read(frame, minOf(frame.size, buffer.size - retained))
                        if (count < 0 && recording.load()) error("麦克风读取失败：$count")
                        if (count > 0) {
                            frame.copyInto(buffer, destinationOffset = retained, endIndex = count)
                            capturedSize.store(retained + count)
                            if (!ready) {
                                ready = true
                                onReady()
                            }
                        }
                    }
                } catch (error: Throwable) {
                    if (recording.load()) captureFailure.store(error)
                } finally {
                    recording.store(false)
                }
            })
        } catch (error: Throwable) {
            recording.store(false)
            runCatching { input.stop() }
            runCatching { input.close() }
            device.store(null)
            throw error
        }
    }

    override suspend fun finish(): ByteArray {
        val wasRecording = recording.exchange(false)
        check(wasRecording || worker.load() != null) { "对讲未开始" }
        val input = device.exchange(null)
        runCatching { input?.stop() }
        worker.exchange(null)?.join()
        runCatching { input?.close() }
        captureFailure.exchange(null)?.let { throw IllegalStateException(it.message ?: "麦克风读取失败", it) }
        val size = capturedSize.exchange(0)
        val pcm = checkNotNull(captured.exchange(null)).copyOf(size)
        require(pcm.size >= FRAME_BYTES) { "对讲录音过短" }
        return encoder(pcm)
    }

    override fun cancel() {
        recording.store(false)
        val input = device.exchange(null)
        runCatching { input?.stop() }
        runCatching { input?.close() }
        worker.exchange(null)?.cancel()
        captureFailure.store(null)
        captured.store(null)
        capturedSize.store(0)
    }

    override fun close() {
        cancel()
        scope.cancel()
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
