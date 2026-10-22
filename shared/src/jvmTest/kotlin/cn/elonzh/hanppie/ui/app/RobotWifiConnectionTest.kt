package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.robot.network.*
import cn.elonzh.hanppie.ui.settings.ConnectionPreferences
import kotlinx.coroutines.*
import kotlin.test.*

class RobotWifiConnectionTest {
    @Test fun reconnectUsesTheOriginalTargetAndPublishesOnlyAfterConfigurationVerification(): Unit = runBlocking {
        val services = List(6) { WifiFixture() }
        val sessions = services.map { service -> RobotSessionFixture().apply { wifi = service } }
        val opened = mutableListOf<RobotTarget>()
        var discoveries = 0
        val runtime = object : RobotRuntime {
            override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> { discoveries++; return emptyList() }
            override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("unused")
            override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String) = error("unused")
            override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
                onLost: (RobotSession, String) -> Unit): RobotSession {
                val index = opened.size; opened += target; return sessions[index]
            }
        }
        val store = MemorySettingsStore()
        val model = testConsoleModel(robotRuntime = runtime, settingsStore = store)
        suspend fun waitFor(check: () -> Boolean) { withTimeout(5_000) { while (!check()) delay(10) } }
        try {
            model.connect("127.0.0.1", "12345678")
            waitFor { model.state.value.connected && !model.state.value.busy }
            model.robotWifi.refresh(); waitFor { model.robotWifi.state.value.profile != null }
            model.robotWifi.edit("RMS1_New", "new_pass")
            model.state.value = model.state.value.copy(scriptRunPhase = cn.elonzh.hanppie.robot.lab.ScriptRunPhase.RUNNING)
            model.robotWifi.apply(); waitFor { !model.robotWifi.state.value.busy }
            assertTrue(services[0].writes.isEmpty()); assertNull(model.robotWifi.state.value.recovery)
            model.state.value = model.state.value.copy(scriptRunPhase = cn.elonzh.hanppie.robot.lab.ScriptRunPhase.IDLE)
            model.enableRemote(); waitFor { model.remoteEnabled.value && !model.state.value.busy }
            model.robotWifi.apply(); waitFor { model.robotWifi.state.value.phase == WifiPhase.READY }
            assertFalse(model.remoteEnabled.value)
            assertEquals(0, services[0].reboots)
            model.robotWifi.reboot(); waitFor { model.robotWifi.state.value.phase == WifiPhase.NETWORK_REQUIRED && !model.state.value.connected }
            assertTrue(sessions[0].closed); assertEquals(1, opened.size)
            val preferences = store.loadConnection()
            val cancelledRead = CompletableDeferred<Unit>(); services[1].readGate = cancelledRead
            model.robotWifi.reconnect()
            waitFor { services[1].reads == 1 }
            model.robotWifi.stopWaiting(); cancelledRead.complete(Unit)
            waitFor { !model.state.value.busy }
            assertTrue(sessions[1].closed); assertFalse(model.state.value.connected)
            assertEquals(WifiPhase.NETWORK_REQUIRED, model.robotWifi.state.value.phase)
            assertNotNull(model.robotWifi.state.value.recovery); assertEquals(preferences, store.loadConnection())
            model.robotWifi.reconnect()
            waitFor { model.robotWifi.state.value.phase == WifiPhase.NETWORK_REQUIRED && !model.state.value.busy }
            assertFalse(model.state.value.connected); assertTrue(sessions[2].closed)
            assertNotNull(model.robotWifi.state.value.error); assertEquals(preferences, store.loadConnection())
            services[3].profile = RobotWifiProfile(0, "RMS1_New", "new_pass")
            val gate = CompletableDeferred<Unit>(); services[3].readGate = gate
            model.robotWifi.reconnect(); waitFor { services[3].reads == 1 }
            assertFalse(model.state.value.connected, "A connected socket is not verified network configuration")
            assertEquals(WifiPhase.RECONNECTING, model.robotWifi.state.value.phase)
            gate.complete(Unit)
            waitFor { model.robotWifi.state.value.phase == WifiPhase.VERIFIED && !model.state.value.busy }
            assertTrue(model.state.value.connected); assertFalse(sessions[3].closed)
            assertEquals(List(4) { opened.first() }, opened)
            assertEquals(0, discoveries)
            val saved: ConnectionPreferences = store.loadConnection()
            assertTrue(saved.routerSsid.isEmpty()); assertTrue(saved.routerPassword.isEmpty())
            assertFalse(saved.toString().contains("new_pass")); assertNull(model.robotWifi.state.value.recovery)
            model.robotWifi.refresh(); waitFor { model.robotWifi.state.value.phase == WifiPhase.EDITING }
            model.robotWifi.refreshRadio(); waitFor { model.robotWifi.state.value.radio != null }
            model.robotWifi.setChannelMode(WifiChannelMode.MANUAL)
            waitFor { model.robotWifi.state.value.radio?.selectionCode == 1 }
            services[3].radioWriteError = RobotRequestException(RequestProblem.TIMED_OUT)
            model.robotWifi.setChannel(149)
            waitFor { model.robotWifi.state.value.phase == WifiPhase.NETWORK_REQUIRED && !model.state.value.connected }
            assertEquals(WifiRadioTarget(WifiChannelMode.MANUAL, 149), model.robotWifi.state.value.recovery?.expectedRadio)
            services[4].profile = services[3].profile; services[4].radio = services[3].radio
            model.robotWifi.reconnect()
            waitFor { model.robotWifi.state.value.phase == WifiPhase.NETWORK_REQUIRED && !model.state.value.busy }
            assertTrue(sessions[4].closed); assertFalse(model.state.value.connected)
            services[5].profile = services[3].profile; services[5].radio = services[3].radio.copy(currentChannel = 149)
            model.robotWifi.reconnect()
            waitFor { model.robotWifi.state.value.phase == WifiPhase.VERIFIED && !model.state.value.busy }
            assertTrue(model.state.value.connected); assertEquals(149, model.robotWifi.state.value.radio?.currentChannel)
            assertEquals(List(6) { opened.first() }, opened); assertEquals(0, discoveries)
            model.disconnect(); waitFor { !model.state.value.connected && !model.state.value.busy }
            assertNull(model.robotWifi.state.value.draft); assertNull(model.robotWifi.state.value.profile)
        } finally { model.close() }
    }

    @Test fun wifiCredentialAndProvisioningFramesAreRedactedEvenWhenMalformed() {
        val model = testConsoleModel()
        val secret = "never-log-this-secret"
        try {
            for (id in listOf(7, 8, 0x0d, 0x0e, 0x3b)) for (valid in listOf(false, true))
                model.receive(DussFrame(0, 7, 2, 7, 0xc0, 7, id, secret.encodeToByteArray(), valid))
            val logs = model.state.value.frames.toString() + model.state.value.logs
            assertTrue(logs.contains("redacted"))
            assertFalse(logs.contains(secret)); assertFalse(logs.contains(secret.encodeToByteArray().hex()))
        } finally { model.close() }
    }
}
