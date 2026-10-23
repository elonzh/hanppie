package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.maintenance.firmware.*
import cn.elonzh.hanppie.robot.product.RobotPowerAction
import cn.elonzh.hanppie.ui.robot.device.PowerFixture
import cn.elonzh.hanppie.ui.robot.maintenance.*
import kotlinx.coroutines.*
import kotlinx.io.Buffer
import kotlin.test.*

class RobotFirmwareConnectionTest {
    @Test fun installAdmissionOwnsControlAndPreventsOtherDeviceTasksUntilReadback(): Unit = runBlocking {
        val firmware = FirmwareServiceFixture(); val power = PowerFixture()
        val addressing = AddressingServiceFixture(); val calibration = CalibrationServiceFixture()
        val session = RobotSessionFixture().apply {
            this.firmware = firmware; this.power = power; this.addressing = addressing; this.calibration = calibration
        }
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(session)))
        suspend fun waitFor(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }
        try {
            model.connect("127.0.0.1", "12345678"); waitFor { model.state.value.connected && !model.state.value.busy }
            model.robotFirmware.refresh(); waitFor { model.robotFirmware.state.value.task.configuration != null && !model.robotFirmware.state.value.busy }
            val bytes = firmwareUiArchive()
            model.robotFirmware.selectPackage("local.tar", { bytes.size.toLong() }) { Buffer().apply { write(bytes) } }; ready(model.robotFirmware)
            val selected = model.robotFirmware.state.value.file!!
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.RUNNING)
            model.robotFirmware.install(FirmwareInstallConsent(), firmware.configuration, selected.token)
            waitFor { !model.robotFirmware.state.value.busy }; assertFalse("install" in firmware.calls)
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.IDLE)
            model.robotFiles.state.value = model.robotFiles.state.value.copy(busy = true)
            model.robotFirmware.install(FirmwareInstallConsent(), firmware.configuration, selected.token)
            waitFor { !model.robotFirmware.state.value.busy }; assertFalse("install" in firmware.calls)
            model.robotFiles.state.value = model.robotFiles.state.value.copy(busy = false)
            model.remoteInput.value = listOf(0.5, 0.0, 0.0, 0.0, 0.0)
            model.robotFirmware.install(FirmwareInstallConsent(), firmware.configuration, selected.token)
            waitFor { firmware.state.value.phase == FirmwarePhase.INSTALLING && !model.state.value.busy }
            assertFalse(model.remoteEnabled.value); assertTrue(model.remoteInput.value.all { it == 0.0 })
            model.robotFiles.refresh(); assertNotNull(model.robotFiles.state.value.error); assertFalse(model.robotFiles.state.value.busy)
            model.enableRemote(); waitFor { !model.state.value.busy }; assertFalse(model.remoteEnabled.value)
            model.runScript("def start(): pass", "test", emptyList()); waitFor { !model.state.value.busy }
            assertEquals(ScriptRunPhase.IDLE, model.state.value.scriptRunPhase)
            model.robotPower.perform(RobotPowerAction.SHUTDOWN); waitFor { !model.robotPower.state.value.busy }; assertEquals(0, power.actions)
            model.robotAddressing.begin(ModuleAddressingKind.WHEELS); waitFor { !model.robotAddressing.state.value.busy }
            model.robotCalibration.begin(CalibrationKind.GIMBAL); waitFor { !model.robotCalibration.state.value.busy }
            assertTrue(addressing.calls.none { it.startsWith("begin:") }); assertTrue(calibration.calls.none { it.startsWith("begin:") })
            val starts = session.mediaStarts.get(); model.startMedia(false); delay(60); assertEquals(starts, session.mediaStarts.get())
            firmware.state.value = firmware.state.value.copy(phase = FirmwarePhase.VERIFIED, loaderMayBeActive = false)
            waitFor { !model.robotFirmware.state.value.blocksControl }
            assertFalse(model.remoteEnabled.value); assertTrue(model.remoteInput.value.all { it == 0.0 })
            model.robotFiles.state.value = model.robotFiles.state.value.copy(busy = true)
            firmware.state.value = firmware.state.value.copy(phase = FirmwarePhase.INSTALLING, loaderMayBeActive = true)
            waitFor { model.robotFirmware.state.value.blocksControl && !model.robotFiles.state.value.busy }
        } finally { model.close() }
    }

    @Test fun reconnectKeepsOriginalTargetAndNeverReplaysInstallation(): Unit = runBlocking {
        val old = FirmwareServiceFixture(); val fresh = FirmwareServiceFixture()
        val sessions = listOf(old, fresh).map { source -> RobotSessionFixture().apply { firmware = source; addressing = AddressingServiceFixture() } }
        val runtime = AddressingRuntime(sessions); val model = testConsoleModel(robotRuntime = runtime)
        suspend fun waitFor(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }
        try {
            model.connect("127.0.0.1", "12345678"); waitFor { model.state.value.connected && !model.state.value.busy }
            old.state.value = old.state.value.copy(phase = FirmwarePhase.INSTALLING, loaderMayBeActive = true)
            waitFor { model.robotFirmware.state.value.blocksControl }
            model.pauseConnection()
            assertTrue(sessions[0].closed); assertNotNull(model.robotFirmware.state.value.recovery)
            model.reconnectFirmware(); waitFor { model.state.value.connected && !model.state.value.busy }
            assertEquals(runtime.targets[0], runtime.targets[1]); assertTrue(fresh.calls.isEmpty())
            model.enableRemote(); waitFor { !model.state.value.busy }; assertFalse(model.remoteEnabled.value)
            model.robotAddressing.begin(ModuleAddressingKind.ARMOR); waitFor { !model.robotAddressing.state.value.busy }
            assertTrue((sessions[1].addressing as AddressingServiceFixture).calls.none { it.startsWith("begin:") })
            model.robotFirmware.observePrevious(); waitFor { "observe" in fresh.calls && !model.state.value.busy }
            model.robotFirmware.refresh(); waitFor { model.robotFirmware.state.value.task.configuration != null && !model.state.value.busy }
            model.robotFirmware.confirmRestarted(); waitFor { model.robotFirmware.state.value.recovery == null && !model.state.value.busy }
            assertEquals(listOf("observe", "refresh", "refresh", "confirm-restart"), fresh.calls)
            assertFalse(model.remoteEnabled.value); assertTrue(model.remoteInput.value.all { it == 0.0 })
        } finally { model.close() }
    }
}
