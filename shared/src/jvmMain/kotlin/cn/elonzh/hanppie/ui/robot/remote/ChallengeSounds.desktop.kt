package cn.elonzh.hanppie.ui.robot.remote

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

internal actual fun createChallengeSoundPlayer(): ChallengeSoundPlayer = DesktopChallengeSoundPlayer()

private class DesktopChallengeSoundPlayer : ChallengeSoundPlayer {
    private val clips = mutableMapOf<ChallengeCue, Clip>()
    init {
        try {
            for (cue in ChallengeCue.entries) {
                val clip = AudioSystem.getClip()
                clips[cue] = clip
                val pcm = challengePcm(cue)
                clip.open(AudioFormat(ChallengeSoundRate.toFloat(), 16, 1, true, false), pcm, 0, pcm.size)
            }
        } catch (error: Exception) { close(); throw error }
    }
    override fun play(cue: ChallengeCue) {
        clips.values.forEach { it.stop() }
        clips[cue]?.let { it.framePosition = 0; it.start() }
    }
    override fun close() { clips.values.forEach { runCatching { it.stop(); it.close() } }; clips.clear() }
}
