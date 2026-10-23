package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.robot.challenge.*
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

internal enum class ChallengeCue { AIM, COUNTDOWN, START, HIT, CLEAR, FINISH }

/** Local, optional feedback. Never writes robot audio, firing or motion commands. */
internal interface ChallengeSoundPlayer : AutoCloseable {
    fun play(cue: ChallengeCue)
    override fun close()
}
internal expect fun createChallengeSoundPlayer(): ChallengeSoundPlayer

/** A newly mounted player starts at the current state, so resume cannot replay an old cue. */
internal class ChallengeCueTracker(initial: ChallengeState) {
    private var previous = initial
    fun update(state: ChallengeState): ChallengeCue? {
        val old = previous; previous = state
        val hit = state.hit
        if (!state.ownsRound || state.roundId != old.roundId) return null
        return when {
            state.end != old.end && state.end in listOf(ChallengeEnd.COMPLETED, ChallengeEnd.TIME_EXPIRED) -> ChallengeCue.FINISH
            state.phase == ChallengePhase.WAVE_CLEAR && old.phase != state.phase -> ChallengeCue.CLEAR
            hit != null && hit.sequence != old.hit?.sequence && state.hitAgeMillis?.let { it in 0..150 } == true -> ChallengeCue.HIT
            old.phase == ChallengePhase.COUNTDOWN && state.phase == ChallengePhase.PLAYING -> ChallengeCue.START
            state.phase == ChallengePhase.COUNTDOWN && (old.phase != state.phase ||
                (state.remainingMillis + 999) / 1000 != (old.remainingMillis + 999) / 1000) -> ChallengeCue.COUNTDOWN
            state.phase == ChallengePhase.PLAYING && state.aimedMarkerId != null && state.aimedMarkerId != old.aimedMarkerId -> ChallengeCue.AIM
            else -> null
        }
    }
}

internal const val ChallengeSoundRate = 24_000

/** Original low-level PCM tones with click-free edges; no shipped or copied audio assets. */
internal fun challengePcm(cue: ChallengeCue): ByteArray {
    val notes = when (cue) {
        ChallengeCue.AIM -> listOf(880 to 55)
        ChallengeCue.COUNTDOWN -> listOf(660 to 80)
        ChallengeCue.START -> listOf(880 to 80, 1320 to 100)
        ChallengeCue.HIT -> listOf(1568 to 40, 1046 to 55)
        ChallengeCue.CLEAR -> listOf(1046 to 75, 1318 to 85)
        ChallengeCue.FINISH -> listOf(1046 to 75, 1318 to 75, 1568 to 100)
    }
    val data = ByteArray(notes.sumOf { (_, millis) -> ChallengeSoundRate * millis / 1000 } * 2)
    var offset = 0
    for ((frequency, millis) in notes) {
        val samples = ChallengeSoundRate * millis / 1000
        repeat(samples) { i ->
            val envelope = min(1.0, min(i / 120.0, (samples - 1 - i) / 360.0))
            val value = (sin(2 * PI * frequency * i / ChallengeSoundRate) * envelope * 4000).toInt()
            data[offset++] = value.toByte(); data[offset++] = (value shr 8).toByte()
        }
    }
    return data
}

internal fun challengeWav(cue: ChallengeCue): ByteArray {
    val pcm = challengePcm(cue)
    return ByteArray(44 + pcm.size).apply {
        fun text(offset: Int, value: String) { value.forEachIndexed { index, c -> this[offset + index] = c.code.toByte() } }
        fun int(offset: Int, value: Int, length: Int = 4) { repeat(length) { this[offset + it] = (value ushr (it * 8)).toByte() } }
        text(0, "RIFF"); int(4, 36 + pcm.size); text(8, "WAVEfmt "); int(16, 16)
        int(20, 1, 2); int(22, 1, 2); int(24, ChallengeSoundRate); int(28, ChallengeSoundRate * 2)
        int(32, 2, 2); int(34, 16, 2); text(36, "data"); int(40, pcm.size); pcm.copyInto(this, 44)
    }
}
