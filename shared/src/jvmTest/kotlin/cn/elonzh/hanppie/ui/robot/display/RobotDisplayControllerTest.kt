package cn.elonzh.hanppie.ui.robot.display

import cn.elonzh.hanppie.robot.display.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Synthetic values and controllable replies, shared by root and actual-route tests. */
internal class DisplayFixture : RobotDisplayService {
    var sight = SightCalibration()
    var lights = HeadlightBrightness(35, 55)
    var reads = 0; var writes = 0
    var read: suspend (DisplayTarget) -> Unit = {}
    var write: suspend (() -> Unit) -> Unit = { it() }
    override suspend fun readSight(): SightCalibration { reads++; read(DisplayTarget.SIGHT); return sight }
    override suspend fun readHeadlights(): HeadlightBrightness { reads++; read(DisplayTarget.HEADLIGHTS); return lights }
    override suspend fun setSight(value: SightCalibration, onDispatch: () -> Unit): SightCalibration {
        writes++; write(onDispatch); sight = value; return value
    }
    override suspend fun setHeadlights(percent: Int, onDispatch: () -> Unit): HeadlightBrightness {
        writes++; write(onDispatch); lights = HeadlightBrightness(percent, percent); return lights
    }
}

class RobotDisplayControllerTest {
    @Test fun readsDoNotTakeActuatorOwnershipAndWritesNeedTheCorrespondingSnapshot(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var ownership = 0; val peer = DisplayFixture()
        val controller = RobotDisplayController(scope) { _, action -> ownership++; action() }
        try {
            controller.attach(peer)
            assertEquals(peer.sight, controller.state.value.sight); assertEquals(0, ownership)
            controller.setHeadlights(20); assertEquals(0, peer.writes)
            controller.refreshHeadlights(); assertEquals(0, ownership)
            controller.setHeadlights(20)
            assertEquals(HeadlightBrightness(20,20), controller.state.value.headlights)
            assertEquals(DisplayTarget.HEADLIGHTS, controller.state.value.confirmed)
            controller.setSight(SightCalibration(SightPoint(5001,5002),SightPoint(5003,5004)))
            assertEquals(peer.sight, controller.state.value.sight); assertEquals(2, ownership)
            assertEquals(DisplayTarget.SIGHT, controller.state.value.confirmed)
        } finally { scope.cancel() }
    }

    @Test fun rejectionAdmissionAndUnknownWritesKeepDistinctRecoveryStates(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var admitted = true; val peer = DisplayFixture()
        val controller = RobotDisplayController(scope) { _, action -> check(admitted); action() }
        try {
            controller.attach(peer); controller.refreshHeadlights()
            admitted = false; controller.setHeadlights(40); assertEquals(0, peer.writes)
            assertNotNull(controller.state.value.headlights); assertNull(controller.state.value.confirmed)
            admitted = true
            peer.write = { callback -> callback(); throw RobotRequestException(RequestProblem.REJECTED,7) }
            controller.setHeadlights(40)
            assertNotNull(controller.state.value.headlights); assertNull(controller.state.value.uncertain)
            peer.write = { callback -> callback(); throw RobotRequestException(RequestProblem.TIMED_OUT) }
            controller.setHeadlights(40)
            assertEquals(DisplayTarget.HEADLIGHTS, controller.state.value.uncertain)
            assertNull(controller.state.value.headlights); assertNotNull(controller.state.value.sight)
            val before = peer.writes; controller.setHeadlights(20); assertEquals(before,peer.writes)
            controller.refreshHeadlights(); assertNull(controller.state.value.uncertain)
            peer.write = { callback -> callback() }; controller.setHeadlights(20)
            assertEquals(DisplayTarget.HEADLIGHTS, controller.state.value.confirmed)
            peer.write = { callback -> callback(); throw RobotRequestException(RequestProblem.NOT_APPLIED) }
            controller.setSight(SightCalibration(SightPoint(5100,5200),SightPoint(5100,5200)))
            assertEquals(DisplayTarget.SIGHT,controller.state.value.uncertain)
            assertNull(controller.state.value.sight);assertNull(controller.state.value.confirmed)
            peer.read = { if (it == DisplayTarget.SIGHT) error("read failed") }
            controller.refreshSight(); assertNull(controller.state.value.sight)
            assertEquals(HeadlightBrightness(20,20),controller.state.value.headlights)
        } finally { scope.cancel() }
    }

    @Test fun cancellingQueriesOrReplacingTheConnectionDiscardsNonCancellableOldResults(): Unit = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val peer=DisplayFixture();val readGate=CompletableDeferred<Unit>()
        val controller=RobotDisplayController(scope){_,action->action()}
        try {
            peer.read={withContext(NonCancellable){readGate.await()}}
            controller.attach(peer);controller.refreshHeadlights();assertEquals(1,peer.reads)
            controller.cancelRead();readGate.complete(Unit);yield()
            assertFalse(controller.state.value.busy);assertNull(controller.state.value.sight)
            peer.read={};controller.refreshSight()
            val writeGate=CompletableDeferred<Unit>()
            peer.write={callback->withContext(NonCancellable){writeGate.await();callback()}}
            controller.setSight(SightCalibration(SightPoint(5100,5200)))
            val replacement=DisplayFixture();controller.attach(replacement)
            writeGate.complete(Unit);yield()
            assertEquals(replacement.sight,controller.state.value.sight)
            assertNull(controller.state.value.confirmed);assertNull(controller.state.value.uncertain)
            controller.clear();assertNull(controller.state.value.uncertain)
        } finally { scope.cancel() }
    }

    @Test fun disconnectAfterDispatchRetainsUnknownWithoutPublishingIntoTheNextConnection(): Unit = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val peer=DisplayFixture();val gate=CompletableDeferred<Unit>()
        val controller=RobotDisplayController(scope){_,action->action()}
        try {
            controller.attach(peer);controller.refreshHeadlights()
            peer.write={callback->callback();withContext(NonCancellable){gate.await()}}
            controller.setHeadlights(40);controller.clear();controller.clear()
            assertFalse(controller.state.value.connected);assertNull(controller.state.value.headlights)
            assertEquals(DisplayTarget.HEADLIGHTS,controller.state.value.uncertain)
            controller.attach(DisplayFixture());gate.complete(Unit);yield()
            assertNull(controller.state.value.uncertain);assertNull(controller.state.value.headlights)
            assertNotNull(controller.state.value.sight)
        } finally { scope.cancel() }
    }
}
