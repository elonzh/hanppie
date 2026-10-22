package cn.elonzh.hanppie.ui.robot.network

import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class RobotWifiControllerTest {
    private val origin = RobotTarget("127.0.0.1", "12345678")

    @Test fun savingAndRestartingAreSeparateAndRecoverySurvivesItsExpectedDisconnect(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val service = WifiFixture()
            var reconnects = 0
            lateinit var controller: RobotWifiController
            controller = RobotWifiController(scope, { _, action -> action() }, { controller.detach() }, { target, expected ->
                reconnects++; assertEquals(origin, target); assertEquals(service.profile, expected)
                controller.attach(service, target)
                service.read()
            })
            controller.attach(service, origin)
            assertEquals(0, service.reads)
            controller.refresh(); val previous = controller.state.value.profile
            controller.edit("RMS1_New", "new_pass"); controller.apply()
            assertEquals(WifiPhase.READY, controller.state.value.phase)
            assertEquals(WifiConfirmedField.entries.toSet(), controller.state.value.confirmed)
            assertEquals(0, service.reboots); assertEquals(0, reconnects)
            controller.reboot()
            assertEquals(WifiPhase.NETWORK_REQUIRED, controller.state.value.phase)
            assertFalse(controller.state.value.connected)
            assertTrue(controller.state.value.restartAcknowledged)
            assertEquals(previous, controller.state.value.recovery?.previous)
            controller.reconnect()
            assertEquals(WifiPhase.VERIFIED, controller.state.value.phase)
            assertTrue(controller.state.value.connected); assertNull(controller.state.value.recovery)
            assertEquals(1, reconnects); assertEquals(1, service.reboots)
            controller.refresh(); assertEquals(WifiPhase.EDITING, controller.state.value.phase)
            controller.close(); assertNull(controller.state.value.draft); assertNull(controller.state.value.profile)
        } finally { scope.cancel() }
    }

    @Test fun partialWritePreservesBothProfilesAndDoesNotRebootOrAutomaticallyRetry(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val service = WifiFixture().apply { saveError = RobotRequestException(RequestProblem.TIMED_OUT) }
            val controller = RobotWifiController(scope, { _, action -> action() }, {}, { _, _ -> error("not connected") })
            controller.attach(service, origin); controller.refresh()
            controller.edit("RMS1_New", "new_pass"); controller.apply()
            assertEquals(WifiPhase.EDITING, controller.state.value.phase)
            assertEquals(setOf(WifiConfirmedField.SSID), controller.state.value.confirmed)
            assertNotNull(controller.state.value.error)
            controller.reboot(); assertEquals(0, service.reboots)
            controller.detach()
            assertEquals(WifiPhase.NETWORK_REQUIRED, controller.state.value.phase)
            assertEquals("RMS1_Old", controller.state.value.recovery?.previous?.ssid)
            assertEquals("new_pass", controller.state.value.recovery?.expected?.password)
            assertFalse(controller.state.value.toString().contains("new_pass"))
            controller.reconnect(); assertEquals(WifiPhase.NETWORK_REQUIRED, controller.state.value.phase)
            assertNotNull(controller.state.value.recovery); assertEquals(1, service.writes.size)
            controller.cancel(); assertEquals(RobotWifiState(connection = controller.state.value.connection), controller.state.value)
        } finally { scope.cancel() }
    }

    @Test fun rejectedRestartCanBeRetriedWhileUnknownRestartMovesToExplicitNetworkRecovery(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val service = WifiFixture()
            var retires = 0
            val controller = RobotWifiController(scope, { _, action -> action() }, { retires++ }, { _, _ -> error("unused") })
            controller.attach(service, origin); controller.refresh()
            service.rebootError = RobotRequestException(RequestProblem.REJECTED, 2)
            controller.reboot(); assertEquals(WifiPhase.EDITING, controller.state.value.phase)
            assertNull(controller.state.value.recovery)
            controller.edit("RMS1_New", "new_pass"); controller.apply()
            service.rebootError = RobotRequestException(RequestProblem.REJECTED, 2)
            controller.reboot(); assertEquals(WifiPhase.READY, controller.state.value.phase); assertEquals(0, retires)
            service.rebootError = RobotRequestException(RequestProblem.TIMED_OUT)
            controller.reboot(); assertEquals(WifiPhase.NETWORK_REQUIRED, controller.state.value.phase)
            assertFalse(controller.state.value.restartAcknowledged); assertEquals(1, retires)
            assertEquals(3, service.reboots)
        } finally { scope.cancel() }
    }

    @Test fun queryAndReconnectCancellationRejectLateResultsAndAClosedScopeDoesNoWork(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val restoreGate = CompletableDeferred<Unit>()
        try {
            val service = WifiFixture().apply { readGate = gate }
            val controller = RobotWifiController(scope, { _, action -> action() }, {}, { _, expected ->
                withContext(NonCancellable) { restoreGate.await() }; expected
            })
            controller.attach(service, origin); controller.refresh(); controller.refresh()
            assertEquals(1, service.reads)
            controller.stopWaiting(); controller.attach(service, origin)
            gate.complete(Unit); scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertNull(controller.state.value.profile)
            service.readGate = null; controller.refresh()
            controller.edit("RMS1_New", "new_pass"); controller.apply(); controller.reboot(); controller.detach()
            controller.reconnect(); assertEquals(WifiPhase.RECONNECTING, controller.state.value.phase)
            controller.stopWaiting(); controller.cancel()
            restoreGate.complete(Unit); scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertEquals(WifiPhase.IDLE, controller.state.value.phase); assertNull(controller.state.value.recovery)
            scope.cancel(); controller.attach(service, origin); controller.refresh(); controller.apply(); controller.reboot()
            assertNull(controller.state.value.profile); assertEquals(1, service.writes.size); assertEquals(1, service.reboots)
        } finally { gate.complete(Unit); restoreGate.complete(Unit); scope.cancel() }
    }
}
