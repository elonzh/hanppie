package cn.elonzh.hanppie.ui.robot.maintenance

import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.session.RobotTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

internal class CalibrationServiceFixture : RobotCalibrationService {
    override val state = MutableStateFlow(RobotCalibrationState())
    val calls = mutableListOf<String>()
    var onBegin: suspend () -> Unit = {}
    var onGimbal: suspend () -> Unit = {}
    var onCancel: suspend () -> Unit = {}
    override suspend fun begin(kind: CalibrationKind) {
        calls += "begin:$kind"
        state.value = RobotCalibrationState(kind, CalibrationPhase.STARTING, busy = true,
            needsGimbal = kind == CalibrationKind.CHASSIS_AND_GIMBAL, gimbalMayBeActive = kind == CalibrationKind.GIMBAL)
        try {
            onBegin()
            state.value = state.value.copy(phase = when (kind) {
                CalibrationKind.CHASSIS_AND_GIMBAL -> CalibrationPhase.CHASSIS
                CalibrationKind.GIMBAL -> CalibrationPhase.GIMBAL
                CalibrationKind.ARM -> CalibrationPhase.ARM_ACKNOWLEDGED
            }, requestAcknowledged = true)
        } catch (error: Exception) { state.value = state.value.copy(phase = CalibrationPhase.UNCERTAIN, problem = CalibrationProblem.REQUEST_UNKNOWN); throw error }
        finally { state.value = state.value.copy(busy = false) }
    }
    override suspend fun startGimbal() {
        check(state.value.phase == CalibrationPhase.WAITING_LEVEL && state.value.levelReady)
        calls += "gimbal"; state.value = state.value.copy(phase = CalibrationPhase.GIMBAL, gimbalMayBeActive = true)
        onGimbal()
    }
    override suspend fun cancel() {
        calls += "cancel"; check(state.value.canStopImu)
        try { onCancel(); state.value = state.value.copy(phase = CalibrationPhase.CANCELED, stopAcknowledged = true) }
        catch (error: Exception) { state.value = state.value.copy(phase = CalibrationPhase.UNCERTAIN, problem = CalibrationProblem.STOP_UNCONFIRMED); throw error }
    }
    override suspend fun retryArm() {
        check(state.value.kind == CalibrationKind.ARM && state.value.phase == CalibrationPhase.UNCERTAIN)
        calls += "retry-arm"; state.value = state.value.copy(phase = CalibrationPhase.ARM_ACKNOWLEDGED, requestAcknowledged = true)
    }
    override fun confirmRestarted() {
        check(state.value.observingInterrupted && state.value.phase == CalibrationPhase.UNCERTAIN &&
            state.value.imuStatusRaw != 3 && state.value.gimbalStatusRaw != 1)
        calls += "restart-confirmed"; state.value = state.value.copy(phase = CalibrationPhase.CANCELED, restartConfirmedByOperator = true)
    }
    override suspend fun observeInterrupted(previous: RobotCalibrationState) {
        calls += "observe"
        state.value = previous.copy(phase = CalibrationPhase.UNCERTAIN, busy = false, observingInterrupted = true,
            requestAcknowledged = false, reportAgeSeconds = null, imuStatusRaw = null, imuProgressRaw = null,
            imuSideRaw = null, finishedMaskRaw = null, gimbalStatusRaw = null, gimbalProgressRaw = null)
    }
}

class RobotCalibrationControllerTest {
    private val origin = RobotTarget("192.168.2.1", "12345678", remotePort = 32451)
    @Test fun aStableCombinedTaskContinuesOnlyOnceAndNeverReplaysOnAttach(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = CalibrationServiceFixture()
        val controller = RobotCalibrationController(scope, { _, action -> action() }, { true })
        try {
            controller.attach(service, origin); assertTrue(service.calls.isEmpty())
            controller.begin(CalibrationKind.CHASSIS_AND_GIMBAL)
            service.state.value = service.state.value.copy(phase = CalibrationPhase.WAITING_LEVEL, levelReady = true)
            yield()
            assertEquals(listOf("begin:CHASSIS_AND_GIMBAL", "gimbal"), service.calls)
            service.state.value = service.state.value.copy(gimbalProgressRaw = 25); yield()
            assertEquals(1, service.calls.count { it == "gimbal" })
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun leavingOrForegroundLossRequiresExplicitContinuationAfterTheNextReadyReport(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = CalibrationServiceFixture(); var foreground = true
        val controller = RobotCalibrationController(scope, { _, action -> action() }, { foreground })
        try {
            controller.attach(service, origin); controller.begin(CalibrationKind.CHASSIS_AND_GIMBAL)
            foreground = false; controller.stopAutomaticContinuation()
            service.state.value = service.state.value.copy(phase = CalibrationPhase.WAITING_LEVEL, levelReady = true)
            foreground = true; yield(); assertTrue(service.calls.none { it == "gimbal" })
            controller.pageLeft(); assertTrue(service.calls.none { it == "cancel" })
            controller.continueGimbal(); yield(); assertEquals(1, service.calls.count { it == "gimbal" })
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun cancelJoinsTheInterruptedStartBeforeTheNativeImuStopAndNeverContinues(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = CalibrationServiceFixture().apply { onBegin = { try { awaitCancellation() } finally { calls += "begin-canceled" } } }
        val controller = RobotCalibrationController(scope, { _, action -> action() }, { true })
        try {
            controller.attach(service, origin); controller.begin(CalibrationKind.CHASSIS_AND_GIMBAL)
            var ended = false; controller.cancel { ended = true }; yield()
            assertTrue(ended); assertFalse(controller.state.value.busy)
            assertTrue(service.calls.indexOf("begin-canceled") < service.calls.indexOf("cancel"))
            assertFalse(controller.state.value.autoContinue)
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun anOldNoncancellableResultAndStaleIntentsCannotAffectTheNewBinding(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>(); val old = CalibrationServiceFixture().apply { onBegin = { withContext(NonCancellable) { gate.await() } } }
        val fresh = CalibrationServiceFixture()
        val controller = RobotCalibrationController(scope, { _, action -> action() }, { true })
        try {
            controller.attach(old, origin); val connection = controller.state.value.connection
            controller.begin(CalibrationKind.GIMBAL); controller.attach(fresh, origin)
            gate.complete(Unit); yield()
            assertEquals(CalibrationPhase.IDLE, controller.state.value.task.phase); assertFalse(controller.state.value.busy)
            assertNotNull(controller.state.value.recovery)
            controller.begin(CalibrationKind.ARM, connection); controller.cancel(connection); controller.continueGimbal(connection)
            controller.retryArm(connection); controller.observePrevious(connection); controller.confirmRestarted(connection)
            controller.pageLeft(connection); assertTrue(fresh.calls.isEmpty())
        } finally { gate.complete(Unit); controller.clear(); scope.cancel() }
    }

    @Test fun recoveryRemainsBoundToTheOriginalTargetAndObservationNeverRestartsCalibration(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = CalibrationServiceFixture(); val fresh = CalibrationServiceFixture()
        val controller = RobotCalibrationController(scope, { _, action -> action() }, { true })
        try {
            controller.attach(old, origin); controller.begin(CalibrationKind.GIMBAL); controller.detach()
            controller.attach(fresh, origin.copy(remotePort = 32452))
            controller.observePrevious(); controller.confirmRestarted(); controller.cancel()
            assertTrue(fresh.calls.isEmpty()); assertNotNull(controller.state.value.recovery)
            controller.attach(fresh, origin); controller.observePrevious(); yield()
            assertEquals(listOf("observe"), fresh.calls)
            assertNotNull(controller.state.value.recovery); assertFalse(controller.state.value.autoContinue)
            fresh.state.value = fresh.state.value.copy(phase = CalibrationPhase.CANCELED)
            yield(); assertNull(controller.state.value.recovery); assertFalse(controller.state.value.blocksControl)
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun failedAutomaticContinuationDoesNotCreateAnAutomaticRetryLoop(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = CalibrationServiceFixture().apply { onGimbal = { error("not confirmed") } }
        val controller = RobotCalibrationController(scope, { _, action -> action() }, { true })
        try {
            controller.attach(service, origin); controller.begin(CalibrationKind.CHASSIS_AND_GIMBAL)
            service.state.value = service.state.value.copy(phase = CalibrationPhase.WAITING_LEVEL, levelReady = true); yield()
            assertNotNull(controller.state.value.error); assertFalse(controller.state.value.autoContinue)
            service.state.value = service.state.value.copy(gimbalProgressRaw = 10); yield()
            assertEquals(1, service.calls.count { it == "gimbal" })
        } finally { controller.clear(); scope.cancel() }
    }
    @Test fun aContradictoryReportRestoresRecoveryWithoutContaminatingANewOrdinaryTask(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = CalibrationServiceFixture(); val fresh = CalibrationServiceFixture()
        val controller = RobotCalibrationController(scope, { _, action -> action() }, { true })
        try {
            controller.attach(old, origin); controller.begin(CalibrationKind.GIMBAL); controller.attach(fresh, origin)
            controller.observePrevious(); controller.confirmRestarted(); yield()
            assertNull(controller.state.value.recovery)
            fresh.state.value = fresh.state.value.copy(phase = CalibrationPhase.UNCERTAIN, restartConfirmedByOperator = false, gimbalStatusRaw = 1)
            yield(); assertNotNull(controller.state.value.recovery); assertTrue(controller.state.value.canRecover)
            fresh.state.value = fresh.state.value.copy(phase = CalibrationPhase.CANCELED)
            yield(); assertNull(controller.state.value.recovery)
            controller.begin(CalibrationKind.ARM); yield()
            assertNull(controller.state.value.recovery); assertFalse(controller.state.value.blocksControl)
            assertEquals(CalibrationPhase.ARM_ACKNOWLEDGED, controller.state.value.task.phase)
        } finally { controller.clear(); scope.cancel() }
    }

}
