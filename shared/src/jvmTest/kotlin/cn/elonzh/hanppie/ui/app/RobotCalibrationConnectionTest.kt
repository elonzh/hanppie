package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.product.RobotPowerAction
import cn.elonzh.hanppie.ui.robot.device.PowerFixture
import cn.elonzh.hanppie.ui.robot.maintenance.*
import kotlinx.coroutines.*
import kotlin.test.*

class RobotCalibrationConnectionTest {
    @Test fun calibrationAdmissionOwnsControlsAndForegroundLossPausesOnlyTheAutomaticNextStage(): Unit = runBlocking {
        val service = CalibrationServiceFixture(); val power = PowerFixture(); val addressing = AddressingServiceFixture()
        val session = RobotSessionFixture().apply { calibration = service; this.power = power; this.addressing = addressing }
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(session)))
        suspend fun waitFor(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }
        try {
            model.connect("127.0.0.1", "12345678"); waitFor { model.state.value.connected && !model.state.value.busy }
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.RUNNING)
            model.robotCalibration.begin(CalibrationKind.CHASSIS_AND_GIMBAL); waitFor { !model.robotCalibration.state.value.busy }
            assertTrue(service.calls.isEmpty())
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.IDLE)
            model.remoteInput.value = listOf(0.5, 0.0, 0.0, 0.0, 0.0)
            model.robotCalibration.begin(CalibrationKind.CHASSIS_AND_GIMBAL)
            waitFor { service.state.value.phase == CalibrationPhase.CHASSIS && !model.state.value.busy }
            assertTrue(model.remoteInput.value.all { it == 0.0 }); assertFalse(model.remoteEnabled.value)
            model.enableRemote(); waitFor { !model.state.value.busy }; assertFalse(model.remoteEnabled.value)
            model.runScript("def start(): pass", "test", emptyList()); waitFor { !model.state.value.busy }
            assertEquals(ScriptRunPhase.IDLE, model.state.value.scriptRunPhase)
            model.robotPower.perform(RobotPowerAction.SHUTDOWN); waitFor { !model.robotPower.state.value.busy }; assertEquals(0, power.actions)
            model.robotAddressing.begin(ModuleAddressingKind.WHEELS); waitFor { !model.robotAddressing.state.value.busy }; assertTrue(addressing.calls.none { it.startsWith("begin:") })
            val starts = session.mediaStarts.get(); model.startMedia(false); delay(60); assertEquals(starts, session.mediaStarts.get())
            model.setForeground(false)
            service.state.value = service.state.value.copy(phase = CalibrationPhase.WAITING_LEVEL, levelReady = true)
            model.setForeground(true); delay(60); assertTrue(service.calls.none { it == "gimbal" })
            model.robotCalibration.continueGimbal()
            waitFor { service.calls.contains("gimbal") && !model.state.value.busy }
            assertFalse(model.remoteEnabled.value)
            service.state.value = service.state.value.copy(phase = CalibrationPhase.SUCCEEDED)
            waitFor { !model.robotCalibration.state.value.blocksControl }
            assertFalse(model.remoteEnabled.value); assertTrue(model.remoteInput.value.all { it == 0.0 })
        } finally { model.close() }
    }

    @Test fun reconnectRetainsTheOriginalStageAndOnlyExplicitObservationCanResolveIt(): Unit = runBlocking {
        val old = CalibrationServiceFixture(); val fresh = CalibrationServiceFixture()
        val sessions = listOf(old, fresh).map { source -> RobotSessionFixture().apply { calibration = source; addressing = AddressingServiceFixture() } }
        val runtime = AddressingRuntime(sessions); val model = testConsoleModel(robotRuntime = runtime)
        suspend fun waitFor(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }
        try {
            model.connect("127.0.0.1", "12345678"); waitFor { model.state.value.connected && !model.state.value.busy }
            model.robotCalibration.begin(CalibrationKind.GIMBAL); waitFor { old.state.value.phase == CalibrationPhase.GIMBAL && !model.state.value.busy }
            model.pauseConnection()
            assertTrue(sessions[0].closed); assertFalse(model.robotCalibration.state.value.busy)
            assertTrue(model.robotCalibration.state.value.recovery?.previous?.gimbalMayBeActive == true)
            model.reconnectCalibration(); waitFor { model.state.value.connected && !model.state.value.busy }
            assertEquals(runtime.targets[0], runtime.targets[1]); assertTrue(fresh.calls.isEmpty())
            model.robotAddressing.begin(ModuleAddressingKind.ARMOR); waitFor { !model.robotAddressing.state.value.busy }
            assertTrue((sessions[1].addressing as AddressingServiceFixture).calls.none { it.startsWith("begin:") })
            model.enableRemote(); waitFor { !model.state.value.busy }; assertFalse(model.remoteEnabled.value)
            model.robotCalibration.observePrevious()
            waitFor { fresh.calls.contains("observe") && !model.state.value.busy }
            assertEquals(listOf("observe"), fresh.calls); assertNotNull(model.robotCalibration.state.value.recovery)
            model.robotCalibration.confirmRestarted()
            waitFor { model.robotCalibration.state.value.recovery == null && !model.state.value.busy }
            assertEquals(listOf("observe", "restart-confirmed"), fresh.calls)
            assertFalse(model.remoteEnabled.value)
        } finally { model.close() }
    }
}
