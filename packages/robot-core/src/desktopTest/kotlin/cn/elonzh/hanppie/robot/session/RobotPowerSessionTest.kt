package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class RobotPowerSessionTest {
    @Test fun nativePowerRequestsUseBrokerStopMediaAndKeepAQuiescentSessionAfterStandbyAck(): Unit = runBlocking {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val running = AtomicBoolean(true); val requests = CopyOnWriteArrayList<DussFrame>()
        val worker = thread(isDaemon = true) {
            var policy = "012c01".hexBytes()
            var rejectFirstAction = true
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                for (frame in Protocol.frames(bytes)) {
                    if (frame.set == 0x3f && frame.id == 0xd2) requests += frame
                    if (frame.receiver == 9 && frame.set == 0x3f && frame.id in listOf(0x4a, 0x4b, 0x4c)) {
                        requests += frame
                        if (frame.id == 0x4a) policy = frame.payload
                        val payload = if (frame.id == 0x4b) byteArrayOf(0) + policy
                            else if (frame.id == 0x4c && rejectFirstAction) { rejectFirstAction = false; byteArrayOf(1) }
                            else byteArrayOf(0)
                        for ((node, response) in listOf(0x28 to byteArrayOf(1), 9 to payload)) {
                            val data = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(node, 2, 0xc0, frame.set, frame.id, response, frame.sequence))
                            socket.send(DatagramPacket(data, data.size, packet.socketAddress))
                        }
                    }
                }
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        try {
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort)).use { session ->
                session.connect()
                assertEquals(LowPowerPolicy(true, 300), session.power.readPolicy())
                session.power.setPolicy(LowPowerPolicy(false, 60)) {}
                assertEquals(LowPowerPolicy(false, 60), session.power.readPolicy())
                assertEquals(RequestProblem.REJECTED, assertFailsWith<RobotRequestException> {
                    session.power.perform(RobotPowerAction.ENTER_STANDBY) {}
                }.problem)
                session.media(true)
                session.power.perform(RobotPowerAction.ENTER_STANDBY) {}
                assertFailsWith<IllegalStateException> { session.media(true) }
                assertFailsWith<IllegalStateException> { session.enterRemote() }
                assertFailsWith<IllegalStateException> { session.labMode(true) }
                withTimeout(1000) { while (requests.none { it.set == 0x3f && it.id == 0x4c }) delay(5) }
                assertEquals(listOf("75/", "74/003c00", "75/", "76/01", "76/01"), requests.filter { it.receiver == 9 }.map { "${it.id}/${it.payload.hex()}" })
                val standby = requests.indexOfFirst { it.id == 0x4c }
                assertTrue(requests.take(standby).any { it.id == 0xd2 && it.payload.contentEquals(byteArrayOf(2, 0, 0)) })
                assertTrue(requests.take(standby).any { it.id == 0xd2 && it.payload.contentEquals(byteArrayOf(1, 0, 0)) })
            }
        } finally { running.set(false); socket.close(); worker.join(1000) }
    }
}
