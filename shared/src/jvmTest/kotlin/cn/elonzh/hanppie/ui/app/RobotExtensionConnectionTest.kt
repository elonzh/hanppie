package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.extensions.*
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.ui.robot.extensions.*
import cn.elonzh.hanppie.ui.robot.maintenance.*
import kotlinx.coroutines.*
import kotlin.test.*

class RobotExtensionConnectionTest {
    @Test fun identificationUsesRootAdmissionAndForegroundRecoveryResumesOnlyObservation(): Unit = runBlocking {
        val service = ExtensionServiceFixture(); val addressing = AddressingServiceFixture(); val calibration = CalibrationServiceFixture()
        val session = extensionSession(service).apply { this.addressing = addressing; this.calibration = calibration }
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(session)))
        try {
            model.connect("127.0.0.1", "12345678"); waitExtensions { model.state.value.connected && !model.state.value.busy }
            model.robotExtensions.setActive(true); model.robotExtensions.select(RobotComponent.SERVO_1)
            waitExtensions { service.state.value.observation == ExtensionObservation.ACTIVE && !model.robotExtensions.state.value.busy }
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.RUNNING)
            model.robotExtensions.identify(); delay(30); assertTrue(service.calls.none { it.startsWith("identify:") })
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.IDLE)
            service.onIdentify = { awaitCancellation() }; model.robotExtensions.identify()
            waitExtensions { model.state.value.busy && model.robotExtensions.state.value.changing }
            model.robotAddressing.begin(ModuleAddressingKind.ARMOR); model.robotCalibration.begin(CalibrationKind.GIMBAL)
            waitExtensions { !model.robotAddressing.state.value.busy && !model.robotCalibration.state.value.busy }
            assertTrue(addressing.calls.none { it.startsWith("begin:") }); assertTrue(calibration.calls.none { it.startsWith("begin:") })
            model.setForeground(false); waitExtensions { !model.state.value.busy && !model.robotExtensions.state.value.busy }
            assertNull(service.state.value.identifying); assertEquals(ExtensionObservation.OFF, service.state.value.observation)
            val lamps = service.calls.count { it.startsWith("identify:") }
            model.setForeground(true); waitExtensions { service.state.value.observation == ExtensionObservation.ACTIVE }
            assertEquals(lamps, service.calls.count { it.startsWith("identify:") }); assertTrue(service.state.value.readings.isEmpty())
            assertFalse(model.remoteEnabled.value)
        } finally { model.close() }
    }
    @Test fun reconnectDropsSelectionAndOldUiConnectionCannotWriteToTheNewService(): Unit = runBlocking {
        val old = ExtensionServiceFixture(); val fresh = ExtensionServiceFixture()
        val sessions = listOf(old, fresh).map(::extensionSession)
        val model = testConsoleModel(robotRuntime = AddressingRuntime(sessions))
        try {
            model.connect("127.0.0.1", "12345678"); waitExtensions { model.state.value.connected && !model.state.value.busy }
            model.robotExtensions.setActive(true); model.robotExtensions.select(RobotComponent.SERVO_4)
            waitExtensions { old.state.value.observation == ExtensionObservation.ACTIVE && !model.robotExtensions.state.value.busy }
            model.robotExtensions.identify(); waitExtensions { old.state.value.identifying != null && !model.state.value.busy }
            val previous = model.robotExtensions.state.value.connection
            model.pauseConnection(); assertTrue(sessions[0].closed)
            model.connect("127.0.0.2", "12345678"); waitExtensions { model.state.value.connected && !model.state.value.busy }
            model.robotExtensions.setActive(true)
            assertNull(model.robotExtensions.state.value.selected)
            model.robotExtensions.select(RobotComponent.SERVO_4, previous); model.robotExtensions.identify(previous)
            assertTrue(fresh.calls.isEmpty()); assertNull(old.state.value.identifying)
            model.robotExtensions.select(RobotComponent.SERVO_1)
            waitExtensions { fresh.state.value.observation == ExtensionObservation.ACTIVE }
            assertTrue(fresh.calls.none { it.startsWith("identify:") })
        } finally { model.close() }
    }
}

private suspend fun waitExtensions(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }
