package cn.elonzh.hanppie.ui.robot.maintenance

import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.session.RobotTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

internal class AddressingServiceFixture : RobotAddressingService {
    override val state = MutableStateFlow(RobotAddressingState())
    val calls = mutableListOf<String>()
    var renewals = 0
    var onBegin: suspend () -> Unit = {}
    var onAssign: suspend () -> Unit = {}
    var failRecovery = false
    var failEnd = false
    override suspend fun begin(kind: ModuleAddressingKind) {
        calls += "begin:$kind"; state.value = RobotAddressingState(kind, AddressingPhase.STARTING, busy = true)
        try { onBegin(); state.value = state.value.copy(phase = when (kind) {
            ModuleAddressingKind.WHEELS, ModuleAddressingKind.ARMOR -> AddressingPhase.ADDRESSING
            ModuleAddressingKind.SENSOR_ADAPTER -> AddressingPhase.VERIFYING
            else -> AddressingPhase.DISCOVERING
        }) }
        catch (error: CancellationException) { state.value = state.value.copy(phase = AddressingPhase.UNCERTAIN); throw error }
        finally { state.value = state.value.copy(busy = false) }
    }
    override suspend fun assign(id: Int, candidate: Int) {
        check(state.value.candidateId == candidate)
        calls += "assign:$id"; state.value = state.value.copy(busy = true)
        try { onAssign() } catch (error: CancellationException) { calls += "assign-canceled"; throw error }
        finally { state.value = state.value.copy(busy = false) }
    }
    override suspend fun skipArmor(step: Int) { check(step == state.value.armorSteps); calls += "skip" }
    override suspend fun prepareVerification() {
        calls += "verify"; state.value = state.value.copy(phase = AddressingPhase.VERIFYING,
            wheelTestReady = state.value.kind == ModuleAddressingKind.WHEELS,
            inventoryKnown = false, onlineIds = emptySet())
    }
    override suspend fun identify(id: Int) { calls += "identify:$id" }
    override fun holdWheel(wheel: AddressingWheel?) { calls += "hold:$wheel"; state.value = state.value.copy(wheelHeld = wheel) }
    override fun renewWheel() { renewals++ }
    override fun confirmWheel(wheel: AddressingWheel) { calls += "confirm:$wheel"; state.value = state.value.copy(verifiedWheels = state.value.verifiedWheels + wheel) }
    override fun confirmModule(id: Int) { calls += "confirm:$id"; state.value = state.value.copy(verifiedIds = state.value.verifiedIds + id) }
    override suspend fun end() {
        calls += "end"
        if (failEnd) { state.value = state.value.copy(phase = AddressingPhase.UNCERTAIN, problem = AddressingProblem.RESULT_UNKNOWN); error("end not confirmed") }
        state.value = state.value.copy(phase = AddressingPhase.FINISHED, nativeEndAcknowledged = true)
    }
    override suspend fun recover(kind: ModuleAddressingKind) {
        calls += "recover:$kind"
        state.value = RobotAddressingState(kind, AddressingPhase.ENDING)
        if (failRecovery) { state.value = state.value.copy(phase = AddressingPhase.UNCERTAIN); error("recovery not confirmed") }
        state.value = state.value.copy(phase = AddressingPhase.FINISHED, nativeEndAcknowledged = true)
    }
}

class RobotAddressingControllerTest {
    private val origin = RobotTarget("192.168.2.1", "12345678", remotePort = 32451)
    @Test fun lateResultsFromAnInterruptedBindingCannotOverwriteTheNewConnection(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = AddressingServiceFixture(); val fresh = AddressingServiceFixture(); val gate = CompletableDeferred<Unit>()
        old.onBegin = { withContext(NonCancellable) { gate.await() } }
        val controller = RobotAddressingController(scope, { _, action -> action() }, { true })
        try {
            controller.attach(old, origin); controller.begin(ModuleAddressingKind.TOF)
            assertTrue(controller.state.value.busy)
            controller.attach(fresh, origin)
            assertFalse(controller.state.value.busy); assertTrue(controller.state.value.canRecover)
            gate.complete(Unit); yield()
            assertEquals(AddressingPhase.IDLE, controller.state.value.task.phase)
            assertEquals(AddressingRecovery(origin, ModuleAddressingKind.TOF), controller.state.value.recovery)
            controller.recover(); yield()
            assertEquals(listOf("recover:TOF"), fresh.calls.filterNot { it.startsWith("hold:") })
            assertNull(controller.state.value.recovery)
        } finally { gate.complete(Unit); controller.clear(); scope.cancel() }
    }

    @Test fun endingJoinsTheCanceledAssignmentBeforeSendingTermination(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = AddressingServiceFixture(); val gate = CompletableDeferred<Unit>()
        service.onAssign = { gate.await() }
        val controller = RobotAddressingController(scope, { _, action -> action() }, { true })
        try {
            controller.attach(service, origin); controller.begin(ModuleAddressingKind.TOF)
            service.state.value = service.state.value.copy(candidateId = 1)
            controller.assign(2); assertTrue(controller.state.value.busy)
            var ended = 0; controller.end { ended++ }; yield()
            assertEquals(1, ended); assertFalse(controller.state.value.busy)
            assertTrue(service.calls.indexOf("assign-canceled") < service.calls.indexOf("end"))
            assertEquals(AddressingPhase.FINISHED, controller.state.value.task.phase)
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun anOldLeaseCleanupCannotReleaseANewPressAndForegroundLossStopsIt(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = AddressingServiceFixture(); var foreground = true
        val controller = RobotAddressingController(scope, { _, action -> action() }, { foreground })
        try {
            controller.attach(service, origin)
            controller.holdWheel(AddressingWheel.FRONT_RIGHT)
            controller.holdWheel(null)
            controller.holdWheel(AddressingWheel.REAR_LEFT)
            delay(85)
            assertEquals(AddressingWheel.REAR_LEFT, service.state.value.wheelHeld)
            assertTrue(service.renewals >= 3)
            foreground = false; delay(85)
            assertNull(service.state.value.wheelHeld)
            val count = service.calls.count { it == "hold:FRONT_RIGHT" }
            controller.holdWheel(AddressingWheel.FRONT_RIGHT)
            assertEquals(count, service.calls.count { it == "hold:FRONT_RIGHT" })
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun detachRetainsTheOriginWithoutLeavingBusyStateAndNeverRecoversAnotherRobot(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = AddressingServiceFixture(); val gate = CompletableDeferred<Unit>(); old.onBegin = { gate.await() }
        val controller = RobotAddressingController(scope, { _, action -> action() }, { true })
        try {
            controller.attach(old, origin); controller.begin(ModuleAddressingKind.SERVO); controller.detach()
            assertFalse(controller.state.value.busy); assertFalse(controller.state.value.connected)
            assertEquals(AddressingPhase.CLOSED, controller.state.value.task.phase)
            val wrong = AddressingServiceFixture(); controller.attach(wrong, origin.copy(remotePort = 32452))
            assertFalse(controller.state.value.canRecover); controller.recover()
            assertTrue(wrong.calls.none { it.startsWith("recover:") }); assertNotNull(controller.state.value.error)
            controller.end(); assertNotNull(controller.state.value.recovery)
            assertTrue(wrong.calls.none { it == "end" })
            assertNotNull(controller.state.value.recovery)
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun failedRecoveryStaysPendingUntilAnExplicitEndIsAcknowledged(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = AddressingServiceFixture()
        val controller = RobotAddressingController(scope, { _, action -> action() }, { true })
        try {
            controller.attach(old, origin); controller.begin(ModuleAddressingKind.WHEELS); controller.detach()
            val current = AddressingServiceFixture().apply { failRecovery = true; failEnd = true }
            controller.attach(current, origin); controller.recover()
            assertNotNull(controller.state.value.recovery); assertTrue(controller.state.value.blocksControl)
            controller.end(); assertNotNull(controller.state.value.recovery)
            current.failEnd = false; controller.end()
            assertNull(controller.state.value.recovery); assertFalse(controller.state.value.blocksControl)
        } finally { controller.clear(); scope.cancel() }
    }
    @Test fun staleConnectionIntentsAndPageDisposalCannotReachTheNewBinding(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = AddressingServiceFixture(); val fresh = AddressingServiceFixture()
        val controller = RobotAddressingController(scope, { _, action -> action() }, { true })
        try {
            controller.attach(old, origin); val connection = controller.state.value.connection
            controller.attach(fresh, origin)
            controller.holdWheel(AddressingWheel.REAR_LEFT)
            val before = fresh.calls.toList()
            controller.begin(ModuleAddressingKind.TOF, connection)
            controller.assign(2, connection, 1); controller.skipArmor(connection, 0)
            controller.prepareVerification(connection); controller.identify(1, connection)
            controller.confirmModule(1, connection); controller.confirmWheel(AddressingWheel.FRONT_RIGHT, connection)
            controller.pulseWheel(AddressingWheel.FRONT_RIGHT, connection); controller.end(connection); controller.pageLeft(connection)
            assertEquals(before, fresh.calls); assertEquals(AddressingWheel.REAR_LEFT, fresh.state.value.wheelHeld)
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun anOldAccessibilityPulseTimerCannotStopANewerPress(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = AddressingServiceFixture()
        val controller = RobotAddressingController(scope, { _, action -> action() }, { true })
        try {
            controller.attach(service, origin); controller.pulseWheel(AddressingWheel.FRONT_RIGHT)
            delay(600); controller.holdWheel(AddressingWheel.REAR_LEFT)
            delay(450); assertEquals(AddressingWheel.REAR_LEFT, service.state.value.wheelHeld)
            controller.holdWheel(null); controller.pulseWheel(AddressingWheel.REAR_RIGHT)
            delay(1_050); assertNull(service.state.value.wheelHeld)
        } finally { controller.clear(); scope.cancel() }
    }

}
