package cn.elonzh.hanppie.robot.vision

import cn.elonzh.hanppie.robot.protocol.*
import kotlin.test.*

class VisionControlTest {
    private data class Sent(val id: Int, val payload: ByteArray, val sequence: Int, val receiver: Int)
    private class Rig {
        var now = 100L
        var zeros = 0
        var sendFails = false
        val sent = mutableListOf<Sent>()
        val control = VisionControl({ receiver, id, payload ->
            if (sendFails) error("transport closed")
            val sequence = sent.size + 1
            sent += Sent(id, payload, sequence, receiver)
            sequence
        }, { now }, { zeros++ })
        fun response(command: Sent = sent.last(), code: Int = 0, mask: Int? = null) =
            DussFrame(0, command.receiver, 2, command.sequence, 0xc0, 0x0a, command.id,
                byteArrayOf(code.toByte()) + (mask?.let(VisionProtocol::enable) ?: byteArrayOf()), true)
        fun ack(code: Int = 0, mask: Int? = null) = control.receive(response(code = code, mask = mask))
        fun active(mode: VisionMode = VisionMode.ROBOT_AIM) {
            control.start(mode); ack(mask = 0); ack(); ack(); ack(mask = mode.mask)
            assertEquals(VisionPhase.ACTIVE, control.state.value.phase)
        }
        fun stopped() { ack(); ack(mask = 0) }
    }

    private fun report(type: Int = 3, status: Int = 3, info: Int = 5, error: Int = 0, target: Boolean = true): DussFrame {
        val rect = listOf(.5f, .25f, .2f, .4f).flatMap { value -> List(4) { (value.toBits() ushr (8 * it)).toByte() } }.toByteArray()
        val bits = ByteArray(4) { (info ushr (it * 8)).toByte() }
        return DussFrame(0, 0xf1, 2, 800, 0, 0x0a, 0xa4,
            byteArrayOf(type.toByte(), status.toByte(), 0, 0, 0, 0, error.toByte(), (error ushr 8).toByte(), if (target) 1 else 0) +
                if (target) rect + bits else byteArrayOf(), true)
    }

    @Test fun nativeMasksAndReportDomainsAreDistinctAndStrict() {
        assertEquals("0800", VisionProtocol.enable(VisionMode.ROBOT_AIM.mask).hex())
        assertEquals("2000", VisionProtocol.enable(VisionMode.MARKERS.mask).hex())
        assertEquals(0x128, VisionProtocol.enabled("002801".hexBytes()))
        assertNull(VisionProtocol.enabled("0028".hexBytes()))
        assertNull(VisionProtocol.enabled("012800".hexBytes()))
        assertNull(VisionProtocol.enabled("00280000".hexBytes()))
        val marker = report(type = 5, info = 0x803a002d.toInt())
        assertEquals(0x803a002d.toInt(), VisionProtocol.push(marker)!!.rectangles.single().info)
        val decoded = VisionProtocol.push(marker)!!.rectangles.single()
        assertEquals(45, decoded.markerId); assertEquals(0x803a, decoded.markerDistanceCentimetres)
        assertEquals("Z", markerSymbol(decoded.markerId)); assertEquals("0", markerSymbol(10))
        assertNull(markerSymbol(9)); assertNull(markerSymbol(46))
        val aim = report()
        for (bad in listOf(aim.copy(sender = 9), aim.copy(receiver = 9), aim.copy(attr = 0xc0),
            aim.copy(valid = false), aim.copy(id = 0x90), aim.copy(payload = aim.payload.copyOf(9)),
            aim.copy(payload = aim.payload + byteArrayOf(0)), report(type = 8), report(type = 7))) assertNull(VisionProtocol.push(bad))
        val invalid = aim.payload.copyOf().apply { "0000c07f".hexBytes().copyInto(this, 9) }
        assertNull(VisionProtocol.push(aim.copy(payload = invalid)))
        assertNull(cn.elonzh.hanppie.robot.tracking.TrackingProtocol.push(aim))
    }

    @Test fun inspectNeverResetsAnotherOwnerAndCancelDiscardsLateRead() {
        val rig = Rig(); rig.control.start(VisionMode.ROBOT_AIM); rig.ack(mask = 64)
        assertEquals(VisionProblem.RESOURCE_BUSY, rig.control.state.value.problem)
        assertEquals(listOf(0xa5), rig.sent.map { it.id })
        assertFalse(rig.control.state.value.ownsPipeline)
        rig.control.stop(); assertEquals(1, rig.sent.size)
        rig.control.start(VisionMode.MARKERS)
        val late = rig.response(mask = 0)
        rig.control.stop(); rig.control.receive(late)
        assertEquals(VisionPhase.OFF, rig.control.state.value.phase)
        assertEquals(listOf(0xa5, 0xa5), rig.sent.map { it.id })
    }

    @Test fun enableRequiresCorrelatedAckAndMatchingReadbackAndStopRequiresReadback() {
        val rig = Rig(); rig.control.start(VisionMode.ROBOT_AIM)
        val inspect = rig.response(mask = 0)
        for (bad in listOf(inspect.copy(sender = 9), inspect.copy(receiver = 9), inspect.copy(sequence = 999),
            inspect.copy(attr = 0x80), inspect.copy(set = 0x3f), inspect.copy(valid = false))) rig.control.receive(bad)
        assertEquals(1, rig.sent.size)
        rig.control.receive(inspect); rig.ack()
        assertTrue(rig.control.state.value.ownsGimbal)
        rig.control.receive(report())
        assertEquals(VisionPhase.STARTING, rig.control.state.value.phase)
        rig.ack()
        assertEquals(0xa5, rig.sent.last().id)
        assertEquals(VisionPhase.STARTING, rig.control.state.value.phase)
        rig.ack(mask = 8)
        assertEquals(VisionPhase.ACTIVE, rig.control.state.value.phase)
        rig.control.stop(); rig.ack()
        assertTrue(rig.control.state.value.ownsGimbal, "Detection-off ACK alone cannot release control")
        rig.ack(mask = 0)
        assertEquals(VisionPhase.OFF, rig.control.state.value.phase)
        assertFalse(rig.control.state.value.ownsPipeline)
    }

    @Test fun wrongOrMalformedModeReadbackStopsWithoutPretendingActivation() {
        for (mask in listOf(32, null)) {
            val rig = Rig(); rig.control.start(VisionMode.ROBOT_AIM); rig.ack(mask = 0); rig.ack(); rig.ack(); rig.ack(mask = mask)
            assertEquals(VisionPhase.STOPPING, rig.control.state.value.phase)
            assertEquals("0000", rig.sent.last().payload.hex())
            rig.stopped()
            assertEquals(if (mask == null) VisionProblem.UNSUPPORTED_REPORT else VisionProblem.MODE_MISMATCH, rig.control.state.value.problem)
            assertFalse(rig.control.state.value.ownsGimbal)
        }
    }

    @Test fun rejectionReportsCommandAndCodeSeparatelyFromDeviceError() {
        val rig = Rig(); rig.control.start(VisionMode.ROBOT_AIM); rig.ack(mask = 0); rig.ack(); rig.ack(code = 0xe0)
        assertEquals(VisionPhase.STOPPING, rig.control.state.value.phase)
        rig.stopped()
        assertEquals(VisionProblem.REJECTED, rig.control.state.value.problem)
        assertEquals(0xe0, rig.control.state.value.responseCode)
        assertEquals(0xa3, rig.control.state.value.rejectedCommand)
        assertNull(rig.control.state.value.report)
        rig.active(); rig.control.receive(report(error = 0x1234)); rig.stopped()
        assertEquals(VisionProblem.DEVICE_ERROR, rig.control.state.value.problem)
        assertEquals(0x1234, rig.control.state.value.report?.error)
        assertNull(rig.control.state.value.responseCode)
    }

    @Test fun stopUnconfirmedKeepsOwnershipAndExplicitRetryDoesNotRestartOldMode() {
        val rig = Rig(); rig.active(); rig.control.stop()
        repeat(3) { rig.now += 501; rig.control.tick(true) }
        assertEquals(VisionProblem.STOP_UNCONFIRMED, rig.control.state.value.problem)
        assertTrue(rig.control.state.value.ownsGimbal)
        assertFailsWith<IllegalStateException> { rig.control.start(VisionMode.MARKERS) }
        val prior = rig.sent.size
        rig.control.stop(); rig.stopped(); rig.control.receive(report())
        assertEquals(VisionPhase.OFF, rig.control.state.value.phase)
        assertEquals(listOf(0xa3, 0xa5), rig.sent.drop(prior).map { it.id })
        assertEquals("0000", rig.sent[prior].payload.hex())
    }

    @Test fun nonzeroStopReadbackOrRejectedStopDoesNotReleaseActuators() {
        for (reject in listOf(true, false)) {
            val rig = Rig(); rig.active(); rig.control.stop()
            if (reject) rig.ack(code = 2) else { rig.ack(); rig.ack(mask = 8) }
            assertEquals(VisionProblem.STOP_UNCONFIRMED, rig.control.state.value.problem)
            assertTrue(rig.control.state.value.ownsPipeline)
            assertTrue(rig.control.state.value.blocksManualControl)
        }
    }

    @Test fun onlyMatchingDetectorProducesFreshRectanglesAndStaleFramesDisappear() {
        val rig = Rig(); rig.active(VisionMode.MARKERS)
        assertFalse(rig.control.state.value.ownsGimbal)
        rig.control.receive(report()); assertNull(rig.control.state.value.report)
        rig.control.receive(report(type = 5, info = 0x0064002d))
        assertEquals(0x0064002d, rig.control.state.value.rectangles.single().info)
        assertEquals(0, rig.zeros)
        rig.now += 751; rig.control.tick(true)
        assertTrue(rig.control.state.value.rectangles.isEmpty())
        assertEquals(751L, rig.control.state.value.reportAgeMillis)
        rig.now += 750; rig.control.tick(true)
        assertEquals(VisionPhase.STOPPING, rig.control.state.value.phase)
        rig.stopped(); assertEquals(VisionProblem.TIMED_OUT, rig.control.state.value.problem)
    }

    @Test fun operatorLossUnknownStatusUnknownAimInfoAndTargetLossAllStopWithoutResume() {
        for (event in listOf("operator", "status", "info", "target")) {
            val rig = Rig(); rig.active(); rig.control.receive(report())
            when (event) {
                "operator" -> rig.control.tick(false)
                "status" -> rig.control.receive(report(status = 99))
                "info" -> rig.control.receive(report(info = 99))
                else -> rig.control.receive(report(target = false))
            }
            assertEquals(VisionPhase.STOPPING, rig.control.state.value.phase)
            rig.stopped()
            val count = rig.sent.size
            rig.control.receive(report()); rig.control.tick(true)
            assertFalse(rig.control.state.value.ownsGimbal)
            assertEquals(count, rig.sent.size)
        }
    }

    @Test fun missingInitialReportOrEnableAckTimesOutAndTransportFailureRetainsUncertainOwnership() {
        val rig = Rig(); rig.active(); rig.now += 3_001; rig.control.tick(true)
        assertEquals(VisionPhase.STOPPING, rig.control.state.value.phase)
        rig.stopped()
        rig.control.start(VisionMode.ROBOT_AIM); rig.ack(mask = 0); rig.ack()
        rig.now += 1_501; rig.control.tick(true)
        assertEquals(VisionPhase.STOPPING, rig.control.state.value.phase)
        rig.stopped()
        rig.control.start(VisionMode.ROBOT_AIM); rig.ack(mask = 0)
        rig.sendFails = true; rig.ack()
        assertEquals(VisionProblem.STOP_UNCONFIRMED, rig.control.state.value.problem)
        assertTrue(rig.control.state.value.ownsGimbal)
    }

    @Test fun closeSendsOnlyBestEffortCleanupAndNeverProcessesLateFramesOrRestarts() {
        val rig = Rig(); rig.active()
        rig.control.close()
        val count = rig.sent.size
        assertEquals("0000", rig.sent.last().payload.hex())
        rig.ack(); rig.control.receive(report()); rig.control.tick(true); rig.control.close()
        assertEquals(count, rig.sent.size)
        assertEquals(VisionPhase.CLOSED, rig.control.state.value.phase)
        assertFailsWith<IllegalStateException> { rig.control.start(VisionMode.MARKERS) }
        val loader = Rig(); loader.active(); val prior = loader.sent.size
        loader.control.close(sendCleanup = false); assertEquals(prior, loader.sent.size)
    }

    private fun lineReport(theta: Float = -35f, curvature: Float = 6f, count: Int = 10, info: Int = 2): DussFrame {
        val record = listOf(.4f, .8f, theta, curvature).flatMap { v -> List(4) { (v.toBits() ushr (8 * it)).toByte() } }.toByteArray() +
            ByteArray(4) { (info ushr (8 * it)).toByte() }
        return report(type = 4, target = false).let { it.copy(payload = it.payload.copyOf().apply { this[8] = count.toByte() } +
            List(count) { record }.fold(byteArrayOf()) { bytes, item -> bytes + item }) }
    }

    @Test fun passiveDetectorVectorsKeepPoseInfoAndLineTangentCurvatureOutsideRectangleRules() {
        for ((mode, mask, type) in listOf(Triple(VisionMode.HEAD_SHOULDERS, 1, 0), Triple(VisionMode.PEOPLE, 2, 1),
            Triple(VisionMode.POSE, 4, 2), Triple(VisionMode.LINES, 16, 4))) {
            assertEquals(mask, mode.mask); assertEquals(type, mode.reportType)
        }
        val pose = VisionProtocol.push(report(type = 2, info = 0x12340006))!!.rectangles.single()
        assertEquals(6, pose.poseId); assertEquals(0x12340006, pose.info)
        val line = VisionProtocol.push(lineReport())!!
        assertTrue(line.rectangles.isEmpty()); assertEquals(10, line.linePoints.size)
        assertEquals(VisionLinePoint(.4f, .8f, -35f, 6f, 2), line.linePoints.first())
        assertEquals(20, VisionProtocol.push(lineReport(count = 20))!!.linePoints.size)
        assertTrue(VisionProtocol.push(lineReport(count = 0))!!.linePoints.isEmpty())
        for (bad in listOf(lineReport(theta = Float.NaN), lineReport(curvature = Float.POSITIVE_INFINITY),
            lineReport(curvature = -1f), lineReport(curvature = 11f))) assertNull(VisionProtocol.push(bad))
        // A line point is not a target box: zero curvature and coordinates on the edge are valid.
        assertNotNull(VisionProtocol.push(lineReport(theta = 180f, curvature = 0f)))
        assertEquals("0102", VisionProtocol.color(VisionMode.LINES, VisionColor.GREEN).hex())
        assertEquals("0203", VisionProtocol.color(VisionMode.MARKERS, VisionColor.BLUE).hex())
    }

    @Test fun colourUsesItsOwnReceiverAndAcceptedColourNeverReplacesMaskReadback() {
        for (mode in listOf(VisionMode.LINES, VisionMode.MARKERS)) {
            val rig = Rig(); rig.control.start(mode, VisionColor.RED); rig.ack(mask = 0); rig.ack()
            assertEquals(9, rig.sent.last().receiver); assertEquals(0xab, rig.sent.last().id)
            assertContentEquals(VisionProtocol.color(mode, VisionColor.RED), rig.sent.last().payload)
            assertFalse(rig.control.state.value.colorAccepted)
            val ack = rig.response()
            for (bad in listOf(ack.copy(sender = 0xf1), ack.copy(sequence = 999), ack.copy(attr = 0), ack.copy(receiver = 9))) rig.control.receive(bad)
            assertEquals(3, rig.sent.size)
            rig.control.receive(ack)
            assertTrue(rig.control.state.value.colorAccepted)
            assertEquals(0xf1, rig.sent.last().receiver); assertEquals(if (mode == VisionMode.LINES) "1000" else "2000", rig.sent.last().payload.hex())
            rig.ack(); assertEquals(VisionPhase.STARTING, rig.control.state.value.phase)
            rig.ack(mask = mode.mask); assertEquals(VisionPhase.ACTIVE, rig.control.state.value.phase)
            assertEquals(VisionColor.RED, rig.control.state.value.color)
            rig.control.stop(); rig.stopped(); assertEquals(VisionPhase.OFF, rig.control.state.value.phase)
        }
    }

    @Test fun colourRejectionTimeoutOrCancelDoesNotEnableAndCannotClaimItRestoredPriorColour() {
        for (event in listOf("reject", "timeout", "cancel", "close")) {
            val rig = Rig(); rig.control.start(VisionMode.LINES, VisionColor.BLUE); rig.ack(mask = 0); rig.ack()
            val late = rig.response()
            when (event) {
                "reject" -> rig.ack(code = 4)
                "timeout" -> { rig.now += 1_501; rig.control.tick(true) }
                "cancel" -> rig.control.stop()
                else -> rig.control.close()
            }
            val sent = rig.sent.size
            rig.control.receive(late); rig.control.tick(true)
            assertEquals(sent, rig.sent.size)
            assertFalse(rig.control.state.value.ownsPipeline)
            assertFalse(rig.control.state.value.colorAccepted)
            assertFalse(rig.sent.any { it.id == 0xa3 && it.payload.contentEquals(VisionProtocol.enable(16)) })
        }
        val busy = Rig(); busy.control.start(VisionMode.LINES, VisionColor.BLUE); busy.ack(mask = 64)
        assertEquals(1, busy.sent.size, "Foreign ownership must also prevent colour writes")
        assertFailsWith<IllegalArgumentException> { Rig().control.start(VisionMode.PEOPLE, VisionColor.RED) }
    }

    @Test fun allPassiveModesLeaveGimbalManualAndEmptyReportsAreFreshWithoutRestartingMotion() {
        for (mode in listOf(VisionMode.HEAD_SHOULDERS, VisionMode.PEOPLE, VisionMode.POSE, VisionMode.LINES)) {
            val rig = Rig(); rig.active(mode)
            rig.control.receive(if (mode == VisionMode.LINES) lineReport() else report(type = mode.reportType, info = 6))
            assertFalse(rig.control.state.value.ownsGimbal); assertEquals(0, rig.zeros)
            assertEquals(if (mode == VisionMode.LINES) 10 else 0, rig.control.state.value.linePoints.size)
            rig.now += 751; rig.control.tick(true)
            assertTrue(rig.control.state.value.linePoints.isEmpty()); assertTrue(rig.control.state.value.rectangles.isEmpty())
            rig.control.receive(report(type = mode.reportType, target = false))
            assertEquals(VisionPhase.ACTIVE, rig.control.state.value.phase)
            rig.control.tick(false); rig.stopped()
            assertEquals(VisionPhase.OFF, rig.control.state.value.phase)
            val stopped = rig.control.state.value
            rig.now += 2_000; rig.control.tick(true)
            assertEquals(stopped, rig.control.state.value, "Completed detections must not keep publishing an old report age")
        }
    }

    @Test fun lineReportsKeepRawDiagnosticsButStoppingClearsEveryVisibleResult() {
        val rig = Rig(); rig.active(VisionMode.LINES)
        rig.control.receive(lineReport(info = 0x12340003))
        assertEquals(0x12340003, rig.control.state.value.linePoints.first().info)
        rig.control.receive(report(type = 2)); assertEquals(4, rig.control.state.value.report!!.type)
        val broken = lineReport().let { frame -> frame.copy(payload = frame.payload.copyOf().apply { this[6] = 5 }) }
        rig.control.receive(broken)
        assertEquals(VisionPhase.STOPPING, rig.control.state.value.phase)
        assertTrue(rig.control.state.value.linePoints.isEmpty())
        rig.stopped(); assertEquals(5, rig.control.state.value.report!!.error)
        assertEquals(VisionProblem.DEVICE_ERROR, rig.control.state.value.problem)
        rig.control.stop()
        val dismissed = rig.control.state.value
        rig.now += 2_000; rig.control.tick(true)
        assertEquals(VisionState(), dismissed)
        assertEquals(dismissed, rig.control.state.value, "Dismissing a completed error must also retire the old report clock")
    }
}
