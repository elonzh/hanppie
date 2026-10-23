package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.product.RobotPowerAction
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.robot.device.PowerFixture
import cn.elonzh.hanppie.ui.robot.maintenance.AddressingServiceFixture
import kotlinx.coroutines.*
import kotlin.test.*

internal class AddressingRuntime(private val sessions: List<RobotSessionFixture>) : RobotRuntime {
    val targets = mutableListOf<RobotTarget>()
    override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = error("unused")
    override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("unused")
    override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String): Unit = error("unused")
    override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
        onLost: (RobotSession, String) -> Unit): RobotSession { targets += target; return sessions[targets.size - 1] }
}

class RobotAddressingConnectionTest {
    @Test fun taskAdmissionStopsInputAndBlocksProgramsPowerAndPreviewUntilExplicitEnd(): Unit = runBlocking {
        val service = AddressingServiceFixture(); val power = PowerFixture()
        val session = RobotSessionFixture().apply { addressing = service; this.power = power }
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(session)))
        suspend fun waitFor(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }
        try {
            model.connect("127.0.0.1", "12345678"); waitFor { model.state.value.connected && !model.state.value.busy }
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.RUNNING)
            model.robotAddressing.begin(ModuleAddressingKind.WHEELS)
            waitFor { !model.robotAddressing.state.value.busy }
            assertTrue(service.calls.none { it.startsWith("begin:") })
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.IDLE)
            model.remoteInput.value = listOf(0.5, 0.0, 0.0, 0.0, 0.0)
            model.robotAddressing.begin(ModuleAddressingKind.WHEELS)
            waitFor { service.calls.contains("begin:WHEELS") && !model.robotAddressing.state.value.busy && !model.state.value.busy }
            assertTrue(model.remoteInput.value.all { it == 0.0 }); assertFalse(model.remoteEnabled.value)
            model.enableRemote(); waitFor { !model.state.value.busy }; assertFalse(model.remoteEnabled.value)
            model.runScript("def start(): pass", "test", emptyList()); waitFor { !model.state.value.busy }
            assertEquals(ScriptRunPhase.IDLE, model.state.value.scriptRunPhase)
            model.robotPower.perform(RobotPowerAction.SHUTDOWN)
            waitFor { !model.robotPower.state.value.busy }; assertEquals(0, power.actions)
            val media = session.mediaStarts.get(); model.startMedia(false); delay(60); assertEquals(media, session.mediaStarts.get())
            service.state.value = service.state.value.copy(phase = AddressingPhase.VERIFYING, wheelTestReady = true)
            waitFor { model.robotAddressing.state.value.task.phase == AddressingPhase.VERIFYING }
            model.robotAddressing.holdWheel(AddressingWheel.FRONT_RIGHT)
            assertEquals(AddressingWheel.FRONT_RIGHT, service.state.value.wheelHeld)
            model.setForeground(false); assertNull(service.state.value.wheelHeld)
            model.setForeground(true); assertNull(service.state.value.wheelHeld)
            model.robotAddressing.end()
            waitFor { !model.robotAddressing.state.value.blocksControl && !model.state.value.busy }
            assertTrue(model.remoteInput.value.all { it == 0.0 }); assertFalse(model.remoteEnabled.value)
            model.enableRemote(); waitFor { model.remoteEnabled.value && !model.state.value.busy }
        } finally { model.close() }
    }

    @Test fun pauseRetainsTheTaskOriginAndReconnectOnlyOffersExplicitTermination(): Unit = runBlocking {
        val old = AddressingServiceFixture().apply { onBegin = { awaitCancellation() } }
        val fresh = AddressingServiceFixture()
        val sessions = listOf(old, fresh).map { source -> RobotSessionFixture().apply { addressing = source } }
        val runtime = AddressingRuntime(sessions)
        val model = testConsoleModel(robotRuntime = runtime)
        suspend fun waitFor(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }
        try {
            model.connect("127.0.0.1", "12345678"); waitFor { model.state.value.connected && !model.state.value.busy }
            model.robotAddressing.begin(ModuleAddressingKind.SERVO); waitFor { old.calls.contains("begin:SERVO") }
            model.pauseConnection()
            assertTrue(sessions[0].closed); assertFalse(model.robotAddressing.state.value.busy)
            assertFalse(model.robotAddressing.state.value.connected)
            assertEquals(ModuleAddressingKind.SERVO, model.robotAddressing.state.value.recovery?.kind)
            model.reconnectAddressing()
            waitFor { model.state.value.connected && !model.state.value.busy }
            assertEquals(runtime.targets[0], runtime.targets[1]); assertTrue(model.robotAddressing.state.value.canRecover)
            assertTrue(fresh.calls.none { it.startsWith("begin:") || it.startsWith("assign:") || it.startsWith("recover:") })
            model.enableRemote(); waitFor { !model.state.value.busy }; assertFalse(model.remoteEnabled.value)
            model.robotAddressing.recover()
            waitFor { model.robotAddressing.state.value.recovery == null && !model.robotAddressing.state.value.busy && !model.state.value.busy }
            assertEquals(listOf("recover:SERVO"), fresh.calls.filterNot { it.startsWith("hold:") })
            assertFalse(model.remoteEnabled.value); assertTrue(model.remoteInput.value.all { it == 0.0 })
        } finally { model.close() }
    }
}
