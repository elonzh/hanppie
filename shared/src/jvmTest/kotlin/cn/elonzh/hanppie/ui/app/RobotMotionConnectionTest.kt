package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.ui.robot.remote.MotionFixture
import kotlinx.coroutines.*
import kotlin.test.*

class RobotMotionConnectionTest {
    @Test fun rootAdmissionStopsInputAndRejectsActiveProgramsWithoutDispatch(): Unit = runBlocking {
        val peer = MotionFixture(); val session = RobotSessionFixture().apply { motion = peer }
        val model = testConsoleModel(robotRuntime = powerRuntime(listOf(session)))
        suspend fun settled() = withTimeout(5_000) { while (model.state.value.busy || model.robotMotion.state.value.busy) delay(5) }
        try {
            model.connect("127.0.0.1", "12345678"); settled()
            model.remoteInput.value = listOf(.4, .1, 0.0, 0.0, 0.0)
            model.robotMotion.refresh(); settled()
            assertTrue(model.remoteInput.value.all { it == 0.0 })
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.RUNNING)
            model.robotMotion.setSpeedLevel(4); settled(); assertEquals(0, peer.writes)
            assertNotNull(model.robotMotion.state.value.error)
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.IDLE)
            model.robotMotion.setSpeedLevel(4); settled()
            assertEquals(1, peer.writes); assertTrue(model.robotMotion.state.value.confirmed)
            model.setForeground(false); model.robotMotion.setSpeedLevel(1); settled()
            assertEquals(1, peer.writes)
        } finally { model.close() }
    }

    @Test fun connectionReplacementAndPauseRetireOldParametersAndKeepDispatchedUnknown(): Unit = runBlocking {
        val peers = List(2) { MotionFixture() }; val sessions = peers.map { RobotSessionFixture().apply { motion = it } }
        val model = testConsoleModel(robotRuntime = powerRuntime(sessions))
        val gate = CompletableDeferred<Unit>()
        suspend fun waitFor(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }
        try {
            model.connect("127.0.0.1", "12345678"); waitFor { model.state.value.connected && !model.state.value.busy }
            model.robotMotion.refresh(); waitFor { model.robotMotion.state.value.configuration != null && !model.state.value.busy }
            peers[0].write = { dispatched -> dispatched(); gate.await() }
            model.robotMotion.setSpeedLevel(4); waitFor { peers[0].writes == 1 }
            model.pauseConnection()
            assertFalse(model.robotMotion.state.value.connected); assertTrue(model.robotMotion.state.value.uncertain)
            assertNull(model.robotMotion.state.value.configuration); assertFalse(model.robotMotion.state.value.busy)
            model.connect("127.0.0.1", "12345678"); waitFor { model.state.value.connected && !model.state.value.busy }
            gate.complete(Unit)
            assertFalse(model.robotMotion.state.value.uncertain); assertNull(model.robotMotion.state.value.configuration)
            assertEquals(0, peers[1].writes)
        } finally { model.close() }
    }
}
