package cn.elonzh.hanppie.robot.challenge

import cn.elonzh.hanppie.robot.display.SightPoint
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.remote.remoteEffects
import kotlin.test.*

class ChallengeSetupTest {
    private val baseline = remoteEffects.first { it.set == 0x3f && it.id == 9 }.payload.hexBytes()
    private data class Sent(val id: Int, val body: ByteArray, val sequence: Int)
    private inner class Rig {
        var now = 100L
        val sent = mutableListOf<Sent>()
        val setup = ChallengeSetup(baseline, { id, body -> (sent.size + 1).also { sent += Sent(id, body.copyOf(), it) } }, { now })
        fun reply(body: ByteArray = byteArrayOf(0), frame: DussFrame? = null) {
            val request = sent.last()
            setup.receive(frame ?: DussFrame(0, 9, 2, request.sequence, 0xc0, 0x3f, request.id, body, true))
        }
        fun prepare() {
            setup.prepare(ChallengeConfiguration(ChallengeMode.TARGET_RACE, ChallengeFiring.GEL, 3))
            reply("00881388134c1d4c1d".hexBytes())
        }
        fun advance(millis: Long) { now += millis; setup.tick() }
    }

    @Test fun challengeChangesOnlyFourRulesInThePacketTestedListAndKeepsItsTail() {
        val gel = challengeRules(baseline, ChallengeFiring.GEL)
        assertEquals(baseline.size, gel.size)
        assertEquals(0, gel.u8(16)) // Gun, captured second key (1002).
        assertEquals(200, gel.u8(56)) // OverheatIncrease, captured seventh key (1008).
        assertEquals(0, gel.u8(96)) // BloodCalc, captured twelfth key (1032).
        val changed = baseline.indices.filter { gel[it] != baseline[it] }
        assertEquals(listOf(16, 56, 96), changed)
        assertContentEquals(baseline.copyOfRange(108, baseline.size), gel.copyOfRange(108, gel.size))
        val infrared = challengeRules(baseline, ChallengeFiring.INFRARED)
        assertEquals(listOf(56, 96), baseline.indices.filter { infrared[it] != baseline[it] })
        assertFailsWith<IllegalArgumentException> { challengeRules(byteArrayOf(), ChallengeFiring.GEL) }
        assertFailsWith<IllegalArgumentException> { challengeRules(byteArrayOf(1, 0, 0, 0, 0), ChallengeFiring.GEL) }
    }

    @Test fun setupRequiresFreshCalibratedSightAndRulesAckBeforeItBecomesReady() {
        val r = Rig(); r.prepare()
        assertEquals(listOf(0x57, 0x09), r.sent.map { it.id })
        assertFalse(r.setup.environment.ready)
        assertEquals(SightPoint(7500, 7500), r.setup.environment.sight)
        assertFalse(r.setup.environment.cleanupConfirmed)
        r.reply(); assertTrue(r.setup.environment.ready)
        r.setup.stop(); assertContentEquals(baseline, r.sent.last().body)
        assertFalse(r.setup.environment.cleanupConfirmed)
        r.reply(); assertTrue(r.setup.environment.cleanupConfirmed)
        assertFalse(r.setup.environment.ready)
    }

    @Test fun setupRejectsUnrelatedEnvelopesAndInvalidCalibrationWithoutWritingRules() {
        val r = Rig(); r.setup.prepare(ChallengeConfiguration(ChallengeMode.TARGET_PRACTICE))
        val valid = DussFrame(0, 9, 2, 1, 0xc0, 0x3f, 0x57, "00881388134c1d4c1d".hexBytes(), true)
        for (bad in listOf(valid.copy(valid = false), valid.copy(sender = 0x17), valid.copy(receiver = 9),
            valid.copy(sequence = 2), valid.copy(attr = 0), valid.copy(set = 0), valid.copy(id = 9), valid.copy(payload = byteArrayOf()))) {
            r.reply(frame = bad); assertEquals(1, r.sent.size)
        }
        r.reply("008813881311278813".hexBytes()) // x = 10001.
        assertEquals(ChallengeProblem.CALIBRATION_UNAVAILABLE, r.setup.environment.problem)
        r.setup.stop(); assertTrue(r.setup.environment.cleanupConfirmed); assertEquals(1, r.sent.size)
    }

    @Test fun missingApplyAckStillRequiresRestorationAndStaleAckCannotConfirmIt() {
        val r = Rig(); r.prepare(); val applySequence = r.sent.last().sequence
        r.advance(1_500); assertEquals(ChallengeProblem.RULES_TIMED_OUT, r.setup.environment.problem)
        r.setup.stop(); assertEquals(3, r.sent.size)
        r.reply(frame = DussFrame(0, 9, 2, applySequence, 0xc0, 0x3f, 9, byteArrayOf(0), true))
        assertFalse(r.setup.environment.cleanupConfirmed)
        r.reply(); assertTrue(r.setup.environment.cleanupConfirmed)
    }

    @Test fun rejectedApplyRestoresAndThreeMissingRestoreAcksKeepOwnershipForExplicitRetry() {
        val r = Rig(); r.prepare(); r.reply(byteArrayOf(7))
        assertEquals(ChallengeProblem.RULES_REJECTED, r.setup.environment.problem)
        r.setup.stop()
        repeat(3) { r.advance(500) }
        assertEquals(5, r.sent.size)
        assertEquals(ChallengeProblem.RESTORE_UNCONFIRMED, r.setup.environment.problem)
        assertFalse(r.setup.environment.cleanupConfirmed)
        r.setup.stop(); assertEquals(6, r.sent.size)
        r.reply(); assertTrue(r.setup.environment.cleanupConfirmed); assertNull(r.setup.environment.problem)
        val count = r.sent.size
        r.setup.close(); r.advance(10_000); assertEquals(count, r.sent.size)
    }
}
