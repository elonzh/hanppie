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
        fun search() { tracker.start(); ack(); ack(); tracker.receive(push(1, false)); tracker.receive(push(0, type = 1, info = 1)) }
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
        assertEquals(TrackingRect(.5f, .25f, .2f, .4f, 2), TrackingProtocol.push(valid)!!.rectangles.single())
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
        rig.tracker.receive(push(1, false)); rig.tracker.receive(push(0, type = 1, info = 1))
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

    @Test fun infoAndUnknownStatusKeepTheirExactWireValues() {
        val raw = 0x87654321.toInt()
        val decoded = TrackingProtocol.push(push(0xfe, info = raw, error = 0xfedc))!!
        assertEquals(raw, decoded.rectangles.single().info)
        assertEquals(TrackingTargetKind.UNKNOWN, decoded.rectangles.single().kind)
        assertNull(decoded.deviceStatus)
        assertEquals(0xfedc, decoded.error)
        for (status in TrackingDeviceStatus.entries) assertEquals(status, TrackingProtocol.push(push(status.raw))!!.deviceStatus)
        assertEquals(TrackingTargetKind.NONE, TrackingRect(.5f, .5f, .1f, .1f).kind)
    }

    @Test fun overlappingCandidatesAreFilteredAtTheOfficialHalfTrackedAreaThreshold() {
        val tracked = TrackingRect(.5f, .5f, .5f, .5f, 2)
        val duplicate = tracked.copy(info = 1)
        val half = tracked.copy(x = .75f, info = 1)
        val distinct = tracked.copy(x = .76f, info = 1)
        val disjoint = tracked.copy(x = 0f, y = 0f, info = 1)
        val state = TrackingState(TrackingPhase.TARGET_READY, listOf(tracked), listOf(duplicate, half, distinct, disjoint))
        assertEquals(listOf(tracked, distinct, disjoint), state.targets)
        assertNull(state.selectableAt(.5f, .5f))
        assertEquals(distinct, state.selectableAt(.76f, .5f))
        assertEquals(0f, tracked.intersection(disjoint))
        assertEquals(listOf(duplicate), state.copy(rectangles = emptyList(), candidates = listOf(duplicate)).targets)
    }

    @Test fun selectionRequiresAFreshActionableCandidateAndRejectsEmptySpace() {
        val rig = Rig(); rig.search()
        val mark = rig.sent.size
        assertFailsWith<IllegalStateException> { rig.tracker.select(.1f, .1f) }
        rig.tracker.receive(push(0, type = 1, info = 0))
        assertFailsWith<IllegalStateException> { rig.tracker.select(.5f, .25f) }
        rig.tracker.receive(push(0, type = 1, info = 99))
        assertFailsWith<IllegalStateException> { rig.tracker.select(.5f, .25f) }
        rig.tracker.receive(push(0, type = 1, info = 1))
        rig.now += 750
        assertFailsWith<IllegalStateException> { rig.tracker.select(.5f, .25f) }
        rig.tracker.tick(true)
        assertTrue(rig.tracker.state.value.candidates.isEmpty())
        assertEquals(mark, rig.sent.size)
    }

    @Test fun deviceErrorsPreserveSourceAndCodeThroughCleanupWithoutResuming() {
        for (type in listOf(1, 6)) {
            val rig = Rig(); rig.follow()
            rig.tracker.receive(push(3, type = type, info = 3, error = 0x1234))
            assertEquals(TrackingPhase.STOPPING, rig.tracker.state.value.phase)
            rig.ack()
            val failed = rig.tracker.state.value
            assertEquals(TrackingProblem.DEVICE_ERROR, failed.problem)
            assertEquals(type, failed.report!!.type)
            assertEquals(0x1234, failed.report.error)
            assertTrue(failed.targets.isEmpty())
            rig.tracker.receive(push(3)); assertEquals(failed, rig.tracker.state.value)
            rig.search(); assertNull(rig.tracker.state.value.responseCode)
            assertEquals(0, rig.tracker.state.value.report!!.error)
        }
    }

    @Test fun unknownReportAndMismatchedInfoCannotUnlockConfirmationOrFollowing() {
        for ((status, info) in listOf(255 to 3, 2 to 0, 2 to -1, 3 to 2)) {
            val rig = Rig(); rig.search(); rig.tracker.select(.5f, .25f); rig.ack()
            rig.tracker.receive(push(status, info = info))
            assertEquals(TrackingPhase.STOPPING, rig.tracker.state.value.phase)
            assertFailsWith<IllegalStateException> { rig.tracker.confirm() }
            rig.ack()
            assertEquals(TrackingProblem.UNSUPPORTED_REPORT, rig.tracker.state.value.problem)
            assertEquals(status, rig.tracker.state.value.report!!.status)
            assertEquals(info, rig.tracker.state.value.report!!.rectangles.single().info)
        }
    }

    @Test fun commandRejectionKeepsTheUnsignedResultAndItsCommand() {
        val rig = Rig(); rig.ready(); rig.tracker.confirm(); rig.ack(result = 0xe0); rig.ack()
        assertEquals(TrackingProblem.REJECTED, rig.tracker.state.value.problem)
        assertEquals(0xe0, rig.tracker.state.value.responseCode)
        assertEquals(TrackingProtocol.CONFIRM, rig.tracker.state.value.rejectedCommand)
        rig.search(); assertNull(rig.tracker.state.value.responseCode)
    }

    companion object {
        private fun push(status: Int, target: Boolean = true, type: Int = 6, info: Int = if (status == 2) 2 else if (status == 3) 3 else 0,
            error: Int = 0): DussFrame {
            val payload = byteArrayOf(type.toByte(), status.toByte(), 0, 0, 0, 0, error.toByte(), (error ushr 8).toByte(), if (target) 1 else 0) +
                if (target) listOf(.5f, .25f, .2f, .4f).flatMap { value ->
                    List(4) { (value.toBits() ushr (it * 8)).toByte() }
                }.toByteArray() + ByteArray(4) { (info ushr (8 * it)).toByte() } else byteArrayOf()
            return Protocol.frames(Protocol.duss(sender = 0xf1, receiver = 2, attr = 0, set = 0xa,
                id = 0xa4, payload = payload, sequence = 99)).single()
        }
    }
}
