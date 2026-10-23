package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.vision.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.*

class VisionSessionTest {
    @Test fun aimOwnsOnlyGimbalMarkersKeepManualControlAndLeaseOrExitNeverResumeVision() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { robot ->
            robot.soTimeout = 50
            val alive = AtomicBoolean(true)
            val sent = CopyOnWriteArrayList<DussFrame>()
            val peer = thread(isDaemon = true) {
                var mask = 0
                while (alive.get()) try {
                    val packet = DatagramPacket(ByteArray(65535), 65535)
                    robot.receive(packet)
                    val bytes = packet.data.copyOf(packet.length)
                    val frames = Protocol.frames(bytes)
                    sent.addAll(frames)
                    robot.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                    if (bytes.size >= 12) {
                        val envelope = AppEnvelope(bytes.u16(2), 80)
                        fun reply(id: Int, payload: ByteArray, sequence: Int = 1, attr: Int = 0xc0) {
                            val data = envelope.direct(Protocol.duss(0xf1, 2, attr, 0x0a, id, payload, sequence))
                            robot.send(DatagramPacket(data, data.size, packet.socketAddress))
                        }
                        for (frame in frames.filter { it.receiver == 0xf1 && it.set == 0x0a }) when (frame.id) {
                            0xa3 -> { mask = frame.payload.u16(0); reply(0xa3, byteArrayOf(0), frame.sequence) }
                            0xa5 -> reply(0xa5, byteArrayOf(0) + VisionProtocol.enable(mask), frame.sequence)
                        }
                        if (mask != 0) {
                            val geometry = listOf(.5f, .5f, .2f, .3f).flatMap { value -> List(4) { (value.toBits() ushr (8 * it)).toByte() } }.toByteArray()
                            reply(0xa4, byteArrayOf(if (mask == 8) 3 else 5, 3, 0, 0, 0, 0, 0, 0, 1) +
                                geometry + byteArrayOf(if (mask == 8) 5 else 45, 0, if (mask == 8) 0 else 100, 0), attr = 0)
                        }
                    }
                } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
            }
            val session = AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = robot.localPort))
            val input = AtomicReference(Triple(0.0, 0.0, 0.0))
            var operator: Job? = null
            suspend fun await(phase: VisionPhase) {
                withTimeoutOrNull(2_000) { while (session.vision.value.phase != phase) delay(5) }
                assertEquals(phase, session.vision.value.phase,
                    "${session.vision.value}; requests=${sent.filter { it.receiver == 0xf1 }.takeLast(10).map { it.id.toString(16) + ':' + it.payload.hex() }}")
            }
            fun heartbeat() = launch {
                while (isActive) {
                    val (x, pitch, yaw) = input.get()
                    session.drive(x, 0.0, 0.0, pitch, yaw)
                    delay(40)
                }
            }
            try {
                session.connect(); session.enterRemote()
                operator = heartbeat()
                session.startVision(VisionMode.ROBOT_AIM); await(VisionPhase.ACTIVE)
                withTimeout(2_000) { while (session.vision.value.rectangles.isEmpty()) delay(5) }
                assertTrue(session.vision.value.ownsGimbal)
                assertEquals(8, session.vision.value.mask)
                assertFailsWith<IllegalStateException> { session.startTracking() }
                assertFailsWith<IllegalStateException> { session.startVision(VisionMode.MARKERS) }
                assertFailsWith<IllegalStateException> { session.camera.setZoom(400) }
                assertFailsWith<IllegalStateException> { session.labMode(true) }
                input.set(Triple(.2, 0.0, 0.0))
                val mark = sent.size
                session.fireInfrared()
                delay(150)
                assertTrue(sent.drop(mark).any { it.set == 0x3f && it.id == 0x21 && it.payload.any { b -> b != 0.toByte() } }, "Aim must preserve explicit manual chassis input")
                assertFalse(sent.drop(mark).any { it.set == 4 && it.id == 0xc }, "The normal gimbal ticker must yield to native aim")
                input.set(Triple(0.0, 0.0, 0.0))
                session.drive(0.0, 0.0, 0.0, .2, 0.0)
                await(VisionPhase.OFF)
                assertFalse(session.vision.value.ownsGimbal)
                session.startVision(VisionMode.MARKERS); await(VisionPhase.ACTIVE)
                withTimeout(2_000) { while (session.vision.value.rectangles.isEmpty()) delay(5) }
                assertEquals(0x0064002d, session.vision.value.rectangles.single().info)
                input.set(Triple(.1, .2, 0.0))
                val markerMark = sent.size
                delay(100)
                assertTrue(sent.drop(markerMark).any { it.set == 4 && it.id == 0xc && it.payload.take(6).any { b -> b != 0.toByte() } }, "Read-only markers must preserve manual gimbal input")
                operator.cancelAndJoin(); operator = null
                session.exitRemote(); await(VisionPhase.OFF)
                session.enterRemote()
                input.set(Triple(0.0, 0.0, 0.0)); operator = heartbeat()
                assertEquals(VisionPhase.OFF, session.vision.value.phase)
                session.startVision(VisionMode.ROBOT_AIM); await(VisionPhase.ACTIVE)
                operator.cancelAndJoin(); operator = null
                await(VisionPhase.OFF)
                val stopped = sent.size
                delay(100)
                assertTrue(sent.drop(stopped).any { it.set == 0x3f && it.id == 0x20 && it.payload.contentEquals(ByteArray(8)) })
                assertFalse(sent.drop(stopped).any { it.set == 0x0a && it.id == 0xa3 && it.payload.contentEquals(VisionProtocol.enable(8)) })
            } finally {
                operator?.cancelAndJoin()
                session.close(); alive.set(false); peer.join(1_000)
            }
        }
    }
}
