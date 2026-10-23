@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.ui.platform.toPlatformData
import platform.AVFAudio.AVAudioPlayer

internal actual fun createChallengeSoundPlayer(): ChallengeSoundPlayer = IosChallengeSoundPlayer()

private class IosChallengeSoundPlayer : ChallengeSoundPlayer {
    private val players = mutableMapOf<ChallengeCue, AVAudioPlayer>()
    init {
        try {
            for (cue in ChallengeCue.entries) {
                val player = AVAudioPlayer(data = challengeWav(cue).toPlatformData(), error = null)
                players[cue] = player
                check(player.prepareToPlay())
            }
        } catch (error: Exception) { close(); throw error }
    }
    override fun play(cue: ChallengeCue) {
        players.values.forEach { it.stop() }
        players[cue]?.let { it.currentTime = 0.0; check(it.play()) }
    }
    override fun close() { players.values.forEach { it.stop() }; players.clear() }
}
