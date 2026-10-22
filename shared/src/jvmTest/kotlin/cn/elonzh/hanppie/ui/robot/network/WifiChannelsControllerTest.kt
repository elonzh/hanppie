package cn.elonzh.hanppie.ui.robot.network

import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class WifiChannelsControllerTest {
    private val origin = RobotTarget("127.0.0.1", "12345678")
    private suspend fun until(condition: () -> Boolean) { withTimeout(3_000) { while (!condition()) delay(1) } }

    @Test fun radioChangesVerifyReadbackAndPreserveUncertainResultsForExplicitRecovery(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val service = WifiFixture()
            lateinit var controller: RobotWifiController
            controller = RobotWifiController(scope, { _, action -> action() }, { controller.detach() }, { target, profile, radio ->
                assertEquals(origin, target); assertEquals(WifiRadioTarget(WifiChannelMode.MANUAL, 149), radio)
                controller.attach(service, target); WifiVerification(profile, service.radio)
            })
            controller.attach(service, origin); controller.refresh(); controller.refreshRadio()
            controller.setChannel(149); assertTrue(service.channelWrites.isEmpty())
            controller.setChannelMode(WifiChannelMode.MANUAL)
            assertEquals(1, controller.state.value.radio?.selectionCode); assertNotNull(controller.state.value.radioMessage)
            assertTrue(service.writes.isEmpty()); assertEquals(0, service.reboots)
            service.applyRadioWrites = false
            controller.setChannel(149)
            assertEquals(WifiPhase.EDITING, controller.state.value.phase); assertNotNull(controller.state.value.error)
            assertEquals(1, controller.state.value.radio?.currentChannel); assertNull(controller.state.value.recovery)
            service.applyRadioWrites = true; service.radioBeforeWriteError = IllegalStateException("preflight")
            controller.setChannel(149); assertNull(controller.state.value.recovery)
            service.radioBeforeWriteError = null; service.radioWriteError = RobotRequestException(RequestProblem.REJECTED, 3)
            controller.setChannel(149); assertTrue(controller.state.value.connected); assertNull(controller.state.value.recovery)
            service.radioWriteError = RobotRequestException(RequestProblem.TIMED_OUT)
            controller.setChannel(149)
            assertEquals(WifiPhase.NETWORK_REQUIRED, controller.state.value.phase)
            assertEquals(WifiRadioTarget(WifiChannelMode.MANUAL, 149), controller.state.value.recovery?.expectedRadio)
            assertFalse(controller.state.value.connected)
            service.radio = service.radio.copy(currentChannel = 149)
            controller.reconnect(); assertEquals(WifiPhase.VERIFIED, controller.state.value.phase)
            assertEquals(149, controller.state.value.radio?.currentChannel)
        } finally { scope.cancel() }
    }

    @Test fun channelReportsBelongToTheVisibleManualViewAndSameServiceRebindingCleansUpFirst(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val service = WifiFixture().apply { radio = radio.copy(selectionCode = 1) }
            val controller = RobotWifiController(scope, { _, action -> action() }, {}, { _, _, _ -> error("unused") })
            controller.attach(service, origin); controller.refresh(); controller.refreshRadio()
            yield(); assertTrue(service.reportWrites.isEmpty())
            controller.showChannels(true); until { service.reportWrites == listOf(true) }
            service.signals.value = WifiSignals(listOf(WifiChannelSignal(6, -88)), 2)
            until { controller.state.value.signals.interferenceCode == 2 }
            controller.showChannels(false); until { service.reportWrites == listOf(true, false) }
            controller.showChannels(true); until { service.reportWrites == listOf(true, false, true) }
            controller.attach(service, origin); controller.refresh(); controller.refreshRadio()
            until { service.reportWrites.size == 5 }
            assertEquals(listOf(true, false, true, false, true), service.reportWrites)
            controller.close(); until { service.reportWrites.lastOrNull() == false }
            service.signals.value = WifiSignals(interferenceCode = 4); yield()
            assertEquals(WifiSignals(), controller.state.value.signals)
        } finally { scope.cancel() }
    }

    @Test fun leavingDuringEnableStillDisablesAndFailedEnableRequiresAnExplicitRetry(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        try {
            val service = WifiFixture().apply { radio = radio.copy(selectionCode = 1); reportGate = gate }
            val controller = RobotWifiController(scope, { _, action -> action() }, {}, { _, _, _ -> error("unused") })
            controller.attach(service, origin); controller.refresh(); controller.refreshRadio(); controller.showChannels(true)
            until { service.reportWrites.size == 1 }
            controller.showChannels(false); gate.complete(Unit)
            until { service.reportWrites == listOf(true, false) }
            service.reportError = RobotRequestException(RequestProblem.TIMED_OUT)
            controller.showChannels(true); until { controller.state.value.monitorError != null }
            val count = service.reportWrites.size; yield(); assertEquals(count, service.reportWrites.size)
            service.reportError = null; controller.retrySignals()
            until { controller.state.value.monitoring }
            assertEquals(count + 1, service.reportWrites.size)
            controller.close(); until { service.reportWrites.lastOrNull() == false }
        } finally { gate.complete(Unit); scope.cancel() }
    }
}
