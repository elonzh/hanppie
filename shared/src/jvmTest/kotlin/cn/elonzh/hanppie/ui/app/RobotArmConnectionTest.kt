package cn.elonzh.hanppie.ui.app

import androidx.compose.ui.graphics.ImageBitmap
import cn.elonzh.hanppie.robot.extensions.arm.*
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.robot.maintenance.CalibrationKind
import cn.elonzh.hanppie.robot.maintenance.ModuleAddressingKind
import cn.elonzh.hanppie.ui.robot.arm.*
import cn.elonzh.hanppie.ui.robot.maintenance.*
import kotlinx.coroutines.*
import kotlin.test.*

class RobotArmConnectionTest {
    @Test fun configurationUsesRootAdmissionAndIsMutuallyExclusiveWithProgramsAndMaintenance(): Unit = runBlocking {
        val arm = ArmServiceFixture(); val addressing = AddressingServiceFixture(); val calibration = CalibrationServiceFixture()
        val session = RobotSessionFixture().apply { this.arm = arm; this.addressing = addressing; this.calibration = calibration }
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(session)))
        try {
            model.connect("127.0.0.1", "12345678"); waitArm { model.state.value.connected && !model.state.value.busy }
            model.robotArm.setActive(true); waitArm { arm.state.value.observation == ArmObservation.ACTIVE && !model.robotArm.state.value.busy }
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.RUNNING)
            model.robotArm.setBinding(ArmServoBinding(2, 1), ArmServoBinding(1, 2))
            delay(30); assertFalse(arm.calls.any { it.startsWith("bind:") })
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.IDLE)
            model.robotArm.setBinding(ArmServoBinding(2, 1), ArmServoBinding(1, 2))
            waitArm { arm.state.value.binding == ArmServoBinding(2, 1) && !model.state.value.busy }
            assertFalse(model.remoteEnabled.value); assertTrue(model.remoteInput.value.all { it == 0.0 })
            arm.onWrite = { awaitCancellation() }
            model.robotArm.setLoadMode(ArmLoadMode.ON_LOAD, ArmServoBinding(2, 1), 0)
            waitArm { model.state.value.busy && model.robotArm.state.value.changing }
            model.robotAddressing.begin(ModuleAddressingKind.ARMOR); model.robotCalibration.begin(CalibrationKind.GIMBAL)
            waitArm { !model.robotAddressing.state.value.busy && !model.robotCalibration.state.value.busy }
            assertFalse(addressing.calls.any { it.startsWith("begin:") }); assertFalse(calibration.calls.any { it.startsWith("begin:") })
            model.setForeground(false); waitArm { !model.state.value.busy && !model.robotArm.state.value.busy }
            assertNotNull(model.robotArm.state.value.interruptedAddress)
        } finally { model.close() }
    }
    @Test fun foregroundLossAndReconnectCannotResumeHoldsOrWriteThePreviousTarget(): Unit = runBlocking {
        val old = ArmServiceFixture(); val fresh = ArmServiceFixture()
        val sessions = listOf(old, fresh).map { source -> RobotSessionFixture().apply { arm = source } }
        val model = testConsoleModel(robotRuntime = AddressingRuntime(sessions))
        try {
            model.connect("127.0.0.1", "12345678"); waitArm { model.state.value.connected && !model.state.value.busy }
            model.robotArm.setActive(true); waitArm { !model.robotArm.state.value.busy && old.state.value.observation == ArmObservation.ACTIVE }
            model.videoFrames.publish(model.state.value.connectedAddress, ImageBitmap(2, 2))
            model.enableRemote(); waitArm { model.remoteEnabled.value && !model.state.value.busy }
            val connection = model.robotArm.state.value.connection
            val held = model.robotArm.press(ArmDirection.FORWARD); assertNotNull(held)
            model.setForeground(false); waitArm { !model.robotArm.state.value.busy }
            assertFalse(old.state.value.controlArmed); assertFalse(model.remoteEnabled.value)
            val count = old.inputs.size; model.setForeground(true); delay(70); assertEquals(count, old.inputs.size)
            model.pauseConnection(); assertTrue(sessions[0].closed)
            model.connect("127.0.0.2", "12345678"); waitArm { model.state.value.connected && !model.state.value.busy }
            model.robotArm.setActive(true); waitArm { !model.robotArm.state.value.busy }
            model.robotArm.release(held)
            model.robotArm.setBinding(ArmServoBinding.Uninstalled, ArmServoBinding(1, 2), connection)
            assertFalse(fresh.calls.any { it.startsWith("bind:") || it.startsWith("mode:") }); assertTrue(fresh.inputs.isEmpty())
            assertFalse(model.remoteEnabled.value)
        } finally { model.close() }
    }
}

private suspend fun waitArm(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }
