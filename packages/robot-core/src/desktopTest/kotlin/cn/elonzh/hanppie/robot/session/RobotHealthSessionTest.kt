package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.telemetry.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class RobotHealthSessionTest {
    @Test fun existingPushesRetainDatedModuleReportsExpireBatteryAndClearOnClose(): Unit = runBlocking {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val running = AtomicBoolean(true); val sentHealth = AtomicBoolean(false)
        val worker = thread(isDaemon = true) {
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                if (Protocol.frames(bytes).any { it.set == 0x48 && it.id == 3 } && sentHealth.compareAndSet(false, true)) {
                    fun push(node: Int, set: Int, id: Int, payload: ByteArray) {
                        val data = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(node, 2, 0, set, id, payload, 0))
                        socket.send(DatagramPacket(data, data.size, packet.socketAddress))
                    }
                    push(0x28, 0x3f, 0x12, byteArrayOf(2, 0, 3, 1, 4, 0x82.toByte(), 0, 0x17, 1, 4, 0x80.toByte()))
                    push(9, 0x48, 8, ByteArray(62).apply { this[1] = 1; put16(2, 12000); put16(4, 320); this[10] = 72 })
                }
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        try {
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort)).use { session ->
                session.connect()
                withTimeout(2_000) { while (session.health.value.modules == null || session.health.value.battery == null) delay(5) }
                assertEquals(2, session.health.value.issues.size)
                assertEquals(BatteryTelemetry(12000, 320, 0, 72), session.health.value.battery)
                withTimeout(4_000) { while (session.health.value.moduleReportAgeSeconds < 3 || session.health.value.battery != null) delay(10) }
                assertEquals(2, session.health.value.issues.size)
                session.close(); assertEquals(RobotHealth(), session.health.value)
            }
        } finally { running.set(false); socket.close(); worker.join(1_000) }
    }
}
