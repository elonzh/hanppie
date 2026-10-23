package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.robot.lab.ui.*
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class LabUiBindingTest {
    private val owner = "0123456789abcdef0123456789abcdef"
    private val input = LabWidgetKey(LabWidgetType.INPUT, 0)
    private class Runtime(private vararg val sessions: RobotSession) : RobotRuntime {
        private var index = 0
        override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = emptyList()
        override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("unused")
        override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String) {}
        override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
            onLost: (RobotSession, String) -> Unit) = sessions[index++]
    }
    private suspend fun ConsoleModel.connected() {
        connect("127.0.0.1", "12345678")
        withTimeout(5_000) { while (!state.value.connected || state.value.busy) delay(5) }
    }
    private suspend fun ConsoleModel.bound(run: String?) {
        withTimeout(5_000) { while (labUi.value.runId != run) delay(5) }
    }

    @Test fun backgroundBusyAndOldRunCannotDispatchProgramInput(): Unit = runBlocking {
        val session = RobotSessionFixture()
        val model = testConsoleModel(robotRuntime = Runtime(session))
        try {
            model.connected()
            session.labUiState.value = LabUiState(runId = owner, running = true, inputEnabled = true)
            model.bound(owner)
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.RUNNING)
            model.setLabUiVisible(owner, true)
            model.labUiInteract(owner, input, 1, 0, LabUiValue.Text("private"))
            assertEquals(1, session.labUiEvents.size)
            model.setForeground(false)
            assertEquals(owner to false, session.labUiVisibility.last())
            model.labUiInteract(owner, input, 1, 0, LabUiValue.Text("background"))
            assertEquals(1, session.labUiEvents.size)
            model.setForeground(true)
            model.state.value = model.state.value.copy(busy = true)
            model.labUiInteract(owner, input, 1, 0, LabUiValue.Text("busy"))
            model.state.value = model.state.value.copy(busy = false)
            model.labUiInteract("f".repeat(32), input, 1, 0, LabUiValue.Text("stale"))
            assertEquals(1, session.labUiEvents.size)
        } finally { model.close() }
    }

    @Test fun disconnectAndReplacementRetireTheOldObserverAndVisibility(): Unit = runBlocking {
        val first = RobotSessionFixture(); val second = RobotSessionFixture()
        val model = testConsoleModel(robotRuntime = Runtime(first, second))
        try {
            model.connected(); first.labUiState.value = LabUiState(runId = owner)
            model.bound(owner); model.setLabUiVisible(owner, true)
            model.disconnect()
            withTimeout(5_000) { while (model.state.value.connected || model.state.value.busy) delay(5) }
            assertNull(model.labUi.value.runId)
            assertEquals(owner to false, first.labUiVisibility.last())
            model.connected()
            val next = "f".repeat(32)
            second.labUiState.value = LabUiState(runId = next); model.bound(next)
            first.labUiState.value = LabUiState(runId = owner, issue = LabUiIssue.SEND_FAILED)
            delay(30)
            assertEquals(next, model.labUi.value.runId)
        } finally { model.close() }
    }

    @Test fun failedConnectionDoesNotRetainAProgramSurface(): Unit = runBlocking {
        val fixture = RobotSessionFixture()
        val failed = object : RobotSession by fixture { override suspend fun connect(): Unit = error("fixture connect failure") }
        val model = testConsoleModel(robotRuntime = Runtime(failed))
        try {
            model.connect("127.0.0.1", "12345678")
            withTimeout(5_000) { while (model.state.value.error == null || model.state.value.busy) delay(5) }
            fixture.labUiState.value = LabUiState(runId = owner)
            delay(30)
            assertNull(model.labUi.value.runId)
        } finally { model.close() }
    }

    @Test fun protocolDiagnosticsNeverContainAuthoredTextOrInput() {
        val model = testConsoleModel()
        try {
            for (id in listOf(0xba, 0xbb)) model.receive(DussFrame(0, 0xc9, 2, 5, 0x40, 0x3f, id,
                "sensitive-input".encodeToByteArray(), true))
            assertTrue(model.state.value.frames.any { it.contains("program-ui") })
            assertFalse((model.state.value.frames + model.state.value.logs).any {
                it.contains("sensitive-input") || it.contains("73656e7369746976652d696e707574")
            })
        } finally { model.close() }
    }
}
