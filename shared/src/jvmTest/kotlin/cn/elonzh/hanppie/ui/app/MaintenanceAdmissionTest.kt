package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.extensions.arm.*
import cn.elonzh.hanppie.robot.extensions.ExtensionObservation
import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.ui.robot.arm.ArmServiceFixture
import cn.elonzh.hanppie.ui.robot.extensions.ExtensionServiceFixture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlin.test.*

/** A competing UI intent is visible before root admission runs on its coroutine. */
class MaintenanceAdmissionTest {
    @Test fun pendingAddressingCannotRevokeAnAdmittedArmWrite() = verify(true, Pending.ADDRESSING)
    @Test fun pendingCalibrationCannotRevokeAnAdmittedArmWrite() = verify(true, Pending.CALIBRATION)
    @Test fun pendingFirmwareCannotRevokeAnAdmittedArmWrite() = verify(true, Pending.FIRMWARE)
    @Test fun pendingAddressingCannotRevokeAnAdmittedIdentification() = verify(false, Pending.ADDRESSING)
    @Test fun pendingCalibrationCannotRevokeAnAdmittedIdentification() = verify(false, Pending.CALIBRATION)
    @Test fun pendingFirmwareCannotRevokeAnAdmittedIdentification() = verify(false, Pending.FIRMWARE)

    private enum class Pending {
        ADDRESSING, CALIBRATION, FIRMWARE;

        fun setWorking(model: ConsoleModel, working: Boolean) = when (this) {
            ADDRESSING -> model.robotAddressing.state.value = model.robotAddressing.state.value.copy(working = working)
            CALIBRATION -> model.robotCalibration.state.value = model.robotCalibration.state.value.copy(working = working)
            FIRMWARE -> model.robotFirmware.state.value = model.robotFirmware.state.value.copy(working = working)
        }
    }

    private fun verify(armWrite: Boolean, pending: Pending): Unit = runBlocking {
        val arm = ArmServiceFixture()
        val extension = ExtensionServiceFixture()
        val session = RobotSessionFixture().apply { this.arm = arm; extensions = extension }
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(session)))
        val admitted = CompletableDeferred<Unit>()
        val prepareMayFinish = CountDownLatch(1)
        val stopAtPreparation = AtomicBoolean(false)
        val actionEntered = CompletableDeferred<Unit>()
        val actionMayFinish = CompletableDeferred<Unit>()
        session.safetyStopAction = {
            if (stopAtPreparation.compareAndSet(true, false)) {
                admitted.complete(Unit)
                check(prepareMayFinish.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            model.connect("127.0.0.1", "12345678")
            waitFor { model.state.value.connected && !model.state.value.busy }
            if (armWrite) {
                model.robotArm.setActive(true)
                waitFor { !model.robotArm.state.value.busy && arm.state.value.observation == ArmObservation.ACTIVE &&
                    arm.state.value.binding != null }
                arm.onWrite = { actionEntered.complete(Unit); actionMayFinish.await() }
            } else {
                model.robotExtensions.setActive(true)
                model.robotExtensions.select(RobotComponent.SERVO_1)
                waitFor { !model.robotExtensions.state.value.busy && extension.state.value.observation == ExtensionObservation.ACTIVE }
                extension.onIdentify = { actionEntered.complete(Unit); actionMayFinish.await() }
            }
            stopAtPreparation.set(true)
            if (armWrite) model.robotArm.setLoadMode(ArmLoadMode.ON_LOAD, ArmServoBinding(1, 2), 0)
            else model.robotExtensions.identify()
            withTimeout(5_000) { admitted.await() }
            // Hold the root preparation boundary while the other controller publishes its unadmitted intent.
            pending.setWorking(model, true)
            prepareMayFinish.countDown()
            waitFor { actionEntered.isCompleted || !model.state.value.busy }
            assertTrue(actionEntered.isCompleted, "$pending must not revoke an admitted ${if (armWrite) "arm write" else "identification"}")
            assertTrue(model.state.value.busy)
            pending.setWorking(model, false)
            actionMayFinish.complete(Unit)
            waitFor { !model.state.value.busy }
            if (armWrite) assertTrue(arm.state.value.loadModeAcknowledged == true)
            else assertEquals(RobotComponent.SERVO_1, extension.state.value.identifying)
        } finally {
            pending.setWorking(model, false)
            stopAtPreparation.set(false)
            prepareMayFinish.countDown()
            actionMayFinish.complete(Unit)
            model.close()
        }
    }

    private suspend fun waitFor(check: () -> Boolean) = withTimeout(5_000) {
        while (!check()) delay(5)
    }
}
