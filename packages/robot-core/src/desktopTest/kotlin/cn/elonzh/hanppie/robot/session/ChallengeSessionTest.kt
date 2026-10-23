package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.challenge.*
import cn.elonzh.hanppie.robot.display.SightPoint
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.remote.RemoteControl
import cn.elonzh.hanppie.robot.vision.VisionRect
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Synthetic UDP bytes exercise the actual AppSession; no device, shot or physical impact is inferred. */
class ChallengeSessionTest {
    private class Peer : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val port = socket.localPort
        val frames = CopyOnWriteArrayList<DussFrame>()
        val markers = AtomicReference(listOf(VisionRect(.75f,.25f,.2f,.2f,11)))
        val sight = AtomicReference(SightPoint(7500, 7500))
        val dropRestoration = AtomicBoolean(false)
        private val alive = AtomicBoolean(true)
        private val sequence = AtomicInteger(700)
        private data class Endpoint(val address: SocketAddress, val session: Int)
        private val endpoint = AtomicReference<Endpoint?>()
        private val worker = thread(isDaemon = true) {
            var mask = 0
            while (alive.get()) try {
                val packet = DatagramPacket(ByteArray(65535),65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes,bytes.size,packet.socketAddress))
                if (bytes.size < 4) continue
                endpoint.set(Endpoint(packet.socketAddress,bytes.u16(2)))
                val incoming = Protocol.frames(bytes)
                for (frame in incoming) {
                    frames += frame
                    if (frame.attr == 0x40 && frame.receiver == 0xf1 && frame.set == 0x0a) {
                        val body = when (frame.id) {
                            0xa3 -> { mask = frame.payload.u16(0); byteArrayOf(0) }
                            0xa5 -> byteArrayOf(0,mask.toByte(),(mask ushr 8).toByte())
                            else -> byteArrayOf(0)
                        }
                        send(0xf1,0xc0,0x0a,frame.id,body,frame.sequence)
                    }
                    if (frame.attr == 0x40 && frame.receiver == 9 && frame.set == 0x3f && frame.id in listOf(0x57, 0x09)) {
                        if (frame.id == 0x09 && frame.payload.size > 56 && frame.payload.u8(56) == 0 && dropRestoration.get()) continue
                        val body = if (frame.id == 0x57) ByteArray(9).apply {
                            put16(1, sight.get().x); put16(3, sight.get().y)
                            put16(5, sight.get().x); put16(7, sight.get().y)
                        } else byteArrayOf(0)
                        send(9, 0xc0, 0x3f, frame.id, body, frame.sequence)
                    }
                }
                if (incoming.isNotEmpty() && mask == 32) {
                    val rectangles = markers.get()
                    val body = ByteArray(9 + 20 * rectangles.size).apply {
                        this[0] = 5; this[8] = rectangles.size.toByte()
                        rectangles.forEachIndexed { index, rect ->
                            val words = listOf(rect.x.toBits(),rect.y.toBits(),rect.width.toBits(),rect.height.toBits(),rect.info)
                            words.forEachIndexed { field,bits -> repeat(4) { this[9 + index * 20 + field * 4 + it] = (bits ushr (it * 8)).toByte() } }
                        }
                    }
                    send(0xf1,0,0x0a,0xa4,body)
                }
            } catch (_: SocketTimeoutException) {} catch (_: SocketException) { break }
        }
        fun fire(sender: Int = 0x17) = send(sender,0,0x3f,0x50,"000048410300".hexBytes())
        private fun send(sender: Int, attr: Int, set: Int, id: Int, body: ByteArray, seq: Int = sequence.incrementAndGet()) {
            val target = endpoint.get() ?: return
            val data = AppEnvelope(target.session,80).direct(Protocol.duss(sender,2,attr,set,id,body,seq))
            socket.send(DatagramPacket(data,data.size,target.address))
        }
        override fun close() { alive.set(false); socket.close(); worker.join(1_000) }
    }

    @Test fun realSessionScoresDeviceNotificationsAndRetainsControlUntilResultsAreDismissed(): Unit = runBlocking {
        Peer().use { peer ->
            AppSession(RobotTarget("127.0.0.1","12345678",localPort = 0,remotePort = peer.port)).use { session ->
                session.connect(); session.enterRemote()
                val presence = launch(Dispatchers.Default) {
                    while (isActive) { runCatching { session.drive(0.0,0.0,0.0,0.0,0.0) }; delay(40) }
                }
                suspend fun waitFor(condition: () -> Boolean) = withTimeout(6_000) { while (!condition()) delay(10) }
                try {
                    session.startChallenge(ChallengeConfiguration(ChallengeMode.TARGET_RACE,markerCount = 1))
                    waitFor { session.challenge.value.phase == ChallengePhase.COUNTDOWN }
                    val countdownMark = peer.frames.size
                    session.drive(.2,0.0,0.0,10.0,0.0)
                    assertFailsWith<IllegalStateException> { session.fireInfrared() }
                    delay(100)
                    assertFalse(peer.frames.drop(countdownMark).any { it.set == 0x3f && it.id == 0x21 })
                    assertTrue(peer.frames.drop(countdownMark).filter { it.set == 4 && it.id == 0x0c }
                        .all { it.payload.contentEquals(RemoteControl.gimbalVelocity(0.0,0.0)) })
                    waitFor { session.challenge.value.phase == ChallengePhase.PLAYING }
                    session.fireInfrared(); delay(150)
                    assertTrue(session.challenge.value.checkpoints.isEmpty(), "A successful send is not a device fire notification")
                    peer.fire(9); delay(50); assertTrue(session.challenge.value.checkpoints.isEmpty())
                    peer.dropRestoration.set(true)
                    peer.fire()
                    waitFor { session.challenge.value.phase == ChallengePhase.RESULT && !session.challenge.value.stopConfirmed }
                    val originalId = session.challenge.value.roundId
                    assertFailsWith<IllegalStateException> { session.dismissChallenge() }
                    assertFailsWith<IllegalStateException> { session.startChallenge(ChallengeConfiguration(ChallengeMode.TARGET_RACE)) }
                    assertEquals(originalId, session.challenge.value.roundId)
                    peer.dropRestoration.set(false)
                    session.stopChallenge()
                    waitFor { session.challenge.value.phase == ChallengePhase.RESULT && session.challenge.value.stopConfirmed }
                    assertEquals(ChallengeEnd.COMPLETED,session.challenge.value.end)
                    assertEquals(listOf(11),session.challenge.value.checkpoints.map { it.markerId })
                    assertTrue(session.challenge.value.blocksControl)
                    assertFailsWith<IllegalStateException> { session.fireInfrared() }
                    assertFailsWith<IllegalStateException> { session.camera.setZoom(200) }
                    assertFailsWith<IllegalStateException> { session.camera.switchCamera(1) }
                    assertFailsWith<IllegalStateException> { session.arm.beginControl() }
                    assertFailsWith<IllegalStateException> { session.labMode(true) }
                    val elapsed = session.challenge.value.elapsedMillis
                    peer.fire(); delay(100); assertEquals(elapsed,session.challenge.value.elapsedMillis)
                    session.dismissChallenge(); assertFalse(session.challenge.value.ownsRound)

                    peer.markers.set(listOf(VisionRect(.5f,.5f,.2f,.2f,11)))
                    peer.sight.set(SightPoint())
                    session.startChallenge(ChallengeConfiguration(ChallengeMode.TARGET_RACE,ChallengeFiring.GEL,1))
                    waitFor { session.challenge.value.phase == ChallengePhase.PLAYING }
                    peer.fire(); delay(40); peer.markers.set(emptyList())
                    waitFor { session.challenge.value.phase == ChallengePhase.RESULT && session.challenge.value.stopConfirmed }
                    assertEquals(ChallengeEnd.COMPLETED,session.challenge.value.end)
                    assertTrue(session.challenge.value.elapsedMillis >= 300)
                    assertEquals(1,session.challenge.value.checkpoints.size)

                    session.startChallenge(ChallengeConfiguration(ChallengeMode.TARGET_RACE,markerCount = 1))
                    waitFor { session.challenge.value.phase == ChallengePhase.COUNTDOWN }
                    presence.cancelAndJoin()
                    waitFor { session.challenge.value.phase == ChallengePhase.RESULT && session.challenge.value.stopConfirmed }
                    assertEquals(ChallengeEnd.INPUT_LOST,session.challenge.value.end)
                    peer.fire(); delay(100); assertTrue(session.challenge.value.checkpoints.isEmpty())
                    assertTrue(peer.frames.any { it.receiver == 0xc3 && it.set == 0x3f && it.id == 0x20 && it.payload.contentEquals(ByteArray(8)) })
                    session.exitRemote()
                    waitFor { !session.challenge.value.ownsRound }
                    assertEquals(ChallengePhase.IDLE, session.challenge.value.phase)
                    session.enterRemote()
                    session.drive(0.0, 0.0, 0.0, 0.0, 0.0)
                    session.startChallenge(ChallengeConfiguration(ChallengeMode.TARGET_RACE, markerCount = 1))
                    session.exitRemote()
                    waitFor { !session.challenge.value.ownsRound }
                    assertEquals(ChallengePhase.IDLE, session.challenge.value.phase)
                } finally { presence.cancelAndJoin() }
            }
        }
    }
}
