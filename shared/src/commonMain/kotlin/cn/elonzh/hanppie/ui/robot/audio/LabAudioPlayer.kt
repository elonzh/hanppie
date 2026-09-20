package cn.elonzh.hanppie.ui.robot.audio

import cn.elonzh.hanppie.robot.lab.LabAudioClip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class AudioPlaybackState(
    val playingClipId: Int? = null,
    val isPlaying: Boolean = false,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
)

internal interface LabAudioPlayer : AutoCloseable {
    val state: StateFlow<AudioPlaybackState>
    fun play(clip: LabAudioClip)
    fun pause()
    fun resume()
    fun stop()
    override fun close() = stop()
}

internal class NoLabAudioPlayer : LabAudioPlayer {
    override val state: StateFlow<AudioPlaybackState> = MutableStateFlow(AudioPlaybackState())
    override fun play(clip: LabAudioClip) = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun stop() = Unit
}
