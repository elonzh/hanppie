package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.ui.platform.IosPlatformServices
import cn.elonzh.hanppie.ui.platform.toByteArray
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class IosSpeakerInput(private val platform: IosPlatformServices, private val report: (String) -> Unit) : SpeakerInput {
    override fun start(onReady: () -> Unit) = platform.startTalk(onReady, report)
    override suspend fun finish(): ByteArray = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { platform.cancelTalk() }
        platform.finishTalk { bytes, error ->
            if (continuation.isActive) {
                if (bytes != null && error == null) continuation.resume(bytes.toByteArray())
                else continuation.resumeWithException(IllegalStateException(error ?: "对讲录音失败"))
            }
        }
    }
    override fun cancel() = platform.cancelTalk()
}
