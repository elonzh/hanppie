package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.extensions.*
import cn.elonzh.hanppie.robot.maintenance.ModuleAddressingKind
import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import kotlin.test.*

class RobotExtensionSessionTest {
    @Test fun realUdpRequestsSelectFamiliesDecodeReportsAndIdentifyTheActualModuleNode(): Unit = runBlocking {
        ExtensionLoopback().use { peer ->
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = peer.port)).use { session ->
                session.connect(); awaitExtension { session.extensions.state.value.online?.size == 4 }
                assertEquals(ExtensionObservation.OFF, session.extensions.state.value.observation)
                assertTrue(peer.frames.none { it.set == 0x48 && it.id == 3 && it.payload.u8(1) in 17..19 })
                session.extensions.setObserving(ExtensionKind.SERVO)
                awaitExtension { session.extensions.state.value.readings[RobotComponent.SERVO_1] != null }
                assertEquals(ServoReading(RobotComponent.SERVO_1, true, -42, 1024), session.extensions.state.value.readings[RobotComponent.SERVO_1])
                session.extensions.setObserving(ExtensionKind.TOF)
                awaitExtension { session.extensions.state.value.readings[RobotComponent.TOF_1] != null }
                assertEquals(TofReading(RobotComponent.TOF_1, 1, 0, 1, 1234), session.extensions.state.value.readings[RobotComponent.TOF_1])
                session.extensions.setObserving(ExtensionKind.ADAPTER)
                awaitExtension { session.extensions.state.value.readings[RobotComponent.SENSOR_ADAPTER_6] != null }
                assertEquals(AdapterReading(RobotComponent.SENSOR_ADAPTER_6, 1, 0, 1023, 65535), session.extensions.state.value.readings[RobotComponent.SENSOR_ADAPTER_6])
                assertEquals(listOf("0211000001e759005f090002000a00", "02120000015ac0e486090002000a00", "0213000001fc9fbbee090002000a00"),
                    peer.frames.filter { it.receiver == 9 && it.set == 0x48 && it.id == 3 && it.payload.u8(1) in 17..19 }.map { it.payload.hex() })
                session.extensions.identify(RobotComponent.SENSOR_ADAPTER_6)
                assertTrue(peer.frames.any { it.receiver == 0xd6 && it.attr == 0x40 && it.set == 0 && it.id == 0x60 && it.payload.hex() == "ff0200ff00" })
                session.safetyStop(); awaitExtension { peer.frames.any { it.receiver == 0xd6 && it.set == 0 && it.id == 0x60 && it.payload.hex() == "ff0100ff00" } }
                session.extensions.setObserving(null); assertTrue(session.extensions.state.value.readings.isEmpty())
                session.close(); assertEquals(ExtensionObservation.CLOSED, session.extensions.state.value.observation)
            }
        }
    }
    @Test fun maintenanceLabAndFirmwareOwnershipPreventNewRequestsAndRetireOldObservation(): Unit = runBlocking {
        ExtensionLoopback().use { peer ->
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = peer.port)).use { session ->
                session.connect(); awaitExtension { session.extensions.state.value.online != null }
                session.addressing.begin(ModuleAddressingKind.ARMOR)
                assertFailsWith<IllegalStateException> { session.extensions.setObserving(ExtensionKind.TOF) }
                assertFailsWith<IllegalStateException> { session.extensions.identify(RobotComponent.TOF_1) }
                session.addressing.end(); session.extensions.setObserving(ExtensionKind.TOF)
                session.labMode(true); assertEquals(ExtensionObservation.OFF, session.extensions.state.value.observation)
                assertFailsWith<IllegalStateException> { session.extensions.setObserving(ExtensionKind.TOF) }
                session.normalMode(); delay(40); assertTrue(session.extensions.state.value.readings.isEmpty())
                session.extensions.setObserving(ExtensionKind.SERVO); peer.upgrade.set(true)
                awaitExtension { session.firmware.state.value.blocksControl && session.extensions.state.value.observation == ExtensionObservation.OFF }
                assertFailsWith<IllegalStateException> { session.extensions.setObserving(ExtensionKind.SERVO) }
                assertFailsWith<IllegalStateException> { session.extensions.identify(RobotComponent.SERVO_1) }
                assertTrue(session.extensions.state.value.readings.isEmpty())
            }
        }
    }
}

private suspend fun awaitExtension(check: () -> Boolean) = withTimeout(3_000) { while (!check()) delay(5) }
