package cn.elonzh.hanppie.ui.robot.network

import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class RobotSdrControllerTest {
    private val origin = RobotTarget("127.0.0.1", "12345678")
    @Test fun positiveEvidenceIsRequiredAndLateCancelledReadsCannotRestoreIt(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val service = SdrFixture().apply { settings = null }
            val controller = RobotSdrController(scope, { _, action -> action() }, {}, { _, _ -> error("unused") })
            controller.attach(service, origin); assertFalse(controller.state.value.visible)
            val gate = CompletableDeferred<Unit>(); service.readGate = gate
            controller.refresh(); controller.stopWaiting(); controller.detach()
            service.settings = SdrSettings(0, 0, 1011, 1, true); gate.complete(Unit)
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertFalse(controller.state.value.visible); assertFalse(controller.state.value.connected)
            service.readGate = null; controller.attach(service, origin)
            assertTrue(controller.state.value.visible)
            controller.close(); assertFalse(controller.state.value.visible)
        } finally { scope.cancel() }
    }

    @Test fun writeReadbackRejectionAndUncertaintyHaveDifferentRecoveryPaths(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val service = SdrFixture(); lateinit var controller: RobotSdrController
            var reconnects = 0
            controller = RobotSdrController(scope, { _, action -> action() }, { controller.detach() }, { target, change ->
                assertEquals(origin, target); assertEquals(SdrChange.Point(1012), change); reconnects++
                controller.attach(service, target, service.settings)
            })
            controller.attach(service, origin); controller.apply(SdrChange.Bandwidth(SdrBandwidth.MHZ_10))
            assertEquals(1, controller.state.value.settings?.bandwidthCode); assertTrue(controller.state.value.verified)
            service.applyWrites = false; controller.apply(SdrChange.Point(1012))
            assertEquals(SdrPhase.READY, controller.state.value.phase); assertNull(controller.state.value.recovery)
            assertNotNull(controller.state.value.error)
            service.error = RobotRequestException(RequestProblem.REJECTED)
            controller.apply(SdrChange.Point(1012)); assertTrue(controller.state.value.connected); assertNull(controller.state.value.recovery)
            service.error = RobotRequestException(RequestProblem.TIMED_OUT)
            controller.apply(SdrChange.Point(1012))
            assertEquals(SdrPhase.NETWORK_REQUIRED, controller.state.value.phase); assertEquals(0, reconnects)
            assertFalse(controller.state.value.connected); assertNotNull(controller.state.value.recovery)
            service.settings = service.settings?.copy(point = 1012)
            controller.reconnect(); assertEquals(SdrPhase.VERIFIED, controller.state.value.phase)
            assertEquals(1, reconnects); assertNull(controller.state.value.recovery)
            controller.detach(false); assertFalse(controller.state.value.visible)
        } finally { scope.cancel() }
    }
}
