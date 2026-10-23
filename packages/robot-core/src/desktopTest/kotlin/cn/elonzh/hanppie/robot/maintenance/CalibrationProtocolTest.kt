package cn.elonzh.hanppie.robot.maintenance

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class CalibrationProtocolTest {
    @Test fun imuStartAndStopUseTheSameNativeParameterWithDifferentValues(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        val protocol = CalibrationProtocol { node, set, id, p -> sent += "$node/$set/$id/${p.hex()}"; byteArrayOf(0) }
        protocol.startImu(); protocol.stopImu()
        // The native config constructor hashes the actual parameter name, not the C# action key.
        val hash = "imu_cali_cmd_start_clai_0".encodeToByteArray().fold(0L) { h, c -> ((h shl 8) or (c.toLong() and 255)) % 0xfffffffbL }
        assertEquals(0x7d99a3e4L, hash)
        assertEquals(listOf("3/3/249/e4a3997d01", "3/3/249/e4a3997d03"), sent)
    }

    @Test fun gimbalAndArmAreSeparateEmptyPayloadActionsWithNoInventedStopSelector(): Unit = runBlocking {
        val sent = mutableListOf<Triple<Int, Int, Int>>()
        val protocol = CalibrationProtocol { node, set, id, p -> assertTrue(p.isEmpty()); sent += Triple(node, set, id); byteArrayOf(0) }
        protocol.startGimbal(); protocol.calibrateArm()
        assertEquals(listOf(Triple(4, 4, 8), Triple(0xc3, 0x33, 0x69)), sent)
    }

    @Test fun requestErrorsPropagateAndCloseOrCancellationCannotDispatchAnotherAction(): Unit = runBlocking {
        var count = 0
        val protocol = CalibrationProtocol { _, _, _, _ -> count++; throw RobotRequestException(RequestProblem.REJECTED, 7) }
        val error = assertFailsWith<RobotRequestException> { protocol.startGimbal() }
        assertEquals(7, error.responseCode); protocol.close(); protocol.close()
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { protocol.stopImu() }.problem)
        assertEquals(1, count)
        val gate = CompletableDeferred<Unit>()
        val canceled = CalibrationProtocol { _, _, _, _ -> count++; gate.await(); byteArrayOf(0) }
        val work = launch(start = CoroutineStart.UNDISPATCHED) { canceled.startImu(); canceled.startGimbal() }
        work.cancelAndJoin(); gate.complete(Unit); assertEquals(2, count)
    }

    @Test fun imuReportUsesTheExistingCompositeSubscriptionAndPreservesRawBytes() {
        val p = ByteArray(62).apply { this[1] = 1; this[12] = 3; this[13] = 67; this[14] = 42; this[15] = 2; this[16] = 9 }
        val frame = DussFrame(0, 9, 2, 71, 0, 0x48, 8, p, true)
        val result = assertNotNull(imuCalibrationReport(frame))
        assertEquals(ImuCalibrationReport(3, 67, 42, 2, 9), result)
        assertEquals(ImuCalibrationStatus.IN_PROGRESS, result.status); assertEquals(67, result.progress)
        assertEquals(ImuCalibrationSide.RIGHT, result.side)
        assertEquals(setOf(ImuCalibrationSide.HORIZONTAL, ImuCalibrationSide.BACK), result.finishedSides)
        for (wrong in listOf(frame.copy(valid = false), frame.copy(sender = 3), frame.copy(receiver = 1), frame.copy(attr = 0x80),
            frame.copy(set = 4), frame.copy(id = 0x30), frame.copy(payload = p.copyOf(61)), frame.copy(payload = p + 0),
            frame.copy(payload = p.copyOf().apply { this[0] = 1 }), frame.copy(payload = p.copyOf().apply { this[1] = 15 })))
            assertNull(imuCalibrationReport(wrong))
    }

    @Test fun imuWireSidesAreNotThePublicBitMaskAndUnknownsNeverBecomeSuccess() {
        fun report(state: Int = 0, progress: Int = 0, side: Int = 4, mask: Int = 0) = ImuCalibrationReport(state, progress, 0, side, mask)
        assertEquals(listOf(ImuCalibrationSide.FRONT, ImuCalibrationSide.BACK, ImuCalibrationSide.RIGHT,
            ImuCalibrationSide.LEFT, ImuCalibrationSide.HORIZONTAL), (0..4).map { report(side = it).side })
        assertEquals(ImuCalibrationStatus.FINISHED, report(0x51).status)
        assertEquals(ImuCalibrationStatus.FAILED, report(0xff).status)
        assertEquals(ImuCalibrationStatus.FAILED, report(0x80).status)
        assertEquals(ImuCalibrationStatus.UNKNOWN, report(0x52).status)
        val unknown = report(0x52, 255, 255, 255)
        assertNull(unknown.side); assertNull(unknown.progress); assertEquals(255, unknown.finishedMaskRaw)
        assertEquals(ImuCalibrationSide.entries.toSet(), unknown.finishedSides)
        assertEquals(ImuCalibrationStatus.UNKNOWN, unknown.status, "All five bits cannot manufacture a completion state")
    }

    @Test fun gimbalProgressPrecedesStateAndOnlyTheKnownNativeSourceIsAccepted() {
        val frame = DussFrame(0, 4, 2, 72, 0, 4, 0x30, byteArrayOf(75, 1), true)
        val result = assertNotNull(gimbalCalibrationReport(frame))
        assertEquals(GimbalCalibrationReport(75, 1), result); assertEquals(GimbalCalibrationStatus.IN_PROGRESS, result.status)
        for (wrong in listOf(frame.copy(valid = false), frame.copy(sender = 0x24), frame.copy(receiver = 1), frame.copy(attr = 0x40),
            frame.copy(set = 0x3f), frame.copy(id = 8), frame.copy(payload = byteArrayOf(75)))) assertNull(gimbalCalibrationReport(wrong))
        assertEquals(result, gimbalCalibrationReport(frame.copy(payload = byteArrayOf(75, 1, 99))))
        assertEquals(GimbalCalibrationStatus.IDLE, GimbalCalibrationReport(100, 0).status)
        assertEquals(GimbalCalibrationStatus.FAILED, GimbalCalibrationReport(20, 3).status)
        assertEquals(GimbalCalibrationStatus.UNKNOWN, GimbalCalibrationReport(100, 2).status)
        assertEquals(GimbalCalibrationStatus.UNKNOWN, GimbalCalibrationReport(100, 255).status)
        assertNull(GimbalCalibrationReport(255, 0).progress)
    }
}
