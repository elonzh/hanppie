package cn.elonzh.hanppie.robot.tracking

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.protocol.hexBytes
import kotlin.test.*

class ObjectTrackingTest {
    private data class Sent(val id: Int, val payload: ByteArray, val sequence: Int)
    private class Rig {
        var now = 100L
        var stops = 0
        val sent = mutableListOf<Sent>()
        val tracker = ObjectTracking({ id, payload ->
            val sequence = sent.size + 1
            sent += Sent(id, payload, sequence)
            sequence
        }, { now }, { stops++ })
        fun ack(command: Sent = sent.last(), result: Int = 0) = tracker.receive(
            DussFrame(0, 0xf1, 2, command.sequence, 0xc0, 0x0a, command.id, byteArrayOf(result.toByte()), true))
        fun search() { tracker.start(); ack(); ack(); tracker.receive(push(1, false)) }
        fun ready() { search(); tracker.select(.5f, .25f); ack(); tracker.receive(push(2)) }
        fun follow() { ready(); tracker.confirm(); ack(); tracker.receive(push(3)) }
    }

    @Test fun nativePacketLayoutsAndStrictDecoding() {
        // Native constructor byte layouts, not a physical-device capture.
        assertEquals("4000", TrackingProtocol.enable(true).hex())
        assertEquals("40000100000000", TrackingProtocol.confirm(true).hex())
        assertEquals("40000000000000", TrackingProtocol.confirm(false).hex())
        assertEquals("4000000000003f0000803e0000000000000000", TrackingProtocol.select(.5f, .25f).hex())
        val valid = push(2)
        assertEquals(TrackingRect(.5f, .25f, .2f, .4f), TrackingProtocol.push(valid)!!.rectangles.single())
        assertNull(TrackingProtocol.push(valid.copy(sender = 9)))
        assertNull(TrackingProtocol.push(valid.copy(attr = 0xc0)))
        assertNull(TrackingProtocol.push(valid.copy(valid = false)))
        assertNull(TrackingProtocol.push(valid.copy(payload = valid.payload.copyOf(10))))
        assertNull(TrackingProtocol.push(valid.copy(payload = valid.payload + byteArrayOf(0))))
        val nan = valid.payload.copyOf().apply { "0000c07f".hexBytes().copyInto(this, 9) }
        assertNull(TrackingProtocol.push(valid.copy(payload = nan)))
        assertFailsWith<IllegalArgumentException> { TrackingProtocol.select(Float.NaN, .5f) }
        assertFailsWith<IllegalArgumentException> { TrackingProtocol.select(1.01f, .5f) }
        val zero = valid.payload.copyOf().apply { ByteArray(16).copyInto(this, 9) }
        assertTrue(TrackingProtocol.push(valid.copy(payload = zero))!!.rectangles.isEmpty())
    }

    @Test fun selectionAndConfirmationRequireMatchingAckAndDevicePushInEitherOrder() {
        val rig = Rig()
        rig.tracker.start()
        assertEquals(TrackingPhase.STARTING, rig.tracker.state.value.phase)
        rig.ack(rig.sent.last().copy(sequence = 999))
        assertEquals(1, rig.sent.size)
        rig.ack(); rig.ack()
        rig.tracker.receive(push(1, false))
        assertEquals(TrackingPhase.SEARCHING, rig.tracker.state.value.phase)
        rig.tracker.select(.5f, .25f)
        rig.tracker.receive(push(2))
        assertEquals(TrackingPhase.SELECTING, rig.tracker.state.value.phase)
        rig.ack()
        assertEquals(TrackingPhase.TARGET_READY, rig.tracker.state.value.phase)
        rig.tracker.confirm()
        rig.tracker.receive(push(3))
        assertEquals(TrackingPhase.STARTING_FOLLOW, rig.tracker.state.value.phase)
        rig.ack()
        assertEquals(TrackingPhase.FOLLOWING, rig.tracker.state.value.phase)
        assertEquals(1, rig.stops, "Following must not run a competing actuator-zero ticker")
    }

    @Test fun stoppingRejectsLateRunningPushesAndOnlyReleasesControlAfterDisableAck() {
        val rig = Rig(); rig.ready(); rig.tracker.confirm()
        val oldConfirm = rig.sent.last()
        rig.tracker.stop()
        assertTrue(rig.tracker.state.value.blocksManualControl)
        rig.ack(oldConfirm); rig.tracker.receive(push(3))
        assertEquals(TrackingPhase.STOPPING, rig.tracker.state.value.phase)
        assertEquals("40000000000000", rig.sent[rig.sent.lastIndex - 1].payload.hex())
        rig.ack()
        assertEquals(TrackingPhase.OFF, rig.tracker.state.value.phase)
        rig.tracker.receive(push(3))
        assertFalse(rig.tracker.state.value.blocksManualControl)
    }

    @Test fun stopTimeoutKeepsControlBlockedAndCanBeRetried() {
        val rig = Rig(); rig.follow(); rig.tracker.stop()
        repeat(3) { rig.now += 501; rig.tracker.tick(true) }
        assertEquals(TrackingProblem.STOP_UNCONFIRMED, rig.tracker.state.value.problem)
        assertTrue(rig.tracker.state.value.blocksManualControl)
        assertFailsWith<IllegalStateException> { rig.tracker.start() }
        rig.tracker.stop(); rig.ack()
        assertEquals(TrackingPhase.OFF, rig.tracker.state.value.phase)
    }

    @Test fun expiredOperatorLeaseVisionTimeoutAndLostTargetAllCancelWithoutAutoResume() {
        for (reason in listOf("operator", "vision", "target")) {
            val rig = Rig(); rig.follow()
            when (reason) {
                "operator" -> rig.tracker.tick(false)
                "vision" -> { rig.now += 1_501; rig.tracker.tick(true) }
                else -> rig.tracker.receive(push(1, false))
            }
            assertEquals(TrackingPhase.STOPPING, rig.tracker.state.value.phase)
            rig.ack(); rig.tracker.receive(push(3))
            assertFalse(rig.tracker.state.value.active)
        }
    }

    @Test fun rejectedAndMissingResponsesStopAndSearchingCanContinueWhilePushesAreFresh() {
        val rejected = Rig(); rejected.tracker.start(); rejected.ack(result = 1); rejected.ack()
        assertEquals(TrackingProblem.REJECTED, rejected.tracker.state.value.problem)
        val timeout = Rig(); timeout.tracker.start(); timeout.now += 1_501; timeout.tracker.tick(true)
        assertEquals(TrackingPhase.STOPPING, timeout.tracker.state.value.phase)
        val searching = Rig(); searching.search()
        repeat(10) { searching.now += 500; searching.tracker.receive(push(1, false)); searching.tracker.tick(true) }
        assertEquals(TrackingPhase.SEARCHING, searching.tracker.state.value.phase)
    }

    companion object {
        private fun push(status: Int, target: Boolean = true): DussFrame {
            val payload = byteArrayOf(6, status.toByte(), 0, 0, 0, 0, 0, 0, if (target) 1 else 0) +
                if (target) listOf(.5f, .25f, .2f, .4f).flatMap { value ->
                    List(4) { (value.toBits() ushr (it * 8)).toByte() }
                }.toByteArray() + ByteArray(4) else byteArrayOf()
            return Protocol.frames(Protocol.duss(sender = 0xf1, receiver = 2, attr = 0, set = 0xa,
                id = 0xa4, payload = payload, sequence = 99)).single()
        }
    }
}
