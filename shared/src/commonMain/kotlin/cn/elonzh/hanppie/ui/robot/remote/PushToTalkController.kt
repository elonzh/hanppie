package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.session.RobotLock
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class TalkPhase { IDLE, PREPARING, RECORDING, SENDING }
internal data class PushToTalkState(val phase: TalkPhase = TalkPhase.IDLE, val error: UiText? = null) {
    val recording: Boolean get() = phase == TalkPhase.PREPARING || phase == TalkPhase.RECORDING
    val busy: Boolean get() = phase == TalkPhase.SENDING
    val active: Boolean get() = phase != TalkPhase.IDLE
}

/** Owns one short host recording and its feedback. Sending retains the existing release-to-upload path. */
internal class PushToTalkController(private val scope: CoroutineScope, private val input: SpeakerInput,
    private val onSent: (Int) -> Unit = {}, private val onError: (Throwable) -> Unit = {}) : AutoCloseable {
    private val lock = RobotLock()
    private val mutable = MutableStateFlow(PushToTalkState())
    val state = mutable.asStateFlow()
    val available: Boolean get() = input.available
    private var revision = 0L
    private var closed = false
    private var sendJob: Job? = null

    fun start() = lock.withLock {
        if (closed || state.value.active) return@withLock
        val token = ++revision
        mutable.value = PushToTalkState(TalkPhase.PREPARING)
        try {
            input.start(onReady = { lock.withLock {
                if (!closed && revision == token && state.value.phase == TalkPhase.PREPARING)
                    mutable.value = state.value.copy(phase = TalkPhase.RECORDING)
            } }, onError = { error -> lock.withLock {
                if (!closed && revision == token && state.value.recording) captureFailed(error)
            } })
        } catch (error: CancellationException) {
            cancel(); throw error
        } catch (error: Exception) { captureFailed(error) }
    }

    fun finish(send: suspend (ByteArray) -> Int) = lock.withLock {
        if (closed || !state.value.recording) return@withLock
        if (state.value.phase == TalkPhase.PREPARING) { cancel(); return@withLock }
        val token = revision
        mutable.value = state.value.copy(phase = TalkPhase.SENDING)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var captured = false
            try {
                val bytes = input.finish()
                currentCoroutineContext().ensureActive()
                captured = true
                val packets = send(bytes)
                lock.withLock { if (!closed && revision == token) onSent(packets) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { lock.withLock {
                if (!closed && revision == token) {
                    onError(error)
                    mutable.value = state.value.copy(error = (error as? TalkInputFailure)?.feedback ?: uiText(
                        if (captured) Res.string.talk_send_failed_value else Res.string.talk_capture_failed_value,
                        error.message ?: error::class.simpleName.orEmpty()))
                }
            } }
        }
        sendJob = job
        job.invokeOnCompletion { lock.withLock {
            if (revision == token) { sendJob = null; mutable.value = state.value.copy(phase = TalkPhase.IDLE) }
        } }
        job.start()
    }

    /** Leaving control cancels capture; an already released upload retains its existing session ownership. */
    fun cancel() = lock.withLock {
        if (!state.value.recording) return@withLock
        ++revision
        input.cancel()
        mutable.value = state.value.copy(phase = TalkPhase.IDLE)
    }

    fun dismissError() = lock.withLock { mutable.value = state.value.copy(error = null) }

    private fun captureFailed(error: Throwable) {
        ++revision
        input.cancel()
        onError(error)
        mutable.value = PushToTalkState(error = (error as? TalkInputFailure)?.feedback ?: uiText(
            Res.string.talk_capture_failed_value, error.message ?: error::class.simpleName.orEmpty()))
    }

    override fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true; ++revision
        input.close(); sendJob?.cancel(); sendJob = null
        mutable.value = PushToTalkState()
    }
}
