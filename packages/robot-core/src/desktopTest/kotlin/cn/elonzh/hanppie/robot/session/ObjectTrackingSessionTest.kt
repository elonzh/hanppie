package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.protocol.AppEnvelope
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.u16
import cn.elonzh.hanppie.robot.tracking.TrackingPhase
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.*

class ObjectTrackingSessionTest {
    @Test fun trackingOwnsActuatorsUntilManualTakeoverAndOperatorLeaseStopsIt() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { robot ->
            robot.soTimeout = 50
            val alive = AtomicBoolean(true)
            val sent = CopyOnWriteArrayList<DussFrame>()
            val peer = thread(isDaemon = true) {
                while (alive.get()) try {
                    val packet = DatagramPacket(ByteArray(65535), 65535)
                    robot.receive(packet)
                    val bytes = packet.data.copyOf(packet.length)
                    val frames = Protocol.frames(bytes)
                    sent.addAll(frames)
                    robot.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                    for (f in frames.filter { it.set == 0xa && it.receiver == 0xf1 }) {
                        val envelope = AppEnvelope(bytes.u16(2), 80)
                        fun reply(attr: Int, id: Int, payload: ByteArray) {
                            val data = envelope.direct(Protocol.duss(sender = 0xf1, receiver = 2,
                                attr = attr, set = 0xa, id = id, payload = payload, sequence = f.sequence))
                            robot.send(DatagramPacket(data, data.size, packet.socketAddress))
                        }
                        reply(0xc0, f.id, byteArrayOf(0))
                        val status = when (f.id) { 0xa8 -> 2; 0xa7 -> if (f.payload[2] == 1.toByte()) 3 else 1; else -> 1 }
                        val rect = listOf(.5f, .5f, .2f, .3f).flatMap { value -> List(4) { (value.toBits() ushr (8 * it)).toByte() } }.toByteArray()
                        reply(0, 0xa4, byteArrayOf(6, status.toByte(), 0, 0, 0, 0, 0, 0, 1) + rect + ByteArray(4))
                    }
                } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
            }
            val session = AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = robot.localPort))
            try {
                session.connect(); session.enterRemote()
                val operator = launch { while (isActive) { session.drive(0.0, 0.0, 0.0, 0.0, 0.0); delay(40) } }
                suspend fun waitFor(phase: TrackingPhase) = withTimeout(2_000) {
                    while (session.tracking.value.phase != phase) delay(5)
                }
                suspend fun follow() {
                    session.startTracking(); waitFor(TrackingPhase.SEARCHING)
                    session.selectTrackingTarget(.5f, .5f); waitFor(TrackingPhase.TARGET_READY)
                    session.confirmTracking(); waitFor(TrackingPhase.FOLLOWING)
                }
                try {
                    follow(); delay(50)
                    val mark = sent.size
                    delay(100)
                    assertFalse(sent.drop(mark).any { it.set == 4 && it.id == 0xc || it.set == 0x3f && it.id in listOf(0x20, 0x21) },
                        "The remote ticker must not overwrite native tracking with zero speeds")
                    assertFailsWith<IllegalStateException> { session.fireInfrared() }
                    session.drive(.2, 0.0, 0.0, 0.0, 0.0)
                    waitFor(TrackingPhase.OFF)
                    assertTrue(sent.any { it.set == 0xa && it.id == 0xa7 && it.payload[2] == 0.toByte() })
                    follow()
                    operator.cancelAndJoin()
                    waitFor(TrackingPhase.OFF)
                    val stoppedAt = sent.size
                    delay(100)
                    assertTrue(sent.drop(stoppedAt).any { it.set == 0x3f && it.id == 0x20 && it.payload.contentEquals(ByteArray(8)) })
                } finally { operator.cancelAndJoin() }
            } finally { session.close(); alive.set(false); peer.join(1_000) }
        }
    }
}
