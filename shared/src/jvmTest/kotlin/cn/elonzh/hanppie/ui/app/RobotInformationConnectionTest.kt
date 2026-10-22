package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.robot.device.*
import kotlinx.coroutines.*
import kotlin.test.*

class RobotInformationConnectionTest {
    @Test fun currentConnectionOwnsInformationAndDisconnectClearsIt(): Unit = runBlocking {
        val services = List(2) { InformationFixture() }
        val sessions = services.map { service -> RobotSessionFixture().apply { information = service } }
        var opened = 0
        val runtime = object : RobotRuntime {
            override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = error("unused")
            override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("unused")
            override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String) = error("unused")
            override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
                onLost: (RobotSession, String) -> Unit): RobotSession = sessions[opened++]
        }
        val model = testConsoleModel(robotRuntime = runtime)
        suspend fun settled() = withTimeout(5000) { while (model.state.value.busy) delay(5) }
        try {
            model.connect("127.0.0.1", "12345678"); settled()
            assertTrue(model.robotInformation.state.value.connected)
            services[0].usage.value = RobotUsage(100, 200)
            withTimeout(3000) { while (model.robotInformation.state.value.usage == null) delay(5) }
            model.robotInformation.refresh()
            withTimeout(3000) { while (model.robotInformation.state.value.busy) delay(5) }
            assertEquals(InformationValue.Value(true), model.robotInformation.state.value.activation)
            model.disconnect(); settled(); assertEquals(RobotInformationState(), model.robotInformation.state.value)
            model.connect("127.0.0.1", "12345678"); settled()
            services[0].usage.value = RobotUsage(999, 999)
            assertNull(model.robotInformation.state.value.usage)
            assertEquals(InformationValue.Unread, model.robotInformation.state.value.activation)
        } finally { model.close() }
        assertEquals(RobotInformationState(), model.robotInformation.state.value)
    }

    @Test fun activationCommandFamilyIsRedactedEvenForMalformedFrames() {
        val model = testConsoleModel(); val secret = "activation-private-content"
        try {
            for (valid in listOf(false, true)) model.receive(DussFrame(0, 0x28, 2, 7, 0xc0, 0, 0x32, secret.encodeToByteArray(), valid))
            val logs = model.state.value.frames.toString() + model.state.value.logs
            assertTrue(logs.contains("redacted")); assertFalse(logs.contains(secret)); assertFalse(logs.contains(secret.encodeToByteArray().hex()))
        } finally { model.close() }
    }
}
