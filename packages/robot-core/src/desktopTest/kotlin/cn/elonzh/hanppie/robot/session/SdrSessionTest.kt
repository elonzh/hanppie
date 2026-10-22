package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.network.*
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class SdrSessionTest {
    @Test fun sessionMatchesSdrNodeGuardsRemoteWritesAndReleasesPendingQueries(): Unit = runBlocking {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val running = AtomicBoolean(true); val dropConfig = AtomicBoolean(false)
        val waiting = CompletableDeferred<Unit>(); val calls = CopyOnWriteArrayList<DussFrame>()
        val worker = thread(isDaemon = true) {
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                for (f in Protocol.frames(bytes).filter { it.receiver == 14 && it.set == 9 || it.receiver == 7 && it.set == 7 && it.id == 0x39 }) {
                    calls += f
                    if (f.receiver == 14 && f.id == 0x21 && dropConfig.get()) { waiting.complete(Unit); continue }
                    val payload = if (f.receiver == 7) byteArrayOf(0, 1) else if (f.id == 0x21) sdrResponse(SdrSettings(0, 1, 1011, 1, true)) else byteArrayOf(0)
                    fun reply(sender: Int) {
                        val response = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(sender, 2, 0xc0, f.set, f.id, payload, f.sequence))
                        socket.send(DatagramPacket(response, response.size, packet.socketAddress))
                    }
                    if (f.receiver == 14) reply(7) // Same command and sequence, wrong node.
                    reply(f.receiver)
                }
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        try {
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort)).use { session ->
                session.connect(); assertEquals(1, session.sdr.read()?.bandwidthCode)
                session.enterRemote()
                assertFailsWith<IllegalStateException> { session.sdr.set(SdrChange.Bandwidth(SdrBandwidth.MHZ_20)) {} }
                assertFalse(calls.any { it.receiver == 14 && it.id == 0x0d })
                session.exitRemote(); session.sdr.set(SdrChange.Bandwidth(SdrBandwidth.MHZ_10)) {}
                assertTrue(calls.any { it.receiver == 14 && it.id == 0x0d && it.payload.hex() == "040100" })
                dropConfig.set(true)
                val pending = async { runCatching { session.sdr.read() }.exceptionOrNull() }
                withTimeout(2_000) { waiting.await() }; session.close()
                assertEquals(RequestProblem.DISCONNECTED, (pending.await() as RobotRequestException).problem)
            }
        } finally { running.set(false); socket.close(); worker.join(1_000) }
    }
}
