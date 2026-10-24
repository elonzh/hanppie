@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.robot.remote

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.uiText

/** Common lifecycle for a bounded 12 kHz mono PCM capture backed by a platform device. */
internal class BoundedPcmCapture(
    private val deviceFactory: () -> PcmCaptureDevice,
    private val encoder: (ByteArray) -> ByteArray,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SpeakerInput {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private class Capture {
        val recording = AtomicBoolean(true)
        val buffer = ByteArray(MAXIMUM_BYTES)
        val size = AtomicInt(0)
        val device = AtomicReference<PcmCaptureDevice?>(null)
        val worker = AtomicReference<Job?>(null)
        val failure = AtomicReference<Throwable?>(null)
        fun cancel() {
            recording.store(false)
            val input = device.exchange(null)
            runCatching { input?.stop() }; runCatching { input?.close() }
            worker.exchange(null)?.cancel()
        }
    }
    private val current = AtomicReference<Capture?>(null)

    override fun start(onReady: () -> Unit, onError: (Throwable) -> Unit) {
        val capture = Capture()
        check(current.compareAndSet(null, capture)) { "Talk capture is already active" }
        try {
            val input = deviceFactory()
            capture.device.store(input)
            if (current.load() !== capture) { capture.cancel(); return }
            input.start()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val frame = ByteArray(FRAME_BYTES)
                var ready = false
                try {
                    while (capture.recording.load()) {
                        val retained = capture.size.load()
                        if (retained >= capture.buffer.size) break
                        val count = input.read(frame, minOf(frame.size, capture.buffer.size - retained))
                        if (!capture.recording.load()) break
                        if (count < 0) error("Microphone read failed: $count")
                        if (count > 0) {
                            frame.copyInto(capture.buffer, destinationOffset = retained, endIndex = count)
                            capture.size.store(retained + count)
                            if (!ready) {
                                ready = true
                                onReady()
                            }
                        }
                    }
                } catch (error: Throwable) {
                    if (capture.recording.load()) {
                        capture.failure.store(error)
                        capture.recording.store(false)
                        if (current.load() === capture) onError(error)
                    }
                } finally {
                    capture.recording.store(false)
                }
            }
            capture.worker.store(job)
            if (current.load() === capture) job.start() else capture.cancel()
        } catch (error: Throwable) {
            current.compareAndSet(capture, null)
            capture.cancel()
            throw error
        }
    }

    override suspend fun finish(): ByteArray {
        val capture = checkNotNull(current.exchange(null)) { "Talk capture has not started" }
        capture.recording.store(false)
        val input = capture.device.exchange(null)
        runCatching { input?.stop() }
        try { capture.worker.exchange(null)?.join() } finally { runCatching { input?.close() } }
        capture.failure.load()?.let { throw it }
        val pcm = capture.buffer.copyOf(capture.size.load())
        if (pcm.size < FRAME_BYTES) throw TalkInputFailure(uiText(Res.string.talk_too_short))
        return encoder(pcm)
    }

    override fun cancel() {
        current.exchange(null)?.cancel()
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
