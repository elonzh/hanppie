package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class WifiChannelsSessionTest {
    @Test fun sessionRoutesChannelQueriesWritesAndFreshReports(): Unit = runBlocking {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val running = AtomicBoolean(true)
        val calls = CopyOnWriteArrayList<DussFrame>()
        val worker = thread(isDaemon = true) {
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                fun reply(id: Int, payload: ByteArray, attr: Int, sequence: Int = 0) {
                    val response = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(7, 2, attr, 7, id, payload, sequence))
                    socket.send(DatagramPacket(response, response.size, packet.socketAddress))
                }
                for (f in Protocol.frames(bytes).filter { it.receiver == 7 && it.set == 7 && it.id in listOf(0x39, 0x28, 0x17, 0x16, 0x29, 0x2b) }) {
                    calls += f
                    reply(f.id, when (f.id) { 0x39 -> byteArrayOf(0, 0); 0x28 -> channelInfo(149, listOf(1, 6, 149));
                        0x17 -> byteArrayOf(0, 1); else -> byteArrayOf(0) }, 0xc0, f.sequence)
                    if (f.id == 0x29 && f.payload.contentEquals(byteArrayOf(1))) {
                        reply(0x2a, signalPayload(listOf(149 to -67)), 0)
                        reply(0x3f, byteArrayOf(4, 0), 0)
                    }
                }
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        try {
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort)).use { session ->
                session.connect()
                assertEquals(WifiRadioSettings(0, 1, 149, listOf(1, 6, 149)), session.wifi.readRadio())
                session.wifi.setChannelMode(WifiChannelMode.MANUAL) {}; session.wifi.setChannel(149) {}
                session.wifi.setSignalReports(true)
                withTimeout(2_000) { session.wifi.signals.first { it.channels != null && it.interferenceCode != null } }
                assertEquals(WifiSignals(listOf(WifiChannelSignal(149, -67)), 4), session.wifi.signals.value)
                assertTrue(calls.any { it.id == 0x16 && it.payload.hex() == "01000000" })
                assertTrue(calls.any { it.id == 0x2b && it.payload.hex() == "019500" })
                withTimeout(2_500) { session.wifi.signals.first { it == WifiSignals() } }
                session.wifi.setSignalReports(false)
                session.close(); assertEquals(WifiSignals(), session.wifi.signals.value)
            }
        } finally { running.set(false); socket.close(); worker.join(1_000) }
    }
}
