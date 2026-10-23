package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.robot.remote.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Synthetic robot configuration for controller and real settings-page tests. */
internal class MotionFixture : RobotMotionService {
    var configuration = RobotMotionConfiguration(2, ChassisParameter.entries.associateWith {
        val speed = it in listOf(ChassisParameter.FORWARD_SPEED, ChassisParameter.BACKWARD_SPEED, ChassisParameter.LATERAL_SPEED)
        ChassisParameterReading(ChassisParameterRange(0f, if (speed) 3f else 100f, if (speed) 1.5f else 50f, 8, 1), if (speed) 1f else 30f)
    })
    var reads = 0; var writes = 0
    val changed = mutableListOf<ChassisParameter>()
    var read: suspend () -> RobotMotionConfiguration = { configuration }
    var write: suspend (() -> Unit) -> Unit = { it() }
    override suspend fun read(): RobotMotionConfiguration { reads++; return read.invoke() }
    override suspend fun setSpeedLevel(level: Int, onDispatch: () -> Unit): RobotMotionConfiguration {
        writes++; write(onDispatch); configuration = configuration.copy(speedLevelRaw = level); return configuration
    }
    override suspend fun setParameter(parameter: ChassisParameter, value: Float, onDispatch: () -> Unit): ChassisParameterReading {
        writes++; write(onDispatch); changed += parameter
        val reading = configuration.parameters.getValue(parameter).copy(value = value)
        configuration = configuration.copy(parameters = configuration.parameters + (parameter to reading))
        return reading
    }
}

class RobotMotionControllerTest {
    @Test fun queryPrecedesWritesAndUnknownResultsRequireARefresh(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val peer = MotionFixture(); val controller = RobotMotionController(scope) { _, action -> action() }
        try {
            controller.attach(peer); controller.setSpeedLevel(4); assertEquals(0, peer.writes)
            controller.refresh(); controller.setSpeedLevel(4)
            assertTrue(controller.state.value.confirmed); assertEquals(4, controller.state.value.configuration?.speedLevelRaw)
            controller.setParameter(ChassisParameter.FORWARD_SPEED, 8f); assertEquals(1, peer.writes)
            controller.setParameter(ChassisParameter.FORWARD_SPEED, .5f)
            assertEquals(.5f, controller.state.value.configuration?.parameters?.get(ChassisParameter.FORWARD_SPEED)?.value)
            peer.write = { dispatched -> dispatched(); throw RobotRequestException(RequestProblem.TIMED_OUT) }
            controller.setSpeedLevel(1); assertTrue(controller.state.value.uncertain); assertNull(controller.state.value.configuration)
            controller.setSpeedLevel(2); controller.setParameter(ChassisParameter.FORWARD_SPEED, 1f); assertEquals(3, peer.writes)
            controller.refresh(); assertFalse(controller.state.value.uncertain); assertNotNull(controller.state.value.configuration)
        } finally { scope.cancel() }
    }

    @Test fun admissionAndRejectionKeepThePreviouslyReadStateWithoutConfirmation(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var admitted = true; val peer = MotionFixture()
        val controller = RobotMotionController(scope) { _, action -> check(admitted); action() }
        try {
            controller.attach(peer); controller.refresh(); admitted = false; controller.setSpeedLevel(4)
            assertEquals(0, peer.writes); assertNotNull(controller.state.value.error); assertFalse(controller.state.value.confirmed)
            admitted = true; peer.write = { throw RobotRequestException(RequestProblem.REJECTED, 5) }
            controller.setSpeedLevel(4)
            assertEquals(2, controller.state.value.configuration?.speedLevelRaw)
            assertNotNull(controller.state.value.error); assertFalse(controller.state.value.uncertain)
        } finally { scope.cancel() }
    }

    @Test fun cancellationAndNewConnectionsRetireLateSnapshotsAndDispatchCallbacks(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val peer = MotionFixture(); val gate = CompletableDeferred<Unit>()
        val controller = RobotMotionController(scope) { _, action -> action() }
        try {
            controller.attach(peer)
            peer.read = { withContext(NonCancellable) { gate.await() }; peer.configuration }
            controller.refresh(); controller.refresh(); assertEquals(1, peer.reads)
            controller.cancelRead(); gate.complete(Unit); yield()
            assertFalse(controller.state.value.busy); assertNull(controller.state.value.configuration)
            peer.read = { peer.configuration }; controller.refresh()
            val oldWrite = CompletableDeferred<Unit>()
            peer.write = { callback -> withContext(NonCancellable) { oldWrite.await(); callback() } }
            controller.setSpeedLevel(4)
            val replacement = MotionFixture(); controller.attach(replacement); controller.refresh()
            oldWrite.complete(Unit); yield()
            assertEquals(2, controller.state.value.configuration?.speedLevelRaw)
            assertFalse(controller.state.value.confirmed)
            controller.clear(); assertFalse(controller.state.value.uncertain)
        } finally { scope.cancel() }
    }

    @Test fun dispatchedWriteLossRetainsUnknownUntilTheNextConnection(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val peer = MotionFixture(); val gate = CompletableDeferred<Unit>()
        val controller = RobotMotionController(scope) { _, action -> action() }
        try {
            controller.attach(peer); controller.refresh()
            peer.write = { callback -> callback(); withContext(NonCancellable) { gate.await() } }
            controller.setSpeedLevel(4); controller.clear()
            assertTrue(controller.state.value.uncertain); assertFalse(controller.state.value.connected)
            controller.clear(); assertTrue(controller.state.value.uncertain)
            controller.attach(MotionFixture()); gate.complete(Unit); yield()
            assertFalse(controller.state.value.uncertain); assertNull(controller.state.value.configuration)
        } finally { scope.cancel() }
    }
}
