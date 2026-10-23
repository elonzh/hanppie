package cn.elonzh.hanppie.ui.robot.remote

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

internal actual fun createChallengeSoundPlayer(): ChallengeSoundPlayer = AndroidChallengeSoundPlayer()

private class AndroidChallengeSoundPlayer : ChallengeSoundPlayer {
    private val tracks = mutableMapOf<ChallengeCue, AudioTrack>()
    init {
        try {
            for (cue in ChallengeCue.entries) {
                val pcm = challengePcm(cue)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(ChallengeSoundRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
                tracks[cue] = track
                check(track.write(pcm, 0, pcm.size) == pcm.size)
            }
        } catch (error: Exception) { close(); throw error }
    }
    override fun play(cue: ChallengeCue) {
        tracks.values.forEach { it.stop() }
        tracks[cue]?.let { check(it.reloadStaticData() == AudioTrack.SUCCESS); it.play() }
    }
    override fun close() { tracks.values.forEach { runCatching { it.stop(); it.release() } }; tracks.clear() }
}
