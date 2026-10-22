package cn.elonzh.hanppie.ui.robot.device

import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotSystemStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

internal class PowerFixture : RobotPowerService {
    var policy = LowPowerPolicy(true, 300)
    var read: suspend () -> LowPowerPolicy = { policy }
    var set: suspend (LowPowerPolicy, () -> Unit) -> Unit = { value, dispatched -> dispatched(); policy = value }
    var action: suspend (RobotPowerAction, () -> Unit) -> Unit = { _, dispatched -> dispatched() }
    var writes = 0; var actions = 0
    override suspend fun readPolicy() = read()
    override suspend fun setPolicy(policy: LowPowerPolicy, onDispatch: () -> Unit) { writes++; set(policy, onDispatch) }
    override suspend fun perform(action: RobotPowerAction, onDispatch: () -> Unit) { actions++; this.action(action, onDispatch) }
}

class RobotPowerControllerTest {
    @Test fun settingsRequireQueryThenReadbackAndUnknownResultsDoNotEnableAnotherWrite(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = PowerFixture(); val controller = RobotPowerController(scope, { _, action -> action() }, {})
        try {
            controller.attach(service, MutableStateFlow(null))
            controller.apply(LowPowerPolicy(false, 300)); assertEquals(0, service.writes)
            controller.refresh(); controller.apply(LowPowerPolicy(false, 60))
            assertEquals(LowPowerPolicy(false, 60), controller.state.value.policy); assertTrue(controller.state.value.verified)
            service.set = { _, dispatched -> dispatched() }
            controller.apply(LowPowerPolicy(true, 30))
            assertEquals(LowPowerPolicy(false, 60), controller.state.value.policy); assertNotNull(controller.state.value.error)
            assertFalse(controller.state.value.verified)
            service.set = { _, dispatched -> dispatched(); throw RobotRequestException(RequestProblem.TIMED_OUT) }
            controller.apply(LowPowerPolicy(true, 30)); assertNull(controller.state.value.policy)
            controller.apply(LowPowerPolicy(true, 30)); assertEquals(3, service.writes)
            controller.refresh(); assertNotNull(controller.state.value.policy)
        } finally { scope.cancel() }
    }
    @Test fun uncertainDispatchedActionsSurviveLinkLossButNewConnectionsRetireOldReports(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = PowerFixture(); var retired = 0
        lateinit var controller: RobotPowerController
        controller = RobotPowerController(scope, { _, action -> action() }, { retired++; controller.detach() })
        val reports = MutableStateFlow<RobotSystemStatus?>(RobotSystemStatus(0, 0, false))
        try {
            service.action = { _, dispatched -> dispatched(); controller.detach(); throw RobotRequestException(RequestProblem.DISCONNECTED) }
            controller.attach(service, reports); controller.perform(RobotPowerAction.SHUTDOWN)
            assertEquals(PowerResult(RobotPowerAction.SHUTDOWN, PowerOutcome.UNCONFIRMED), controller.state.value.result)
            assertFalse(controller.state.value.connected); assertFalse(controller.state.value.busy); assertEquals(1, retired)
            assertNull(controller.state.value.report); assertNull(controller.state.value.policy)
            controller.attach(service, MutableStateFlow(null)); reports.value = RobotSystemStatus(100, 200, true)
            assertNull(controller.state.value.report); assertNull(controller.state.value.result)
            controller.clear(); assertFalse(controller.state.value.connected)
        } finally { scope.cancel() }
    }
    @Test fun acknowledgedActionsEndSessionsExceptWakeAndRejectionsStayRecoverable(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = PowerFixture(); var retired = 0
        val controller = RobotPowerController(scope, { _, action -> action() }, { retired++ })
        try {
            controller.attach(service, MutableStateFlow(null))
            controller.perform(RobotPowerAction.EXIT_STANDBY); assertEquals(0, retired)
            assertEquals(PowerResult(RobotPowerAction.EXIT_STANDBY, PowerOutcome.ACKNOWLEDGED), controller.state.value.result)
            for (action in listOf(RobotPowerAction.ENTER_STANDBY, RobotPowerAction.REBOOT, RobotPowerAction.SHUTDOWN)) {
                controller.perform(action); assertEquals(PowerOutcome.ACKNOWLEDGED, controller.state.value.result?.outcome)
            }
            assertEquals(3, retired)
            service.action = { _, dispatched -> dispatched(); throw RobotRequestException(RequestProblem.REJECTED) }
            controller.perform(RobotPowerAction.SHUTDOWN)
            assertEquals(3, retired); assertNull(controller.state.value.result); assertNotNull(controller.state.value.error)
            assertTrue(controller.state.value.connected)
        } finally { scope.cancel() }
    }
    @Test fun admissionFailureDispatchesNothingAndReadCancellationRejectsLateCompletion(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = PowerFixture(); var admitted = false; val gate = CompletableDeferred<Unit>()
        val controller = RobotPowerController(scope, { _, action -> check(admitted); action() }, { error("Must not retire") })
        try {
            controller.attach(service, MutableStateFlow(null)); controller.perform(RobotPowerAction.SHUTDOWN)
            assertEquals(0, service.actions); assertNotNull(controller.state.value.error)
            service.read = { withContext(NonCancellable) { gate.await() }; service.policy }
            controller.refresh(); controller.refresh(); assertTrue(controller.state.value.busy)
            controller.cancelRead(); gate.complete(Unit)
            assertNull(controller.state.value.policy); assertFalse(controller.state.value.busy)
            admitted = true; service.read = { service.policy }; controller.refresh()
            assertEquals(service.policy, controller.state.value.policy)
        } finally { scope.cancel() }
    }
    @Test fun rebindingDuringAnActionCannotPublishItsLateResultIntoTheNewConnection(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = PowerFixture(); val gate = CompletableDeferred<Unit>()
        val controller = RobotPowerController(scope, { _, action -> action() }, { error("Old action cannot retire new connection") })
        try {
            service.action = { _, dispatched -> dispatched(); withContext(NonCancellable) { gate.await() } }
            controller.attach(service, MutableStateFlow(null)); controller.perform(RobotPowerAction.SHUTDOWN)
            controller.attach(PowerFixture(), MutableStateFlow(null)); gate.complete(Unit)
            assertTrue(controller.state.value.connected); assertNull(controller.state.value.result)
        } finally { scope.cancel() }
    }
}
