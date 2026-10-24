package cn.elonzh.hanppie.robot.remote

import cn.elonzh.hanppie.robot.protocol.*
import kotlin.test.*

class GunCoolingTest {
    private class Rig {
        var now = 100L
        val sent = mutableListOf<ByteArray>()
        val cooling = GunCooling({ now }, { body -> sent.add(body.copyOf()); sent.size })
        val state get() = cooling.state.value
        fun heat(value: Int, frame: DussFrame? = null) {
            cooling.receive(frame ?: report(value))
        }
        fun ack(body: ByteArray = byteArrayOf(0), sequence: Int = sent.size) =
            cooling.receive(DussFrame(0, 9, 2, sequence, 0xc0, 0x3f, 0x5b, body, true))
        fun advance(millis: Long) { now += millis; cooling.tick() }
    }
    companion object {
        private fun report(value: Int) = DussFrame(0, 9, 2, 8, 0, 0x3f, 0x0e,
            ByteArray(15).apply { put16(12, value) }, true)
    }

    @Test fun heatUsesTheNativeUnsignedFieldAndRejectsUnrelatedOrShortReports() {
        val r = Rig(); val valid = report(1234)
        for (bad in listOf(valid.copy(valid = false), valid.copy(sender = 0x17), valid.copy(receiver = 9),
            valid.copy(attr = 0xc0), valid.copy(set = 0), valid.copy(id = 0x50)) +
            (0..14).map { valid.copy(payload = valid.payload.copyOf(it)) }) {
            r.heat(0, bad); assertNull(r.state.heatRaw)
        }
        for (value in listOf(0, 1, 1999, 2000, 2001, 65535)) {
            r.heat(value); assertEquals(value, r.state.heatRaw); assertTrue(r.state.fresh)
        }
    }

    @Test fun onlyFreshNonzeroHeatBelowTheOfficialCeilingCanStartCooling() {
        val r = Rig()
        assertFailsWith<IllegalStateException> { r.cooling.request() }
        for (value in listOf(0, 2000, 2001, 65535)) {
            r.heat(value); assertFalse(r.state.canCool)
            assertFailsWith<IllegalStateException> { r.cooling.request() }
        }
        r.heat(1999); r.advance(1500)
        assertFalse(r.state.fresh); assertEquals(1999, r.state.heatRaw)
        assertFailsWith<IllegalStateException> { r.cooling.request() }; assertTrue(r.sent.isEmpty())
    }

    @Test fun coolingAndResetUseDistinctNativeBytesAndWaitForTheirOwnAcknowledgements() {
        val r = Rig(); r.heat(500); r.cooling.request()
        assertContentEquals(byteArrayOf(0), r.sent.single()); assertTrue(r.state.ownsCooling)
        assertFailsWith<IllegalStateException> { r.cooling.request() }
        r.ack(); assertEquals(GunCoolingPhase.COOLING, r.state.phase)
        r.heat(100); assertEquals(1, r.sent.size)
        r.heat(0); assertContentEquals(byteArrayOf(1), r.sent.last())
        assertEquals(GunCoolingPhase.RESETTING, r.state.phase); assertTrue(r.state.ownsCooling)
        r.ack(sequence = 1); assertTrue(r.state.ownsCooling)
        r.ack(); assertEquals(GunCoolingPhase.IDLE, r.state.phase); assertFalse(r.state.ownsCooling)
        r.heat(0); assertEquals(2, r.sent.size, "Zero reports alone must not add startup resets")
    }

    @Test fun zeroAfterDispatchCanPrecedeTheAckButExpiredZeroCannotReset() {
        val r = Rig(); r.heat(100); r.cooling.request(); r.heat(0)
        assertEquals(1, r.sent.size); r.advance(1499); r.ack()
        assertEquals(2, r.sent.size)
        val stale = Rig(); stale.heat(100); stale.cooling.request(); stale.heat(0)
        stale.advance(1500); stale.ack()
        assertEquals(1, stale.sent.size); assertEquals(GunCoolingPhase.UNCONFIRMED, stale.state.phase)
        assertFalse(stale.state.canRetryReset)
        stale.heat(0); assertTrue(stale.state.canRetryReset); assertEquals(1, stale.sent.size)
    }

    @Test fun invalidAckAndTimeoutKeepOwnershipWithoutAutomaticRetries() {
        for (invalid in listOf(true, false)) {
            val r = Rig(); r.heat(400); r.cooling.request()
            if (invalid) r.ack(byteArrayOf()) else r.advance(1500)
            assertEquals(if (invalid) GunCoolingProblem.INVALID_RESPONSE else GunCoolingProblem.TIMED_OUT, r.state.problem)
            assertTrue(r.state.ownsCooling); r.advance(5000); assertEquals(1, r.sent.size)
            r.heat(0); r.cooling.request(); assertContentEquals(byteArrayOf(1), r.sent.last())
            r.ack(); assertFalse(r.state.ownsCooling)
        }
    }

    @Test fun rejectionOfCoolingAndResetHaveDifferentOwnershipConsequences() {
        val r = Rig(); r.heat(100); r.cooling.request(); r.ack(byteArrayOf(1))
        assertFalse(r.state.ownsCooling); assertEquals(GunCoolingProblem.REJECTED, r.state.problem)
        assertTrue(r.state.canCool); r.cooling.request(); r.ack(); r.heat(0); r.ack(byteArrayOf(1))
        assertTrue(r.state.ownsCooling); assertTrue(r.state.canRetryReset)
    }

    @Test fun wrongSourceAndOldAcksCannotAdvanceTheCoolingRequest() {
        val r = Rig(); r.heat(400); r.cooling.request()
        val valid = DussFrame(0, 9, 2, 1, 0xc0, 0x3f, 0x5b, byteArrayOf(0), true)
        for (bad in listOf(valid.copy(valid = false), valid.copy(sender = 0x17), valid.copy(receiver = 9),
            valid.copy(sequence = 2), valid.copy(attr = 0), valid.copy(set = 0), valid.copy(id = 0x0e))) {
            r.cooling.receive(bad); assertEquals(GunCoolingPhase.REQUESTING, r.state.phase)
        }
        r.ack(); assertEquals(GunCoolingPhase.COOLING, r.state.phase)
    }

    @Test fun ordinaryNonzeroToZeroResetsOnceWithoutRetiringHeldFireOrReplayingOnEntry() {
        val r = Rig(); r.heat(0); r.heat(0); assertTrue(r.sent.isEmpty())
        r.heat(2000); r.heat(500); r.heat(0)
        assertContentEquals(byteArrayOf(1), r.sent.single())
        assertFalse(r.state.ownsCooling, "Native ordinary idle reset does not emit the manual cooling input-stop event")
        r.heat(0); r.ack(); assertEquals(1,r.sent.size)
        r.heat(100); r.cooling.retire(); r.heat(0); assertEquals(1,r.sent.size)
        val rejected = Rig(); rejected.heat(100); rejected.cooling.request(); rejected.heat(0)
        rejected.ack(byteArrayOf(1)); assertEquals(2,rejected.sent.size)
        assertContentEquals(byteArrayOf(1),rejected.sent.last()); assertFalse(rejected.state.ownsCooling)
        val broken = GunCooling({ 100L }) { throw IllegalStateException("synthetic send failure") }
        broken.receive(report(100)); broken.receive(report(0))
        assertEquals(GunCoolingProblem.SEND_FAILED, broken.state.value.problem)
        assertTrue(broken.state.value.ownsCooling); assertTrue(broken.state.value.canRetryReset)
    }

    @Test fun retirementAndCloseDoNotReplayOrPretendToResetTheDevice() {
        val r = Rig(); r.heat(200); r.cooling.request(); r.cooling.retire(); r.ack()
        assertEquals(GunCoolingState(), r.state); assertEquals(1, r.sent.size)
        r.heat(0); assertFalse(r.state.ownsCooling); assertEquals(1, r.sent.size)
        r.cooling.close(); r.heat(100); r.ack(); assertEquals(GunCoolingState(), r.state)
        assertFailsWith<IllegalStateException> { r.cooling.request() }
        assertEquals(1, r.sent.size)
    }
}
