package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText

/** Captures a bounded host-microphone clip and returns robot speaker transport packets. */
internal interface SpeakerInput : AutoCloseable {
    val available: Boolean get() = true
    /** onReady runs only after the first captured samples have been retained. */
    fun start(onReady: () -> Unit, onError: (Throwable) -> Unit)
    suspend fun finish(): ByteArray
    fun cancel()
    override fun close() = cancel()
}

internal class TalkInputFailure(val feedback: UiText) : IllegalStateException(feedback.resolve())

internal class NoSpeakerInput : SpeakerInput {
    override val available = false
    override fun start(onReady: () -> Unit, onError: (Throwable) -> Unit) = throw TalkInputFailure(uiText(Res.string.talk_input_unavailable))
    override suspend fun finish(): ByteArray = byteArrayOf()
    override fun cancel() = Unit
}
