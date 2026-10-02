package cn.elonzh.hanppie.ui.robot.audio

import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.ui.platform.IosPlatformServices
import cn.elonzh.hanppie.ui.platform.toPlatformData
import cn.elonzh.hanppie.ui.platform.toByteArray
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class IosLabAudioImporter(private val platform: IosPlatformServices) : LabAudioImporter {
    override suspend fun decode(name: String, bytes: ByteArray): DecodedLabAudio = suspendCancellableCoroutine { continuation ->
        platform.encodeAudio(name, bytes.toPlatformData()) { encoded, duration, error ->
            if (continuation.isActive) {
                if (encoded != null && error == null) continuation.resume(DecodedLabAudio(duration, encoded.toByteArray()))
                else continuation.resumeWithException(IllegalStateException(error ?: "音频转换失败"))
            }
        }
    }
}
internal class IosLabAudioPlayer(private val platform: IosPlatformServices) : LabAudioPlayer {
    override val state = MutableStateFlow(AudioPlaybackState())
    private var generation = 0
    override fun play(clip: LabAudioClip) {
        val token = ++generation
        platform.playAudio(clip.id, clip.packets.toPlatformData()) { position, duration, playing ->
            if (token == generation) state.value =
                if (!playing && (duration <= 0 || position >= duration)) AudioPlaybackState()
                else AudioPlaybackState(clip.id, playing, position, duration)
        }
    }
    override fun pause() { platform.pauseAudio(); state.value = state.value.copy(isPlaying = false) }
    override fun resume() { if (state.value.playingClipId != null) { platform.resumeAudio(); state.value = state.value.copy(isPlaying = true) } }
    override fun stop() { generation++; platform.stopAudio(); state.value = AudioPlaybackState() }
}
