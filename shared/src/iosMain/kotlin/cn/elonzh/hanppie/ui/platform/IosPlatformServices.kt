package cn.elonzh.hanppie.ui.platform

import platform.UIKit.UIView
import platform.Foundation.NSData

/** Apple media and permission services injected by the Swift host. No robot or agent business logic. */
interface IosPlatformServices {
    fun videoView(): UIView
    fun configureVideo(onFrame: (NSData) -> Unit, onError: (String) -> Unit)
    fun videoPacket(bytes: NSData)
    fun audioPacket(bytes: NSData)
    fun stopVideo()
    fun takePhoto(completion: (String?, String?) -> Unit)
    fun setRecording(enabled: Boolean, completion: (String?, String?) -> Unit)
    fun encodeAudio(name: String, bytes: NSData, completion: (NSData?, Long, String?) -> Unit)
    fun playAudio(id: Int, bytes: NSData, onProgress: (Long, Long, Boolean) -> Unit)
    fun pauseAudio()
    fun resumeAudio()
    fun stopAudio()
    fun startTalk(onReady: () -> Unit, onError: (String) -> Unit)
    fun finishTalk(completion: (NSData?, String?) -> Unit)
    fun cancelTalk()
    fun startSpeech(languageTag: String, onResult: (String, Boolean, String?) -> Unit)
    fun finishSpeech()
    fun cancelSpeech()
}

internal val LocalIosPlatformServices = androidx.compose.runtime.staticCompositionLocalOf<IosPlatformServices> {
    error("iOS platform services must be initialized by the host")
}
