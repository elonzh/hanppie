package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.extensions.arm.*
import cn.elonzh.hanppie.robot.maintenance.ModuleAddressingKind
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import kotlin.test.*

class RobotArmSessionTest {
    @Test fun directControlUsesTheRealRemoteSessionAndStoppingOrTakeoverRetiresItsGesture(): Unit = runBlocking {
        ExtensionLoopback().use { device ->
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = device.port)).use { session ->
                session.connect()
                awaitArm { session.health.value.modules != null }
                session.arm.readBinding(); session.arm.setObserving(true)
                session.enterRemote(); awaitArm { session.arm.state.value.positionFresh && session.arm.state.value.clawFresh }
                val lease = session.arm.beginControl(); session.arm.move(lease, 1.0, -1.0, 0.5)
                awaitArm { device.controls.any { it.payload.hex() == "0f0094066c0100044a05" } }
                assertEquals(0, device.controls.last().attr); assertEquals(3, device.controls.last().receiver)
                val before = device.controls.size; session.arm.stopControl()
                awaitArm { device.controls.drop(before).any { it.payload.hex() == "00000004000400040004" } }
                assertFailsWith<ArmOperationException> { session.arm.move(lease, 1.0, 0.0, 0.0) }
                assertFailsWith<IllegalStateException> { session.arm.setBinding(ArmServoBinding(2, 1), ArmServoBinding(1, 2)) }
                assertFalse(device.frames.any { it.set == 0x33 && it.id == 0x67 })
                val next = session.arm.beginControl(); session.arm.move(next, 0.0, 0.0, -1.0)
                awaitArm { device.controls.any { it.payload.hex() == "08000004000400046c01" } }
                device.upgrade.set(true)
                awaitArm { session.firmware.state.value.blocksControl && !session.arm.state.value.controlArmed }
                assertFailsWith<IllegalStateException> { session.arm.move(next, 0.0, 0.0, -1.0) }
                assertFailsWith<IllegalStateException> { session.arm.setObserving(true) }
                assertFailsWith<IllegalStateException> { session.arm.readBinding() }
                session.close()
                assertEquals(ArmObservation.CLOSED, session.arm.state.value.observation)
            }
        }
    }
    @Test fun configurationRoundTripsAndModeReportsStaySeparateFromMaintenanceAndLabOwnership(): Unit = runBlocking {
        ExtensionLoopback().use { device ->
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = device.port)).use { session ->
                session.connect(); awaitArm { session.health.value.modules != null }
                session.arm.readBinding()
                session.arm.setBinding(ArmServoBinding(2, 1), ArmServoBinding(1, 2))
                assertEquals(ArmServoBinding(2, 1), session.arm.state.value.binding)
                assertTrue(session.arm.state.value.bindingAcknowledged == true)
                assertTrue(session.arm.state.value.bindingMatchesRequest == true)
                val loadBefore = session.arm.state.value.loadModeRaw
                session.arm.setLoadMode(ArmLoadMode.ON_LOAD, ArmServoBinding(2, 1), loadBefore)
                awaitArm { session.arm.state.value.loadModeMatchesRequest == true }
                assertTrue(session.arm.state.value.loadModeAcknowledged == true)
                assertEquals(1, session.arm.state.value.loadModeRaw); assertEquals(0x3b, session.arm.state.value.loadModeSender)
                session.safetyStop(); assertEquals(1, session.arm.state.value.loadModeRaw)
                assertEquals(1, device.frames.count { it.set == 0x33 && it.id == 0x21 })
                session.addressing.begin(ModuleAddressingKind.ARMOR)
                assertFailsWith<IllegalStateException> { session.arm.setObserving(true) }
                assertFailsWith<IllegalStateException> { session.arm.readBinding() }
                assertFailsWith<IllegalStateException> { session.arm.setLoadMode(ArmLoadMode.FREE, ArmServoBinding(2, 1), 1) }
                session.addressing.end()
                session.labMode(true)
                assertFailsWith<IllegalStateException> { session.arm.setObserving(true) }
                assertFailsWith<IllegalStateException> { session.arm.readBinding() }
                session.normalMode()
                session.arm.setBinding(ArmServoBinding.Uninstalled, ArmServoBinding(2, 1))
                assertEquals(ArmServoBinding.Uninstalled, session.arm.state.value.binding)
                assertEquals(listOf("020100", "ffff00"), device.frames.filter { it.set == 0x33 && it.id == 0x67 }.map { it.payload.hex() })
            }
        }
    }
}

private suspend fun awaitArm(check: () -> Boolean) = withTimeout(3_000) { while (!check()) delay(5) }
