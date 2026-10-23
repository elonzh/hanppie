package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.remote.ChassisParameter
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class RobotMotionSessionTest {
    @Test fun nativeRequestsAndReadbackUseTheActualBrokerAndRejectRemoteWrites(): Unit = runBlocking {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val running = AtomicBoolean(true); val calls = CopyOnWriteArrayList<DussFrame>()
        val worker = thread(isDaemon = true) {
            var level = 4; val values = mutableMapOf<String, String>()
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                for (frame in Protocol.frames(bytes)) {
                    val reply = when {
                        frame.receiver == 9 && frame.set == 0x3f && frame.id == 0x4f -> byteArrayOf(0, level.toByte())
                        frame.receiver == 9 && frame.set == 0x3f && frame.id == 0x3f -> { level = frame.payload[0].toInt(); byteArrayOf(0) }
                        frame.receiver == 3 && frame.set == 3 && frame.id == 0xf7 -> "0008000400010000000000000040400000c03f".hexBytes()
                        frame.receiver == 3 && frame.set == 3 && frame.id == 0xf8 -> "00${frame.payload.hex()}${values[frame.payload.hex()] ?: "0000803f"}".hexBytes()
                        frame.receiver == 3 && frame.set == 3 && frame.id == 0xf9 -> { values[frame.payload.copyOf(4).hex()] = frame.payload.copyOfRange(4, 8).hex(); byteArrayOf(0) }
                        else -> continue
                    }
                    calls += frame
                    for ((sender, payload) in listOf(0x28 to byteArrayOf(1), frame.receiver to reply)) {
                        val response = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(sender, 2, 0xc0, frame.set, frame.id, payload, frame.sequence))
                        socket.send(DatagramPacket(response, response.size, packet.socketAddress))
                    }
                }
            } catch (_: SocketTimeoutException) {} catch (_: SocketException) { break }
        }
        try {
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort)).use { session ->
                session.connect()
                assertEquals(7, session.motion.read().parameters.size)
                var sent = false
                assertEquals(.5f, session.motion.setParameter(ChassisParameter.FORWARD_SPEED, .5f) { sent = true }.value)
                assertTrue(sent)
                assertEquals("810636fe0000003f", calls.single { it.id == 0xf9 }.payload.hex())
                assertTrue(calls.all { it.sender == 2 && it.attr == 0x40 })
                assertEquals(2, session.motion.setSpeedLevel(2) {}.speedLevelRaw)
                session.enterRemote()
                val before = calls.count { it.id == 0x3f || it.id == 0xf9 }
                assertFailsWith<IllegalStateException> { session.motion.setSpeedLevel(4) { fail("remote dispatch") } }
                assertEquals(before, calls.count { it.id == 0x3f || it.id == 0xf9 })
            }
        } finally { running.set(false); socket.close(); worker.join(1000) }
    }
}
