package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Actual AppSession/UDP routing with a synthetic ACK/report peer; no camera or image effect. */
class CameraZoomSessionTest {
    private class Peer : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 20 }
        val port = socket.localPort
        val frames = CopyOnWriteArrayList<DussFrame>()
        val holdNext = AtomicBoolean(false)
        val release = AtomicBoolean(false)
        private data class Pending(val session: Int, val endpoint: SocketAddress, val frame: DussFrame)
        private val pending = AtomicReference<Pending?>(null)
        val awaitingAck get() = pending.get() != null
        private val alive = AtomicBoolean(true)
        private val worker = thread(isDaemon = true) {
            var zoom = 100
            fun ack(p: Pending) {
                val data = AppEnvelope(p.session, 80).direct(Protocol.duss(1, 2, 0xc0, 2, 0x34, byteArrayOf(0), p.frame.sequence))
                socket.send(DatagramPacket(data, data.size, p.endpoint))
            }
            while (alive.get()) try {
                if (release.getAndSet(false)) pending.getAndSet(null)?.let(::ack)
                val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                val incoming = Protocol.frames(bytes); frames.addAll(incoming)
                if (bytes.size < 12) continue
                val session = bytes.u16(2)
                for (frame in incoming.filter { it.receiver == 1 && it.set == 2 && it.id == 0x34 }) {
                    zoom = frame.payload.u16(3)
                    val request = Pending(session, packet.socketAddress, frame)
                    if (holdNext.getAndSet(false)) pending.set(request) else ack(request)
                }
                val report = AppEnvelope(session, 80).direct(Protocol.duss(1, 2, 0, 2, 0xe4,
                    ByteArray(26).apply { put16(8, zoom) }, 100))
                socket.send(DatagramPacket(report, report.size, packet.socketAddress))
            } catch (_: SocketTimeoutException) {} catch (_: SocketException) { break }
        }
        override fun close() { alive.set(false); socket.close(); worker.join(1_000) }
    }

    @Test fun haltRetiresTheTransitionBeforeALateAckCanSendItsNextStep() = scenario(reenter = false)
    @Test fun leavingAndReenteringRemoteDoesNotAdmitAnOlderTransitionsNextWrite() = scenario(reenter = true)
    @Test fun enteringRemoteRetiresAnIdleCameraTransitionBeforeTheInitializationReset() = scenario(reenter = true, startRemote = false)

    @Test fun anIdleCameraWriteNeverEnablesDrivingOrFiring(): Unit = runBlocking {
        Peer().use { peer ->
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = peer.port)).use { session ->
                session.connect()
                withTimeout(5_000) { while (session.camera.state.value.zoomHundredths != 100) delay(5) }
                session.camera.setZoom(250, smooth = true)
                assertEquals(250, session.camera.state.value.zoomHundredths)
                assertFailsWith<IllegalStateException> { session.drive(.1, 0.0, 0.0, 0.0, 0.0) }
                assertFailsWith<IllegalStateException> { session.fireInfrared() }
            }
        }
    }

    private fun scenario(reenter: Boolean, startRemote: Boolean = true): Unit = runBlocking {
        Peer().use { peer ->
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = peer.port)).use { session ->
                session.connect(); if (startRemote) session.enterRemote()
                withTimeout(5_000) { while (session.camera.state.value.zoomHundredths != 100) delay(5) }
                val mark = peer.frames.size
                peer.holdNext.set(true)
                val task = async { session.camera.setZoom(400, smooth = true) }
                withTimeout(5_000) { while (!peer.awaitingAck) delay(5) }
                assertFailsWith<IllegalStateException> { session.camera.setZoom(100, true) }
                if (reenter) { if (startRemote) session.exitRemote(); session.enterRemote() } else session.halt()
                val retiredAt = peer.frames.size
                peer.release.set(true)
                withTimeout(5_000) { assertFailsWith<CancellationException> { task.await() } }
                delay(60)
                val following = peer.frames.drop(retiredAt).filter { it.receiver == 1 && it.set == 2 && it.id == 0x34 }
                assertTrue(following.isEmpty(), "No step from the retired task may follow its delayed ACK")
                assertTrue(peer.frames.drop(mark).any { it.receiver == 1 && it.set == 2 && it.id == 0x34 && it.payload.u16(3) in 101..399 })
                session.camera.setZoom(100, smooth = true)
                assertEquals(100, session.camera.state.value.zoomHundredths)
            }
        }
    }
}
