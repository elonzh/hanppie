package cn.elonzh.hanppie.ui

/** Captures a bounded host-microphone clip and returns S1 length-prefixed Opus packets. */
internal interface SpeakerInput : AutoCloseable {
    fun start()
    fun finish(): ByteArray
    fun cancel()
    override fun close() = cancel()
}

internal class NoSpeakerInput : SpeakerInput {
    override fun start() = error("当前平台没有可用的对讲输入")
    override fun finish(): ByteArray = byteArrayOf()
    override fun cancel() = Unit
}
