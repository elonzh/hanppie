package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.robot.challenge.*
import kotlin.test.*

class ChallengeSoundsTest {
    private val preparing = ChallengeState(phase = ChallengePhase.PREPARING,
        configuration = ChallengeConfiguration(ChallengeMode.TARGET_PRACTICE), roundId = 1)

    @Test fun countdownAndOutcomeCuesFollowTransitionsRatherThanTickerRecomposition() {
        val tracker = ChallengeCueTracker(preparing)
        val countdown = preparing.copy(phase = ChallengePhase.COUNTDOWN, remainingMillis = 3000)
        assertEquals(ChallengeCue.COUNTDOWN, tracker.update(countdown))
        assertNull(tracker.update(countdown.copy(remainingMillis = 2990)))
        assertEquals(ChallengeCue.COUNTDOWN, tracker.update(countdown.copy(remainingMillis = 2000)))
        val playing = preparing.copy(phase = ChallengePhase.PLAYING)
        assertEquals(ChallengeCue.START, tracker.update(playing))
        assertEquals(ChallengeCue.CLEAR, tracker.update(playing.copy(phase = ChallengePhase.WAVE_CLEAR)))
        val end = playing.copy(phase = ChallengePhase.STOPPING, end = ChallengeEnd.TIME_EXPIRED)
        assertEquals(ChallengeCue.FINISH, tracker.update(end))
        assertNull(tracker.update(end.copy(phase = ChallengePhase.RESULT)))
        assertNull(tracker.update(end.copy(phase = ChallengePhase.STOPPING)))
    }

    @Test fun aimChangesAndImpactIdentityAreIndependentOfScoreAndIgnoreOldCues() {
        val playing = preparing.copy(phase = ChallengePhase.PLAYING)
        val tracker = ChallengeCueTracker(playing)
        assertEquals(ChallengeCue.AIM, tracker.update(playing.copy(aimedMarkerId = 11)))
        assertNull(tracker.update(playing.copy(aimedMarkerId = 11)))
        assertNull(tracker.update(playing))
        val hit = playing.copy(hit = ChallengeHit(1, 20, null, false), hitAgeMillis = 0)
        assertEquals(ChallengeCue.HIT, tracker.update(hit)); assertNull(tracker.update(hit.copy(hitAgeMillis = 10)))
        assertNull(tracker.update(hit.copy(hit = hit.hit?.copy(sequence = 2), hitAgeMillis = 151)))
        assertNull(tracker.update(hit.copy(roundId = 2)))
    }

    @Test fun remountAndCancellationCannotReplayTheLastImpactOrAnEndTone() {
        val old = preparing.copy(phase = ChallengePhase.RESULT, end = ChallengeEnd.COMPLETED,
            hit = ChallengeHit(5, 11, null, true), hitAgeMillis = 0)
        val tracker = ChallengeCueTracker(old)
        assertNull(tracker.update(old))
        assertNull(tracker.update(old.copy(phase = ChallengePhase.STOPPING)))
        val live = preparing.copy(phase = ChallengePhase.PLAYING)
        assertNull(ChallengeCueTracker(live).update(live.copy(phase = ChallengePhase.STOPPING, end = ChallengeEnd.CANCELLED)))
    }

    @Test fun originalTonePcmAndWavHaveBoundedLevelsAndMatchingPortableHeaders() {
        for (cue in ChallengeCue.entries) {
            val pcm = challengePcm(cue); val wav = challengeWav(cue)
            assertTrue(pcm.size in 2..ChallengeSoundRate)
            assertEquals(0, pcm.size % 2); assertContentEquals(pcm, wav.copyOfRange(44, wav.size))
            assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
            assertEquals("WAVEfmt ", wav.copyOfRange(8, 16).decodeToString())
            fun int(offset: Int) = (0..3).fold(0) { bits, i -> bits or ((wav[offset + i].toInt() and 255) shl (8 * i)) }
            assertEquals(ChallengeSoundRate, int(24)); assertEquals(pcm.size, int(40))
            assertEquals(0, pcm[0].toInt()); assertEquals(0, pcm[pcm.lastIndex].toInt())
            for (index in pcm.indices step 2) {
                val sample = ((pcm[index].toInt() and 255) or (pcm[index + 1].toInt() shl 8))
                assertTrue(sample in -4000..4000)
            }
        }
    }
}
