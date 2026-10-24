package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.robot.challenge.*
import kotlin.test.*

class ChallengeCountdownTest {
    @Test fun holdsEachDigitUntilItsSuccessorAppears() {
        assertTrue(countdownGlyphs(null).isEmpty())
        assertEquals(listOf(CountdownGlyph(ChallengeCountdownStep.THREE)), countdownGlyphs(0))
        assertEquals(countdownGlyphs(0), countdownGlyphs(999))
        assertEquals(listOf(ChallengeCountdownStep.THREE, ChallengeCountdownStep.TWO), countdownGlyphs(1000).map { it.step })
        assertEquals(listOf(CountdownGlyph(ChallengeCountdownStep.TWO)), countdownGlyphs(1999))
        assertEquals(listOf(CountdownGlyph(ChallengeCountdownStep.ONE)), countdownGlyphs(2999))
    }

    @Test fun everyOutgoingDigitMatchesTheSerializedCubicMidpointAndEnd() {
        for (onset in listOf(1000L, 2000L, 3000L)) {
            val first = countdownGlyphs(onset)
            assertEquals(2, first.size)
            assertEquals(1.5f, first.first().scale); assertEquals(1f, first.first().alpha)
            val midpoint = countdownGlyphs(onset + 125).first()
            assertEquals(1.75f, midpoint.scale); assertEquals(.5f, midpoint.alpha)
            assertEquals(listOf(first.last()), countdownGlyphs(onset + 250))
        }
    }

    @Test fun startHidesBeforeTheRoundUnlocksRatherThanAdvancingTheRound() {
        val countdown = ChallengeState(phase = ChallengePhase.COUNTDOWN, remainingMillis = 84)
        assertEquals(listOf(CountdownGlyph(ChallengeCountdownStep.START)), countdownGlyphs(countdown.countdownElapsedMillis))
        for (remaining in listOf(83L, 1L)) {
            val state = countdown.copy(remainingMillis = remaining)
            assertEquals(ChallengeCountdownStep.START, state.countdownStep)
            assertTrue(state.blocksControl)
            assertTrue(countdownGlyphs(state.countdownElapsedMillis).isEmpty())
        }
        assertTrue(countdownGlyphs(countdown.copy(phase = ChallengePhase.PLAYING).countdownElapsedMillis).isEmpty())
    }

    @Test fun skippedAndDisabledAnimationFramesUseTheCurrentCoreTime() {
        assertEquals(listOf(CountdownGlyph(ChallengeCountdownStep.ONE)), countdownGlyphs(2320))
        assertEquals(listOf(CountdownGlyph(ChallengeCountdownStep.START)), countdownGlyphs(3320))
        for (elapsed in listOf(1125L, 2125L, 3125L)) {
            val current = countdownGlyphs(elapsed).last()
            assertEquals(listOf(current), countdownGlyphs(elapsed, animatedExit = 1f))
            assertEquals(current, countdownGlyphs(elapsed, animatedExit = 0f).last())
        }
    }
}
