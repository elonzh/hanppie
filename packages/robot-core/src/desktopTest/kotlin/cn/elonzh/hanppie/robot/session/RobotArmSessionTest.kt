package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.extensions.arm.*
import cn.elonzh.hanppie.robot.maintenance.ModuleAddressingKind
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class RobotArmSessionTest {
    @Test fun directControlUsesTheRealRemoteSessionAndStoppingOrTakeoverRetiresItsGesture(): Unit = runBlocking {
        ArmLoopback().use { device ->
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
        ArmLoopback().use { device ->
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

/** A synthetic UDP peer exercises real envelope/request routing; it is not a robot emulator. */
private class ArmLoopback : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
    val port = socket.localPort
    private val running = AtomicBoolean(true)
    val upgrade = AtomicBoolean(false)
    val frames = CopyOnWriteArrayList<DussFrame>()
    val controls get() = frames.filter { it.set == 0x33 && it.id == 0x10 }
    private var binding = ArmServoBinding(1, 2)
    private var mode = 0
    private var sequence = 300
    private val worker = thread(isDaemon = true) {
        while (running.get()) try {
            val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
            val bytes = packet.data.copyOf(packet.length)
            socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
            fun reply(node: Int, set: Int, id: Int, attr: Int, p: ByteArray, seq: Int = sequence++) {
                val data = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(node, 2, attr, set, id, p, seq))
                socket.send(DatagramPacket(data, data.size, packet.socketAddress))
            }
            val incoming = Protocol.frames(bytes)
            for (frame in incoming) {
                frames += frame
                var p = byteArrayOf(0)
                if (frame.set == 0x33) when (frame.id) {
                    0x68 -> p = byteArrayOf(0, binding.main.toByte(), binding.secondary.toByte())
                    0x67 -> binding = ArmServoBinding(frame.payload.u8(0), frame.payload.u8(1))
                    0x21 -> { mode = frame.payload.u8(0); reply(0x3b, 0x33, 0x22, 0, byteArrayOf(mode.toByte())) }
                }
                if (frame.attr == 0x40) reply(frame.receiver, frame.set, frame.id, 0xc0, p, frame.sequence)
            }
            if (incoming.isNotEmpty()) {
                reply(0x28, 0x3f, 0x12, 0, "041803001903001e03001f0300".hexBytes())
                reply(9, 0x48, 8, 0, "000f000000000000000000".hexBytes())
                reply(9, 0x48, 8, 0, "001001".hexBytes())
                if (upgrade.get()) reply(0x28, 0, 0x42, 0, byteArrayOf(3, 25, 0))
            }
        } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
    }
    override fun close() { running.set(false); socket.close(); worker.join(1_000) }
}
