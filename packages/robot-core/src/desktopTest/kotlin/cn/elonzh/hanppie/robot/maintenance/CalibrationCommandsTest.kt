package cn.elonzh.hanppie.robot.maintenance

import cn.elonzh.hanppie.robot.product.WorkingRobotDevice
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import kotlinx.coroutines.*
import kotlin.test.*

private class CalibrationFixture {
    var now = 0L
    var health = RobotHealth(modules = listOf(WorkingRobotDevice(0x0400, emptyList()), WorkingRobotDevice(0x031e, emptyList())))
    val requests = mutableListOf<String>()
    val sends = mutableListOf<String>()
    var reply: suspend (Int, Int, Int, ByteArray) -> ByteArray = { _, _, _, _ -> byteArrayOf(0) }
    val service = CalibrationCommands(RobotLock(), { health }, { node, set, id, p ->
        requests += "$node/$set/$id/${p.hex()}"; reply(node, set, id, p)
    }, { node, set, id, p -> sends += "$node/$set/$id/${p.hex()}" }, { now })
    fun imu(state: Int, progress: Int = 0, side: Int = 4, mask: Int = 0) {
        val p = ByteArray(62).apply { this[1] = 1; this[12] = state.toByte(); this[13] = progress.toByte(); this[15] = side.toByte(); this[16] = mask.toByte() }
        service.receive(DussFrame(0, 9, 2, 71, 0, 0x48, 8, p, true))
    }
    fun gimbal(state: Int, progress: Int = 0) = service.receive(DussFrame(0, 4, 2, 72, 0, 4, 0x30, byteArrayOf(progress.toByte(), state.toByte()), true))
}

class CalibrationCommandsTest {
    @Test fun combinedCalibrationRequiresItsOwnStartAndFreshStableLevelBeforeAnyGimbalRequest(): Unit = runBlocking {
        val f = CalibrationFixture()
        f.imu(0x51, 100, mask = 31)
        f.service.begin(CalibrationKind.CHASSIS_AND_GIMBAL)
        assertEquals(listOf("3/3/249/e4a3997d03", "3/3/249/e4a3997d01"), f.requests)
        f.imu(0x51, 100, mask = 31)
        assertEquals(CalibrationPhase.CHASSIS, f.service.state.value.phase, "An old finished report cannot finish the new round")
        f.imu(3, 30, side = 0); f.imu(0x51, 100, side = 0, mask = 31)
        assertEquals(CalibrationPhase.WAITING_LEVEL, f.service.state.value.phase)
        assertFailsWith<IllegalStateException> { f.service.startGimbal() }
        f.imu(0x51, 100, mask = 31); f.now = 1_999; f.service.tick()
        assertFalse(f.service.state.value.levelReady)
        f.now = 2_000; f.imu(0x51, 100, mask = 31); f.service.tick()
        assertTrue(f.service.state.value.levelReady); assertEquals(2, f.requests.size)
        f.service.startGimbal(); assertEquals("4/4/8/", f.requests.last())
        f.gimbal(0, 100); assertEquals(CalibrationPhase.GIMBAL, f.service.state.value.phase)
        f.gimbal(1, 50); f.gimbal(0, 100)
        assertEquals(CalibrationPhase.SUCCEEDED, f.service.state.value.phase)
        assertFalse(f.service.state.value.blocksControl)
    }

    @Test fun staleLevelCannotStartEvenWhenTheUiPreviouslySawReadyAndNeedsAnotherStableWindow(): Unit = runBlocking {
        val f = CalibrationFixture(); f.service.begin(CalibrationKind.CHASSIS_AND_GIMBAL)
        f.imu(3); f.imu(0x51); f.now = 2_000; f.imu(0x51); f.service.tick()
        assertTrue(f.service.state.value.levelReady)
        f.now = 5_001
        assertFailsWith<IllegalStateException> { f.service.startGimbal() }
        f.service.tick(); assertEquals(CalibrationProblem.REPORT_STALE, f.service.state.value.problem)
        f.imu(0x51); f.service.tick(); assertFalse(f.service.state.value.levelReady)
        f.now = 7_001; f.imu(0x51); f.service.tick(); assertTrue(f.service.state.value.levelReady)
    }

    @Test fun imuWithoutAGimbalFinishesWithoutInventingAnotherStageAndUnknownsKeepOwnership(): Unit = runBlocking {
        val f = CalibrationFixture(); f.health = RobotHealth(modules = emptyList())
        f.service.begin(CalibrationKind.CHASSIS_AND_GIMBAL)
        f.imu(3); f.imu(0x52, 255, 255, 255)
        assertEquals(CalibrationProblem.REPORT_UNKNOWN, f.service.state.value.problem)
        assertTrue(f.service.state.value.blocksControl)
        f.imu(0x51, 100, mask = 31)
        assertEquals(CalibrationPhase.SUCCEEDED, f.service.state.value.phase)
        assertEquals(2, f.requests.size)
        assertFailsWith<IllegalStateException> { f.service.begin(CalibrationKind.GIMBAL) }
        assertEquals(2, f.requests.size)
    }

    @Test fun reportsBeforeTheActionAckAreRetainedAndTheRequestNeverOverwritesTheTerminalReport(): Unit = runBlocking {
        val f = CalibrationFixture()
        f.reply = { _, set, id, _ -> if (set == 4 && id == 8) { f.gimbal(1, 20); f.gimbal(0, 100) }; byteArrayOf(0) }
        f.service.begin(CalibrationKind.GIMBAL)
        assertEquals(CalibrationPhase.SUCCEEDED, f.service.state.value.phase)
        assertTrue(f.service.state.value.requestAcknowledged); assertEquals(100, f.service.state.value.gimbalProgressRaw)
    }

    @Test fun chassisCancelIsAcknowledgedButTheSameActionCannotClaimToStopTheGimbal(): Unit = runBlocking {
        val f = CalibrationFixture(); f.service.begin(CalibrationKind.CHASSIS_AND_GIMBAL); f.service.cancel()
        assertEquals(CalibrationPhase.CANCELED, f.service.state.value.phase); assertTrue(f.service.state.value.stopAcknowledged)
        assertEquals("3/3/249/e4a3997d03", f.requests.last())
        f.service.begin(CalibrationKind.GIMBAL); f.gimbal(1, 25)
        val before = f.requests.size
        assertFailsWith<IllegalStateException> { f.service.cancel() }
        assertEquals(before, f.requests.size); assertTrue(f.service.state.value.blocksControl)
        assertEquals(CalibrationProblem.STOP_UNCONFIRMED, f.service.state.value.problem)
        f.gimbal(0, 100); assertFalse(f.service.state.value.blocksControl)
    }

    @Test fun failedChassisStopAndGimbalTimeoutDoNotReportSuccessOrReleaseControl(): Unit = runBlocking {
        val f = CalibrationFixture(); f.service.begin(CalibrationKind.CHASSIS_AND_GIMBAL)
        f.reply = { _, _, _, _ -> throw RobotRequestException(RequestProblem.TIMED_OUT) }
        assertFailsWith<RobotRequestException> { f.service.cancel() }
        assertEquals(CalibrationProblem.STOP_UNCONFIRMED, f.service.state.value.problem); assertTrue(f.service.state.value.blocksControl)
        f.reply = { _, _, _, _ -> byteArrayOf(0) }; f.service.cancel(); f.service.begin(CalibrationKind.GIMBAL)
        f.gimbal(1); f.now = 100_000; f.service.tick()
        assertEquals(CalibrationProblem.GIMBAL_TIMEOUT, f.service.state.value.problem); assertTrue(f.service.state.value.blocksControl)
        f.gimbal(3, 20); assertEquals(CalibrationPhase.FAILED, f.service.state.value.phase)
    }

    @Test fun armAckIsNotAProgressOrPhysicalSuccessAndUnknownWritesHaveNoInventedUndo(): Unit = runBlocking {
        val f = CalibrationFixture(); f.service.begin(CalibrationKind.ARM)
        assertEquals(listOf("195/51/105/"), f.requests)
        assertEquals(CalibrationPhase.ARM_ACKNOWLEDGED, f.service.state.value.phase)
        assertFalse(f.service.state.value.blocksControl)
        f.reply = { _, _, _, _ -> throw RobotRequestException(RequestProblem.TIMED_OUT) }
        assertFailsWith<RobotRequestException> { f.service.begin(CalibrationKind.ARM) }
        assertEquals(CalibrationPhase.UNCERTAIN, f.service.state.value.phase)
        val before = f.requests.size
        assertFailsWith<IllegalStateException> { f.service.cancel() }; assertEquals(before, f.requests.size)
    }

    @Test fun reconnectObservationNeverReplaysActionsAndAnIdleReportOnlyEndsTheUnknownTask(): Unit = runBlocking {
        val old = CalibrationFixture(); old.service.begin(CalibrationKind.GIMBAL); old.service.close()
        val fresh = CalibrationFixture(); fresh.service.observeInterrupted(old.service.state.value)
        assertTrue(fresh.requests.isEmpty()); assertTrue(fresh.service.state.value.observingInterrupted)
        fresh.gimbal(0, 0)
        assertEquals(CalibrationPhase.CANCELED, fresh.service.state.value.phase)
        assertFalse(fresh.service.state.value.blocksControl); assertFalse(fresh.service.state.value.requestAcknowledged)
        val imu = CalibrationFixture(); imu.service.begin(CalibrationKind.CHASSIS_AND_GIMBAL); imu.service.close()
        assertEquals(listOf("3/3/249/e4a3997d03"), imu.sends)
        fresh.service.observeInterrupted(imu.service.state.value); fresh.service.cancel()
        assertEquals(CalibrationPhase.CANCELED, fresh.service.state.value.phase)
        assertEquals(listOf("3/3/249/e4a3997d03"), fresh.requests)
    }

    @Test fun anInterruptedCombinedGimbalCannotBeClearedByAnImuStopAck(): Unit = runBlocking {
        val previous = RobotCalibrationState(CalibrationKind.CHASSIS_AND_GIMBAL, CalibrationPhase.CLOSED,
            problem = CalibrationProblem.REQUEST_UNKNOWN, gimbalMayBeActive = true, needsGimbal = true)
        val f = CalibrationFixture(); f.service.observeInterrupted(previous)
        assertFailsWith<IllegalStateException> { f.service.cancel() }
        assertTrue(f.requests.isEmpty()); assertTrue(f.service.state.value.blocksControl)
        f.imu(0x51); assertTrue(f.service.state.value.blocksControl)
        f.gimbal(0); assertEquals(CalibrationPhase.CANCELED, f.service.state.value.phase)
    }

    @Test fun closePrecedesLateAcksAndDoesNotManufactureAnUnsupportedActuatorStop(): Unit = runBlocking {
        val f = CalibrationFixture(); val gate = CompletableDeferred<Unit>()
        f.reply = { _, _, _, _ -> withContext(NonCancellable) { gate.await() }; byteArrayOf(0) }
        val work = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.service.begin(CalibrationKind.GIMBAL) } }
        assertTrue(f.service.state.value.blocksControl); f.service.close(); gate.complete(Unit)
        assertTrue(work.await().isFailure); assertEquals(CalibrationPhase.CLOSED, f.service.state.value.phase)
        assertTrue(f.sends.isEmpty()); f.gimbal(0, 100); f.service.tick()
        assertEquals(CalibrationPhase.CLOSED, f.service.state.value.phase)
    }
    @Test fun aGimbalRemovedAfterTheLevelPromptCannotReceiveTheOldStartIntent(): Unit = runBlocking {
        val f = CalibrationFixture(); f.service.begin(CalibrationKind.CHASSIS_AND_GIMBAL)
        f.imu(3); f.imu(0x51); f.now = 2_000; f.imu(0x51); f.service.tick()
        f.health = RobotHealth(modules = emptyList())
        val count = f.requests.size
        assertFailsWith<IllegalStateException> { f.service.startGimbal() }
        assertEquals(count, f.requests.size); assertEquals(CalibrationProblem.MODULE_CHANGED, f.service.state.value.problem)
        assertTrue(f.service.state.value.blocksControl)
    }

    @Test fun anUncertainArmWriteOnlyRetriesAfterAnExplicitPoseConfirmationAndAcknowledgement(): Unit = runBlocking {
        val f = CalibrationFixture()
        f.reply = { _, _, _, _ -> throw RobotRequestException(RequestProblem.TIMED_OUT) }
        assertFailsWith<RobotRequestException> { f.service.begin(CalibrationKind.ARM) }
        assertEquals(1, f.requests.size)
        f.service.tick(); f.imu(0x51); f.gimbal(0)
        assertEquals(1, f.requests.size); assertTrue(f.service.state.value.blocksControl)
        f.reply = { _, _, _, _ -> byteArrayOf(0) }; f.service.retryArm()
        assertEquals(listOf("195/51/105/", "195/51/105/"), f.requests)
        assertEquals(CalibrationPhase.ARM_ACKNOWLEDGED, f.service.state.value.phase)
        assertFailsWith<IllegalStateException> { f.service.retryArm() }
    }

    @Test fun aPhysicalRestartConfirmationIsSeparateFromANativeStopAndCannotEraseAnActiveSameSessionTask(): Unit = runBlocking {
        val f = CalibrationFixture(); f.service.begin(CalibrationKind.GIMBAL)
        assertFailsWith<IllegalStateException> { f.service.confirmRestarted() }
        f.service.close()
        val fresh = CalibrationFixture(); fresh.service.observeInterrupted(f.service.state.value)
        fresh.service.confirmRestarted()
        assertEquals(CalibrationPhase.CANCELED, fresh.service.state.value.phase)
        assertTrue(fresh.service.state.value.restartConfirmedByOperator)
        assertFalse(fresh.service.state.value.stopAcknowledged); assertTrue(fresh.requests.isEmpty())
        fresh.gimbal(1); assertTrue(fresh.service.state.value.blocksControl)
        assertFalse(fresh.service.state.value.restartConfirmedByOperator)
        assertTrue(fresh.requests.isEmpty())
        fresh.gimbal(0); assertEquals(CalibrationPhase.CANCELED, fresh.service.state.value.phase)
    }

    @Test fun reconnectDropsOldReportsAndAnActiveCurrentReportCannotBeDismissedAsAConfirmedRestart(): Unit = runBlocking {
        val f = CalibrationFixture()
        val previous = RobotCalibrationState(CalibrationKind.GIMBAL, CalibrationPhase.CLOSED,
            problem = CalibrationProblem.REQUEST_UNKNOWN, gimbalMayBeActive = true, gimbalStatusRaw = 1, gimbalProgressRaw = 45)
        f.service.observeInterrupted(previous)
        assertNull(f.service.state.value.gimbalStatusRaw); assertNull(f.service.state.value.gimbalProgressRaw)
        f.gimbal(1, 50)
        assertFailsWith<IllegalStateException> { f.service.confirmRestarted() }
        assertTrue(f.service.state.value.blocksControl); assertTrue(f.requests.isEmpty())
    }

}
