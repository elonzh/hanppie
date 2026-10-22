package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class WifiSessionTest {
    @Test fun wifiUsesTheSessionReplyBrokerAndRefusesWritesDuringRemoteOrLab(): Unit = runBlocking {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val running = AtomicBoolean(true)
        val drop = AtomicBoolean(false)
        val requests = CopyOnWriteArrayList<DussFrame>()
        val worker = thread(isDaemon = true) {
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                for (frame in Protocol.frames(bytes).filter { it.receiver == 7 && it.set == 7 && it.id in listOf(7, 8, 0x0d, 0x0e, 0x15, 0x39) }) {
                    requests += frame
                    if (drop.get()) continue
                    val body = when (frame.id) {
                        0x39 -> byteArrayOf(0, 0)
                        7 -> byteArrayOf(0, 4) + "RMS1".encodeToByteArray()
                        0x0e -> byteArrayOf(0, 8) + "password".encodeToByteArray()
                        else -> byteArrayOf(0)
                    }
                    for (sender in listOf(8, 7)) {
                        val response = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(sender, 2, 0xc0, 7, frame.id, body, frame.sequence))
                        socket.send(DatagramPacket(response, response.size, packet.socketAddress))
                    }
                }
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        try {
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort)).use { session ->
                session.connect()
                assertEquals(RobotWifiProfile(0, "RMS1", "password"), session.wifi.read())
                val confirmed = mutableListOf<WifiConfirmedField>()
                session.wifi.configure(RobotApCredentials("RMS1_New", "new_pass"), confirmed::add)
                session.wifi.reboot()
                assertEquals(WifiConfirmedField.entries.toList(), confirmed)
                assertEquals(listOf(0x39, 7, 0x0e, 0x39, 8, 0x0d, 0x15), requests.map { it.id })
                assertTrue(requests.all { it.sender == 2 && it.attr == 0x40 })
                session.enterRemote()
                assertFailsWith<IllegalStateException> { session.wifi.reboot() }
                session.exitRemote(); session.labMode(true)
                assertFailsWith<IllegalStateException> { session.wifi.configure(RobotApCredentials("test", "password")) {} }
                session.normalMode(); drop.set(true)
                val pending = async { runCatching { session.wifi.read() }.exceptionOrNull() }
                withTimeout(2_000) { while (requests.size < 8) delay(10) }
                session.close()
                assertEquals(RequestProblem.DISCONNECTED, (pending.await() as RobotRequestException).problem)
            }
        } finally { running.set(false); socket.close(); worker.join(1_000) }
    }
}
