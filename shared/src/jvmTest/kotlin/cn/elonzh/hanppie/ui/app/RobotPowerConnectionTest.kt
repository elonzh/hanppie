package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.DiscoveredRobot
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.robot.device.*
import kotlinx.coroutines.*
import kotlin.test.*

internal fun powerRuntime(sessions: List<RobotSessionFixture>): RobotRuntime = object : RobotRuntime {
    private var opened = 0
    override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = error("unused")
    override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("unused")
    override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String) = error("unused")
    override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
        onLost: (RobotSession, String) -> Unit): RobotSession = sessions[opened++]
}

class RobotPowerConnectionTest {
    @Test fun acknowledgedStandbyStopsInputSuppressesPreviewAndRetiresTheSessionUntilExplicitReconnect(): Unit = runBlocking {
        val services = List(2) { PowerFixture() }; val sessions = services.map { RobotSessionFixture().apply { power = it } }
        val gate = CompletableDeferred<Unit>()
        services[0].action = { _, dispatched -> dispatched(); gate.await() }
        val model = testConsoleModel(robotRuntime = powerRuntime(sessions))
        suspend fun waitFor(check: () -> Boolean) = withTimeout(5000) { while (!check()) delay(5) }
        try {
            model.connect("127.0.0.1", "12345678"); waitFor { model.state.value.connected && !model.state.value.busy }
            model.remoteInput.value = listOf(0.5, 0.1, 0.0, 0.0, 0.0)
            model.robotPower.perform(RobotPowerAction.ENTER_STANDBY)
            waitFor { services[0].actions == 1 }
            assertTrue(model.state.value.busy); assertTrue(model.remoteInput.value.all { it == 0.0 })
            model.startMedia(false); delay(50); assertEquals(0, sessions[0].mediaStarts.get())
            gate.complete(Unit)
            waitFor { !model.state.value.connected && !model.state.value.busy }
            assertTrue(sessions[0].closed)
            assertFalse(model.robotPower.state.value.connected)
            assertEquals(PowerResult(RobotPowerAction.ENTER_STANDBY, PowerOutcome.ACKNOWLEDGED), model.robotPower.state.value.result)
            assertEquals(1, services[0].actions)
            model.connect("127.0.0.1", "12345678"); waitFor { model.state.value.connected && !model.state.value.busy }
            assertNull(model.robotPower.state.value.result); assertNull(model.robotPower.state.value.policy)
            assertEquals(0, services[1].actions)
        } finally { model.close() }
        assertNull(model.robotPower.state.value.result)
    }
    @Test fun activeProgramsRejectPowerWithoutDispatchAndWakeNeverRestoresInput(): Unit = runBlocking {
        val service = PowerFixture(); val session = RobotSessionFixture().apply { power = service }
        val model = testConsoleModel(robotRuntime = powerRuntime(listOf(session)))
        suspend fun settled() = withTimeout(5000) { while (model.state.value.busy || model.robotPower.state.value.busy) delay(5) }
        try {
            model.connect("127.0.0.1", "12345678"); settled()
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.RUNNING)
            model.robotPower.perform(RobotPowerAction.SHUTDOWN); settled()
            assertEquals(0, service.actions); assertNotNull(model.robotPower.state.value.error); assertFalse(session.closed)
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.IDLE)
            model.remoteInput.value = listOf(0.4, 0.0, 0.0, 0.0, 0.0)
            model.robotPower.perform(RobotPowerAction.EXIT_STANDBY); settled()
            assertEquals(1, service.actions); assertTrue(model.state.value.connected); assertFalse(session.closed)
            assertTrue(model.remoteInput.value.all { it == 0.0 })
        } finally { model.close() }
    }
    @Test fun pausingAfterDispatchRetainsAnUnconfirmedResultWithoutLeavingTheControllerBusy(): Unit = runBlocking {
        val service = PowerFixture(); val session = RobotSessionFixture().apply { power = service }
        val model = testConsoleModel(robotRuntime = powerRuntime(listOf(session)))
        service.action = { _, dispatched -> dispatched(); awaitCancellation() }
        suspend fun waitFor(check: () -> Boolean) = withTimeout(5000) { while (!check()) delay(5) }
        try {
            model.connect("127.0.0.1", "12345678"); waitFor { model.state.value.connected && !model.state.value.busy }
            model.robotPower.perform(RobotPowerAction.SHUTDOWN); waitFor { service.actions == 1 }
            model.pauseConnection()
            assertTrue(session.closed); assertFalse(model.robotPower.state.value.busy)
            assertFalse(model.robotPower.state.value.connected)
            assertEquals(PowerResult(RobotPowerAction.SHUTDOWN, PowerOutcome.UNCONFIRMED), model.robotPower.state.value.result)
            assertEquals(1, service.actions)
        } finally { model.close() }
    }
}
