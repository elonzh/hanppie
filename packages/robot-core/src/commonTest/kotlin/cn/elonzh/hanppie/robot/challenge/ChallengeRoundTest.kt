package cn.elonzh.hanppie.robot.challenge

import cn.elonzh.hanppie.robot.display.SightPoint
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.vision.*
import kotlin.test.*

class ChallengeRoundTest {
    private fun marker(id: Int, x: Float = .5f, y: Float = .5f, size: Float = .2f) = VisionRect(x, y, size, size, id)
    private class Rig {
        var now = 100L
        var stops = 0
        var vision = VisionState(phase = VisionPhase.ACTIVE, mode = VisionMode.MARKERS, mask = 32,
            report = VisionReport(5, 0, 0, emptyList()), reportAgeMillis = 0, enableMayBeActive = true)
        var environment = ChallengeEnvironment(ready = true, sight = SightPoint())
        val round = ChallengeRound({ now }, { stops++ }) { count -> (11..10 + count).toList() }
        val state get() = round.state.value
        fun advance(millis: Long) { now += millis; round.tick(true, vision, environment) }
        fun play(mode: ChallengeMode = ChallengeMode.TARGET_PRACTICE, firing: ChallengeFiring = ChallengeFiring.INFRARED, count: Int = 5) {
            round.prepare(ChallengeConfiguration(mode, firing, count)); round.tick(true, vision, environment)
            assertEquals(ChallengePhase.COUNTDOWN, state.phase)
            advance(3_917); assertEquals(ChallengePhase.PLAYING, state.phase)
        }
        fun rects(vararg values: VisionRect) { vision = vision.copy(rectangles = values.toList()) }
        fun fire(sight: SightPoint = SightPoint()) { round.fireStatus(vision, sight) }
        fun confirmStop() { vision = VisionState(); round.tick(true, vision, environment) }
    }

    @Test fun configurationMatchesTwoPublicModesWithoutInventingGelPractice() {
        for (count in 1..5) for (firing in ChallengeFiring.entries)
            ChallengeConfiguration(ChallengeMode.TARGET_RACE, firing, count)
        assertFailsWith<IllegalArgumentException> { ChallengeConfiguration(ChallengeMode.TARGET_RACE, markerCount = 0) }
        assertFailsWith<IllegalArgumentException> { ChallengeConfiguration(ChallengeMode.TARGET_RACE, markerCount = 6) }
        assertFailsWith<IllegalArgumentException> { ChallengeConfiguration(ChallengeMode.TARGET_PRACTICE, ChallengeFiring.GEL) }
        assertFailsWith<IllegalArgumentException> { ChallengeConfiguration(ChallengeMode.TARGET_PRACTICE, markerCount = 2) }
    }

    @Test fun fixedOfficialFifteenStagesKeepTheirDistinctTimeBudgets() {
        assertEquals(listOf(11,12,13,21,22,23,31,32,33,41,42,43,51,52,53), practiceStages.map { it.id })
        assertEquals(listOf(10,7,4,10,7,4,10,8,5,13,10,7,14,11,8), practiceStages.map { (it.timeMillis / 1000).toInt() })
        assertEquals(listOf(1,1,1,2,2,2,3,3,3,4,4,4,5,5,5), practiceStages.map { it.count })
    }

    @Test fun preparingWaitsForFreshMarkersAndCountdownCannotScore() {
        val r = Rig(); r.vision = r.vision.copy(phase = VisionPhase.STARTING, report = null, reportAgeMillis = null)
        r.round.prepare(ChallengeConfiguration(ChallengeMode.TARGET_PRACTICE)); r.advance(100)
        assertEquals(ChallengePhase.PREPARING, r.state.phase); assertTrue(r.state.blocksControl)
        r.vision = r.vision.copy(phase = VisionPhase.ACTIVE, report = VisionReport(5,0,0,emptyList()), reportAgeMillis = 0)
        r.advance(0); r.rects(marker(11)); r.fire()
        r.advance(3_916); assertEquals(0, r.state.score); assertEquals(1L, r.state.remainingMillis)
        r.advance(1); assertEquals(10_000L, r.state.remainingMillis); assertFalse(r.state.blocksControl)
        r.fire(); assertEquals(100, r.state.score)
    }

    @Test fun readyTimelineKeepsControlLockedThroughTheOfficialStartCardInBothModes() {
        for (mode in ChallengeMode.entries) {
            val r = Rig(); r.round.prepare(ChallengeConfiguration(mode)); r.advance(0)
            r.rects(marker(11))
            val boundaries = listOf(0L to null, 83L to null, 84L to ChallengeCountdownStep.THREE,
                1_083L to ChallengeCountdownStep.THREE, 1_084L to ChallengeCountdownStep.TWO,
                2_083L to ChallengeCountdownStep.TWO, 2_084L to ChallengeCountdownStep.ONE,
                3_083L to ChallengeCountdownStep.ONE, 3_084L to ChallengeCountdownStep.START,
                3_916L to ChallengeCountdownStep.START)
            var elapsed = 0L
            for ((at, step) in boundaries) {
                r.advance(at - elapsed); elapsed = at
                assertEquals(step, r.state.countdownStep, "$mode at $at ms")
                assertEquals((at - 84).takeIf { it >= 0 }, r.state.countdownElapsedMillis)
                assertTrue(r.state.blocksControl); r.fire()
                assertEquals(0, r.state.score); assertTrue(r.state.checkpoints.isEmpty())
                assertEquals(0L, r.state.elapsedMillis)
            }
            r.advance(1)
            assertEquals(ChallengePhase.PLAYING, r.state.phase); assertNull(r.state.countdownStep)
            assertNull(r.state.countdownElapsedMillis)
            assertFalse(r.state.blocksControl); assertEquals(0L, r.state.elapsedMillis)
            r.advance(100); r.fire()
            if (mode == ChallengeMode.TARGET_RACE) assertEquals(100L, r.state.checkpoints.single().elapsedMillis)
            else assertEquals(100, r.state.score)
        }
    }

    @Test fun readyTimelineCannotContinueAfterInputLossOrAnExpiredVisionReport() {
        for (inputLost in listOf(true, false)) {
            val r = Rig(); r.round.prepare(ChallengeConfiguration(ChallengeMode.TARGET_RACE)); r.advance(0)
            r.advance(3_084); assertEquals(ChallengeCountdownStep.START, r.state.countdownStep)
            if (!inputLost) r.vision = r.vision.copy(reportAgeMillis = 750)
            r.round.tick(!inputLost, r.vision, r.environment)
            assertEquals(if (inputLost) ChallengeEnd.INPUT_LOST else ChallengeEnd.VISION_FAILED, r.state.end)
            assertEquals(1, r.stops); assertTrue(r.state.blocksControl); assertNull(r.state.countdownStep)
            r.advance(5_000); assertEquals(ChallengePhase.STOPPING, r.state.phase)
        }
    }

    @Test fun practiceScoresOncePerTargetAndHasThreeWavesBeforeAStageChange() {
        val r = Rig(); r.play(); r.rects(marker(11))
        repeat(3) { index ->
            assertEquals(index + 1, r.state.wave)
            r.fire(); r.fire(); assertEquals((index + 1) * 100, r.state.score)
            assertEquals(ChallengePhase.WAVE_CLEAR, r.state.phase); assertFalse(r.state.blocksControl)
            r.advance(999); assertEquals(ChallengePhase.WAVE_CLEAR, r.state.phase)
            r.advance(1)
        }
        assertEquals(ChallengePhase.LEVEL_CHANGE, r.state.phase); assertEquals(12, r.state.stage)
        r.advance(2_999); assertEquals(ChallengePhase.LEVEL_CHANGE, r.state.phase)
        r.advance(1); assertEquals(1, r.state.wave); assertEquals(7_000L, r.state.remainingMillis)
        assertEquals(300, r.state.score); assertEquals(0, r.stops)
    }

    @Test fun finalStageRepeatsInsteadOfInventingAnOfficialVictoryAtLevelFiveThree() {
        val r = Rig(); r.play()
        repeat(16) { stage ->
            repeat(3) {
                val ids = r.state.targets.map { it.markerId }
                for (id in ids) { r.rects(marker(id)); r.fire() }
                r.advance(1_000)
            }
            assertEquals(ChallengePhase.LEVEL_CHANGE, r.state.phase)
            r.advance(3_000)
            assertEquals(practiceStages[minOf(stage + 1, 14)].id, r.state.stage)
        }
        assertEquals(53, r.state.stage); assertEquals(ChallengePhase.PLAYING, r.state.phase)
        assertNull(r.state.end)
    }

    @Test fun timeoutWinsAgainstAShotAtTheDeadlineAndWaitsForRecognitionCleanup() {
        val r = Rig(); r.play(); r.rects(marker(11)); r.now += 10_000; r.fire()
        assertEquals(0, r.state.score); assertEquals(ChallengeEnd.TIME_EXPIRED, r.state.end)
        assertEquals(ChallengePhase.STOPPING, r.state.phase); assertEquals(1, r.stops)
        r.confirmStop(); assertEquals(ChallengePhase.RESULT, r.state.phase); assertTrue(r.state.stopConfirmed)
        r.rects(marker(11)); r.fire(); assertEquals(0, r.state.score)
    }

    @Test fun raceAcceptsCheckpointsInHitOrderOnceAndKeepsTheFinalElapsedTime() {
        val r = Rig(); r.play(ChallengeMode.TARGET_RACE, count = 3)
        r.advance(400); r.rects(marker(13)); r.fire(); r.fire()
        r.advance(600); r.rects(marker(11)); r.fire()
        r.advance(500); r.rects(marker(12)); r.fire()
        assertEquals(listOf(13,11,12), r.state.checkpoints.map { it.markerId })
        assertEquals(listOf(400L,1000L,1500L), r.state.checkpoints.map { it.elapsedMillis })
        assertEquals(ChallengeEnd.COMPLETED, r.state.end); assertEquals(1, r.stops)
        r.advance(100); assertEquals(1500L, r.state.elapsedMillis)
        r.confirmStop(); assertTrue(r.state.stopConfirmed); assertTrue(r.state.blocksControl)
        r.round.dismissResult(); assertFalse(r.state.ownsRound)
    }

    @Test fun infraredUsesStrictBoundsCalibratedUpwardYAndTheFirstOccludingMarker() {
        val r = Rig(); r.play(ChallengeMode.TARGET_RACE, count = 2)
        r.rects(marker(11, .25f, .25f, .5f)); r.fire(SightPoint(0,7500))
        assertTrue(r.state.targets.none { it.completed }, "The official rectangle boundary is strict")
        r.rects(marker(20, .25f, .25f), marker(11, .25f, .25f))
        r.fire(SightPoint(2500,7500)); assertTrue(r.state.targets.none { it.completed })
        r.rects(marker((123 shl 16) or 11, .25f, .25f)); r.fire(SightPoint(2500,7500))
        assertTrue(r.state.targets.first().completed)
        r.rects(marker(12, .75f, .75f)); r.fire(SightPoint(7500,2500))
        assertEquals(ChallengeEnd.COMPLETED, r.state.end)
    }

    @Test fun staleOrWrongDetectorNotificationsNeverScore() {
        for (bad in listOf(
            VisionState(phase = VisionPhase.ACTIVE, mode = VisionMode.PEOPLE, report = VisionReport(1,0,0,emptyList()), reportAgeMillis = 0),
            VisionState(phase = VisionPhase.ACTIVE, mode = VisionMode.MARKERS, report = VisionReport(5,0,0,emptyList()), reportAgeMillis = 750),
            VisionState(phase = VisionPhase.STARTING, mode = VisionMode.MARKERS, report = VisionReport(5,0,0,emptyList()), reportAgeMillis = 0),
        )) {
            val r = Rig(); r.play(); r.round.fireStatus(bad.copy(rectangles = listOf(marker(11))), SightPoint())
            assertEquals(0, r.state.score)
        }
    }

    @Test fun gelUsesNearestMarkerAndRequiresPostDelayContinuousAbsence() {
        val r = Rig(); r.play(ChallengeMode.TARGET_RACE, ChallengeFiring.GEL, 2)
        r.rects(marker(11,.1f),marker(12,.6f)); r.fire()
        r.rects(marker(11)); r.advance(99); r.advance(1)
        r.advance(300); assertEquals(0, r.state.checkpoints.size)
        r.advance(1); assertEquals(12, r.state.checkpoints.single().markerId)
        assertEquals(ChallengePhase.PLAYING, r.state.phase)
    }

    @Test fun gelReappearanceRestartsAbsenceAndLateDisappearancesCannotScore() {
        val r = Rig(); r.play(ChallengeMode.TARGET_RACE, ChallengeFiring.GEL, 1)
        r.rects(marker(11)); r.fire(); r.advance(100)
        r.rects(); r.advance(100); r.advance(250)
        r.rects(marker(11)); r.advance(1)
        r.rects(); r.advance(100); r.advance(300); assertTrue(r.state.checkpoints.isEmpty())
        r.advance(1); assertEquals(ChallengeEnd.COMPLETED, r.state.end)
        val expired = Rig(); expired.play(ChallengeMode.TARGET_RACE, ChallengeFiring.GEL, 1)
        expired.rects(marker(11)); expired.fire(); expired.advance(2_500)
        expired.rects(); expired.advance(100); expired.advance(400)
        assertTrue(expired.state.checkpoints.isEmpty())
    }

    @Test fun gelDoesNotSkipANearerNonMissionMarkerOrCountOverlappingShotsTwice() {
        val r = Rig(); r.play(ChallengeMode.TARGET_RACE, ChallengeFiring.GEL, 2)
        r.rects(marker(20),marker(11,.1f)); r.fire(); r.rects(); r.advance(100); r.advance(301)
        assertTrue(r.state.checkpoints.isEmpty())
        r.rects(marker(11)); repeat(100) { r.fire() }; r.rects(); r.advance(100); r.advance(301)
        assertEquals(listOf(11), r.state.checkpoints.map { it.markerId })
    }

    @Test fun focusOrVisionLossStopsAndARecoveredReportDoesNotResumeTheRound() {
        for (operator in listOf(true,false)) {
            val r = Rig(); r.play(); r.rects(marker(11))
            if (operator) r.vision = r.vision.copy(reportAgeMillis = 750)
            r.round.tick(operator, r.vision, r.environment)
            assertEquals(ChallengePhase.STOPPING, r.state.phase)
            assertEquals(if (operator) ChallengeEnd.VISION_FAILED else ChallengeEnd.INPUT_LOST, r.state.end)
            r.vision = r.vision.copy(reportAgeMillis = 0); r.fire(); r.advance(10)
            assertEquals(0, r.state.score); assertEquals(ChallengePhase.STOPPING, r.state.phase)
            r.confirmStop(); assertEquals(ChallengePhase.RESULT, r.state.phase)
        }
    }

    @Test fun anUnconfirmedStopKeepsOwnershipAndExplicitRetryDoesNotChangeTheResult() {
        val r = Rig(); r.play(ChallengeMode.TARGET_RACE, count = 1); r.rects(marker(11)); r.fire()
        r.vision = r.vision.copy(phase = VisionPhase.ERROR, problem = VisionProblem.STOP_UNCONFIRMED)
        r.advance(0); assertEquals(ChallengePhase.RESULT, r.state.phase); assertFalse(r.state.stopConfirmed)
        assertTrue(r.state.blocksControl); assertTrue(r.state.ownsRound)
        assertFailsWith<IllegalStateException> { r.round.prepare(ChallengeConfiguration(ChallengeMode.TARGET_RACE)) }
        r.round.cancel(); assertEquals(2,r.stops); assertEquals(ChallengeEnd.COMPLETED,r.state.end)
        assertFailsWith<IllegalStateException> { r.round.dismissResult() }
        r.confirmStop(); assertTrue(r.state.stopConfirmed)
        r.round.prepare(ChallengeConfiguration(ChallengeMode.TARGET_RACE)); assertEquals(0,r.state.score)
    }

    @Test fun cancelledOrClosedRoundsCannotAcceptOldNotifications() {
        val r = Rig(); r.play(); r.rects(marker(11)); r.round.cancel(); r.fire()
        assertEquals(ChallengeEnd.CANCELLED,r.state.end); assertEquals(0,r.state.score)
        r.round.close(); r.fire(); r.advance(100); assertEquals(ChallengePhase.CLOSED,r.state.phase)
        assertFailsWith<IllegalStateException> { r.round.prepare(ChallengeConfiguration(ChallengeMode.TARGET_PRACTICE)) }
    }

    @Test fun leavingRetainsOwnershipUntilRecognitionIsConfirmedOffThenRetiresTheResult() {
        val r = Rig(); r.play(); r.round.leave()
        assertEquals(ChallengeEnd.CANCELLED, r.state.end)
        assertEquals(ChallengePhase.STOPPING, r.state.phase)
        r.vision = r.vision.copy(phase = VisionPhase.ERROR, problem = VisionProblem.STOP_UNCONFIRMED)
        r.advance(0); assertEquals(ChallengePhase.RESULT, r.state.phase)
        assertTrue(r.state.ownsRound); assertFalse(r.state.stopConfirmed)
        r.confirmStop(); assertEquals(ChallengePhase.IDLE, r.state.phase)
        assertFalse(r.state.ownsRound)

        val completed = Rig(); completed.play(ChallengeMode.TARGET_RACE, count = 1)
        completed.rects(marker(11)); completed.fire(); completed.confirmStop()
        assertEquals(ChallengeEnd.COMPLETED, completed.state.end)
        completed.round.leave(); assertEquals(ChallengePhase.IDLE, completed.state.phase)
    }

    @Test fun infraredTemporarilySkipsTheJustHitRectangleWhenTargetsOverlap() {
        val r = Rig(); r.play(ChallengeMode.TARGET_RACE, count = 3)
        r.rects(marker(11), marker(12), marker(13)); r.fire()
        assertEquals(listOf(11), r.state.checkpoints.map { it.markerId })
        r.advance(100); r.fire()
        assertEquals(listOf(11, 12), r.state.checkpoints.map { it.markerId })
        // Only the most recent hit is excluded by the fixed consumer; older occlusion remains.
        r.fire(); assertEquals(2, r.state.checkpoints.size)
        r.rects(marker(12), marker(13)); r.advance(500); r.fire()
        assertEquals(2, r.state.checkpoints.size)
        r.rects(marker(13)); r.fire(); assertEquals(ChallengeEnd.COMPLETED, r.state.end)
    }

    @Test fun nativeFireReportIsDistinctFromCommandSendAndValidatesTheEnvelope() {
        val frame = DussFrame(0,0x17,2,701,0,0x3f,0x50,"000048410300".hexBytes(),true)
        assertEquals(ChallengeFireReport(0x41480000,3,0),challengeFireReport(frame))
        assertEquals(ChallengeFireReport(0x41480000,3,0),challengeFireReport(frame.copy(payload = frame.payload + byteArrayOf(99))))
        for (bad in listOf(frame.copy(valid=false),frame.copy(sender=9),frame.copy(receiver=9),frame.copy(attr=0x80),
            frame.copy(set=0),frame.copy(id=0x51),frame.copy(payload=frame.payload.copyOf(5)))) assertNull(challengeFireReport(bad))
    }

    @Test fun infraredAimAndImpactIncludeNonScoringOccludersWithoutAwardingPoints() {
        val r = Rig(); r.play(ChallengeMode.TARGET_RACE, count = 2)
        r.rects(marker(20), marker(11)); r.advance(0)
        assertEquals(20, r.state.aimedMarkerId)
        r.fire()
        assertEquals(20, r.state.hit?.markerId); assertFalse(checkNotNull(r.state.hit).credited)
        assertEquals(0, r.state.hit?.scoreDelta); assertTrue(r.state.checkpoints.isEmpty())
        val first = checkNotNull(r.state.hit).sequence
        r.advance(1); assertEquals(11, r.state.aimedMarkerId)
        r.fire()
        assertEquals(listOf(11), r.state.checkpoints.map { it.markerId })
        assertTrue(checkNotNull(r.state.hit).credited); assertTrue(checkNotNull(r.state.hit).sequence > first)
        r.advance(500); assertEquals(20, r.state.aimedMarkerId)
        r.rects(marker(12, .9f)); r.advance(0); assertNull(r.state.aimedMarkerId)
    }

    @Test fun finalImpactSurvivesCleanupAndExpiresWithoutReplayingIntoANewRound() {
        val r = Rig(); r.play(ChallengeMode.TARGET_RACE, count = 1); r.rects(marker(11)); r.fire()
        assertEquals(ChallengePhase.STOPPING, r.state.phase)
        val hit = checkNotNull(r.state.hit); assertTrue(hit.credited); assertNull(r.state.aimedMarkerId)
        r.advance(400); assertEquals(hit, r.state.hit); assertEquals(400L, r.state.hitAgeMillis)
        r.confirmStop(); r.advance(600); assertNull(r.state.hit); assertNull(r.state.hitAgeMillis)
        r.vision = Rig().vision
        r.play(ChallengeMode.TARGET_RACE, count = 1); assertNull(r.state.hit)
        r.rects(marker(11)); r.fire(); assertTrue(checkNotNull(r.state.hit).sequence > hit.sequence)
    }

    @Test fun targetPracticeImpactAddsOneHundredButAnOldFrameCannotCreateFeedback() {
        val r = Rig(); r.play(); r.rects(marker(11)); r.fire()
        assertEquals(100, r.state.hit?.scoreDelta)
        r.advance(1_000); assertNull(r.state.hit)
        r.vision = r.vision.copy(reportAgeMillis = 750); r.fire(); assertNull(r.state.hit)
        r.round.cancel(); assertNull(r.state.aimedMarkerId)
    }
}
