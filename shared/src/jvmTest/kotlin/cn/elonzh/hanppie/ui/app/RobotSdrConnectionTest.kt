package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.robot.network.*
import kotlinx.coroutines.*
import kotlin.test.*

class RobotSdrConnectionTest {
    @Test fun changesStopRemoteAndReconnectDoesNotPublishBeforeSdrVerification(): Unit = runBlocking {
        val services = List(3) { SdrFixture() }
        val sessions = services.map { service -> RobotSessionFixture().apply { sdr = service } }
        val targets = mutableListOf<RobotTarget>()
        val runtime = object : RobotRuntime {
            override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = error("Do not scan during explicit recovery")
            override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("unused")
            override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String) = error("unused")
            override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
                onLost: (RobotSession, String) -> Unit): RobotSession { targets += target; return sessions[targets.size - 1] }
        }
        val store = MemorySettingsStore(); val model = testConsoleModel(robotRuntime = runtime, settingsStore = store)
        suspend fun until(check: () -> Boolean) { withTimeout(5_000) { while (!check()) delay(5) } }
        try {
            model.connect("127.0.0.1", "12345678")
            until { model.robotSdr.state.value.settings != null && !model.state.value.busy }
            model.state.value = model.state.value.copy(scriptRunPhase = cn.elonzh.hanppie.robot.lab.ScriptRunPhase.RUNNING)
            model.robotSdr.apply(SdrChange.Point(1012)); until { !model.robotSdr.state.value.busy }
            assertTrue(services[0].writes.isEmpty())
            model.state.value = model.state.value.copy(scriptRunPhase = cn.elonzh.hanppie.robot.lab.ScriptRunPhase.IDLE)
            model.enableRemote(); until { model.remoteEnabled.value && !model.state.value.busy }
            model.robotSdr.apply(SdrChange.Bandwidth(SdrBandwidth.MHZ_10))
            until { model.robotSdr.state.value.verified && !model.state.value.busy }
            assertFalse(model.remoteEnabled.value)
            services[0].error = RobotRequestException(RequestProblem.TIMED_OUT)
            model.robotSdr.apply(SdrChange.Point(1012))
            until { model.robotSdr.state.value.phase == SdrPhase.NETWORK_REQUIRED && !model.state.value.connected }
            assertTrue(sessions[0].closed); assertEquals(1, targets.size)
            val before = store.loadConnection(); val gate = CompletableDeferred<Unit>(); services[1].readGate = gate
            model.robotSdr.reconnect(); until { services[1].reads == 1 }
            assertFalse(model.state.value.connected); assertEquals(SdrPhase.RECONNECTING, model.robotSdr.state.value.phase)
            gate.complete(Unit)
            until { model.robotSdr.state.value.phase == SdrPhase.NETWORK_REQUIRED && !model.state.value.busy }
            assertTrue(sessions[1].closed); assertFalse(model.state.value.connected); assertEquals(before, store.loadConnection())
            services[2].settings = services[0].settings?.copy(point = 1012)
            model.robotSdr.reconnect()
            until { model.robotSdr.state.value.phase == SdrPhase.VERIFIED && !model.state.value.busy }
            assertEquals(1012, model.robotSdr.state.value.settings?.point); assertTrue(model.state.value.connected)
            assertEquals(List(3) { targets.first() }, targets)
            model.disconnect(); until { !model.state.value.busy && !model.state.value.connected }
            assertFalse(model.robotSdr.state.value.visible); assertNull(model.robotSdr.state.value.recovery)
        } finally { model.close() }
    }
}
