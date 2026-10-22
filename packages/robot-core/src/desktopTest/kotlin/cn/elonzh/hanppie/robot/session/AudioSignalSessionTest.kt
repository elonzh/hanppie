package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.media.RobotSpeakerPlayback
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class AudioSignalSessionTest {
    @Test fun appRoutesQueriesAcknowledgementsAndPlaybackWithoutChangingTheExistingMediaControls(): Unit = runBlocking {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val running = AtomicBoolean(true)
        val enabled = AtomicBoolean(false)
        val rejected = AtomicBoolean(false)
        val requests = CopyOnWriteArrayList<DussFrame>()
        val worker = thread(isDaemon = true) {
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535)
                socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                fun reply(sender: Int, id: Int, attr: Int, payload: ByteArray, sequence: Int = 8) {
                    val p = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(sender, 2, attr, 0x3f, id, payload, sequence))
                    socket.send(DatagramPacket(p, p.size, packet.socketAddress))
                }
                for (frame in Protocol.frames(bytes).filter { it.receiver == 1 && it.set == 0x3f && it.id in listOf(0x1f, 0xe1) }) {
                    requests += frame
                    if (frame.id == 0x1f) reply(9, 0xb4, 0, "0b230000000100".hexBytes())
                    reply(9, frame.id, 0xc0, byteArrayOf(1), frame.sequence)
                    val response = if (rejected.get()) byteArrayOf(1)
                        else if (frame.id == 0x1f) byteArrayOf(0, if (enabled.get()) 1 else 0) else byteArrayOf(0)
                    reply(1, frame.id, 0xc0, response, frame.sequence)
                }
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        try {
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort)).use { session ->
                session.connect()
                assertFalse(session.audio.readSoundEnabled())
                withTimeout(2_000) { session.audio.playback.first { it != null } }
                assertEquals(RobotSpeakerPlayback(11, 65536, 35, 0), session.audio.playback.value)
                session.audio.setAntiFeedback(true); session.audio.setAntiFeedback(false)
                enabled.set(true); assertTrue(session.audio.readSoundEnabled())
                rejected.set(true)
                assertEquals(RequestProblem.REJECTED, assertFailsWith<RobotRequestException> { session.audio.setAntiFeedback(true) }.problem)
                assertEquals(listOf("31/", "225/010c0101", "225/010c0102", "31/", "225/010c0101"),
                    requests.map { "${it.id}/${it.payload.hex()}" })
                assertTrue(requests.all { it.sender == 2 && it.attr == 0x40 })
                withTimeout(2_500) { session.audio.playback.first { it == null } }
                session.close(); assertNull(session.audio.playback.value)
            }
        } finally { running.set(false); socket.close(); worker.join(1_000) }
    }
}
