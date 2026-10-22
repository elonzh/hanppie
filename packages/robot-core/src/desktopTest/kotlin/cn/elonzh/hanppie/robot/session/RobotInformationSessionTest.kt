package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class RobotInformationSessionTest {
    @Test fun nativeReadsUseTheBrokerWithoutTrustingWrongNodesAndSessionCloseClearsUsage(): Unit = runBlocking {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val running = AtomicBoolean(true)
        val requests = CopyOnWriteArrayList<DussFrame>()
        val worker = thread(isDaemon = true) {
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                fun reply(node: Int, set: Int, id: Int, attr: Int, payload: ByteArray, sequence: Int = 7) {
                    val data = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(node, 2, attr, set, id, payload, sequence))
                    socket.send(DatagramPacket(data, data.size, packet.socketAddress))
                }
                for (frame in Protocol.frames(bytes).filter { it.set == 0 && it.id in listOf(1, 0x32) }) {
                    requests += frame
                    reply(9, frame.set, frame.id, 0xc0, byteArrayOf(1), frame.sequence)
                    val response = if (frame.id == 0x32) byteArrayOf(0, 1) else ByteArray(26).apply {
                        this[22] = 74; this[23] = 6; this[24] = 2; this[25] = 1
                    }
                    reply(frame.receiver, frame.set, frame.id, 0xc0, response, frame.sequence)
                }
                reply(0x28, 0x3f, 3, 0, "00000000e8030000100e0000".hexBytes())
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        try {
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort)).use { session ->
                session.connect()
                assertEquals("01.02.06.74", session.information.readFirmware(RobotVersionTarget.SYSTEM))
                assertEquals("01.02.16.10", session.information.readFirmware(RobotVersionTarget.CAMERA))
                assertTrue(session.information.readActivated())
                withTimeout(2000) { session.information.systemStatus.first { it != null } }
                assertEquals(1000, session.information.systemStatus.value?.mileageMetres)
                // The existing connection setup already queries the system version once.
                assertEquals(listOf("40/1/", "40/1/", "1/1/", "40/50/1100"), requests.map { "${it.receiver}/${it.id}/${it.payload.hex()}" })
                assertTrue(requests.all { it.sender == 2 && it.attr == 0x40 })
                session.close(); assertNull(session.information.systemStatus.value)
                assertEquals(RequestProblem.DISCONNECTED,
                    assertFailsWith<RobotRequestException> { session.information.readActivated() }.problem)
            }
        } finally { running.set(false); socket.close(); worker.join(1000) }
    }
}
