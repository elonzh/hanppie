package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.network.WifiChannelMode
import cn.elonzh.hanppie.robot.product.RobotPowerAction
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class RobotAddressingSessionTest {
    @Test fun nativeEventsUseTheRealSessionAndTaskOwnershipBlocksCompetingModesUntilAcknowledgedEnd(): Unit = runBlocking {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val running = AtomicBoolean(true); val requests = CopyOnWriteArrayList<DussFrame>()
        val worker = thread(isDaemon = true) {
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                fun reply(node: Int, set: Int, id: Int, attr: Int, p: ByteArray, sequence: Int) {
                    val data = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(node, 2, attr, set, id, p, sequence))
                    socket.send(DatagramPacket(data, data.size, packet.socketAddress))
                }
                for (frame in Protocol.frames(bytes).filter { it.set == 0x3f &&
                    (it.receiver == 0x18 && it.id == 0x62 || it.receiver == 0xc3 && it.id == 0x3e || it.receiver == 3 && it.id in listOf(0x19, 0x20)) }) {
                    requests += frame
                    if (frame.id == 0x62 && frame.payload.contentEquals(byteArrayOf(0))) {
                        reply(9, 0x3f, 0x63, 0, byteArrayOf(1), 70)
                        reply(0x18, 0x3f, 0x63, 0, byteArrayOf(1), 71)
                        reply(0x18, 0x3f, 0x63, 0, byteArrayOf(1), 71)
                    }
                    reply(0x28, frame.set, frame.id, 0xc0, byteArrayOf(1), frame.sequence)
                    reply(frame.receiver, frame.set, frame.id, 0xc0, byteArrayOf(0), frame.sequence)
                }
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        try {
            val session = AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort))
            session.use {
                session.connect(); session.addressing.begin(ModuleAddressingKind.ARMOR)
                assertEquals(1, session.addressing.state.value.armorSteps)
                assertTrue(session.addressing.state.value.blocksControl)
                assertFailsWith<IllegalStateException> { session.enterRemote() }
                assertFailsWith<IllegalStateException> { session.labMode(true) }
                assertFailsWith<IllegalStateException> { session.wifi.setChannelMode(WifiChannelMode.AUTO) {} }
                assertFailsWith<IllegalStateException> { session.power.perform(RobotPowerAction.SHUTDOWN) {} }
                assertFailsWith<IllegalStateException> { session.media(true) }
                assertTrue(assertFailsWith<IllegalStateException> { session.camera.takePhoto() }.message.orEmpty().contains("编址"))
                assertTrue(assertFailsWith<IllegalStateException> { session.camera.formatStorage() }.message.orEmpty().contains("编址"))
                assertTrue(assertFailsWith<IllegalStateException> { session.camera.setRecording(true) }.message.orEmpty().contains("编址"))
                session.safetyStop()
                assertEquals(AddressingPhase.ADDRESSING, session.addressing.state.value.phase)
                session.addressing.end()
                assertTrue(session.addressing.state.value.nativeEndAcknowledged)
                assertFalse(session.addressing.state.value.blocksControl)
                session.addressing.recover(ModuleAddressingKind.WHEELS)
                assertEquals(listOf("195/62/02", "3/32/0000000000000000", "3/25/00"),
                    requests.filter { it.receiver != 0x18 }.map { "${it.receiver}/${it.id}/${it.payload.hex()}" })
                session.addressing.begin(ModuleAddressingKind.ARMOR)
                session.close()
                withTimeout(1_000) { while (requests.count { it.receiver == 0x18 && it.payload.hex() == "ff" } < 2) delay(5) }
                assertEquals(AddressingPhase.CLOSED, session.addressing.state.value.phase)
                assertEquals(AddressingProblem.RESULT_UNKNOWN, session.addressing.state.value.problem)
                assertFailsWith<RobotRequestException> { session.addressing.end() }
            }
        } finally { running.set(false); socket.close(); worker.join(1_000) }
    }
}
