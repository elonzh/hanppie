package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.media.SpeakerLanguage
import cn.elonzh.hanppie.robot.protocol.*
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

class RobotAudioSessionTest {
    private data class Reply(val payload: ByteArray, val sender: Int? = null, val receiver: Int = 2,
        val sequenceShift: Int = 0, val attr: Int = 0xc0, val valid: Boolean = true)

    private class Peer : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val received = CopyOnWriteArrayList<DussFrame>()
        private val alive = AtomicBoolean(true)
        @Volatile var volume = 38
        @Volatile var language = 0
        @Volatile var handler: (DussFrame) -> List<Reply> = { frame ->
            when (frame.id) {
                0x1c -> listOf(Reply(byteArrayOf(0, volume.toByte())))
                0x17 -> listOf(Reply(byteArrayOf(0, language.toByte())))
                0x1b -> { volume = frame.payload.u8(0); listOf(Reply(byteArrayOf(0))) }
                0x16 -> { language = frame.payload.u8(0); listOf(Reply(byteArrayOf(0))) }
                else -> emptyList()
            }
        }
        private val worker = thread(isDaemon = true) {
            while (alive.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535)
                socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                // Echo keeps the existing handshake and session ticker alive, but cannot satisfy a device reply.
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                for (frame in Protocol.frames(bytes).filter { it.set == 0x3f && it.id in listOf(0x16, 0x17, 0x1b, 0x1c) }) {
                    received += frame
                    for (reply in handler(frame)) {
                        val payload = Protocol.duss(sender = reply.sender ?: frame.receiver, receiver = reply.receiver,
                            attr = reply.attr, set = frame.set, id = frame.id, payload = reply.payload,
                            sequence = (frame.sequence + reply.sequenceShift) and 65535)
                        if (!reply.valid) payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
                        val data = AppEnvelope(bytes.u16(2), 80).direct(payload)
                        socket.send(DatagramPacket(data, data.size, packet.socketAddress))
                    }
                }
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        fun session() = AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort))
        override fun close() { alive.set(false); socket.close(); worker.join(1_000) }
    }

    @Test fun readsNativeAudioAndVerifiesWritesWithoutChangingTheLegacyVolumeRoute(): Unit = runBlocking {
        Peer().use { peer -> peer.session().use { session ->
            session.connect()
            assertEquals(38, session.audio.read().volume)
            assertEquals(SpeakerLanguage.ENGLISH, session.audio.read().language)
            assertEquals(SpeakerLanguage.CHINESE, session.audio.setLanguage(SpeakerLanguage.CHINESE).language)
            assertEquals(100, session.audio.applyVolume(100).volume)
            assertTrue(peer.received.filter { it.id in listOf(0x1c, 0x17) }.all { it.receiver == 9 && it.payload.isEmpty() })
            assertEquals("01", peer.received.single { it.id == 0x16 }.payload.hex())
            assertEquals(9, peer.received.single { it.id == 0x16 }.receiver)
            assertEquals("64", peer.received.single { it.id == 0x1b }.payload.hex())
            assertEquals(1, peer.received.single { it.id == 0x1b }.receiver)
            assertFailsWith<IllegalArgumentException> { session.audio.applyVolume(101) }
        } }
    }

    @Test fun ignoresWrongPeerSequenceAttributeAndCrcWhileAcceptingTheMatchedAck(): Unit = runBlocking {
        Peer().use { peer ->
            peer.handler = { frame -> listOf(
                Reply(byteArrayOf(0, 99), sequenceShift = 1), Reply(byteArrayOf(0, 99), sender = 1),
                Reply(byteArrayOf(0, 99), receiver = 3), Reply(byteArrayOf(0, 99), attr = 0),
                Reply(byteArrayOf(0, 99), valid = false),
                Reply(byteArrayOf(0, if (frame.id == 0x1c) 38 else 8), attr = 0x80)) }
            peer.session().use { session ->
                session.connect()
                val result = session.audio.read()
                assertEquals(38, result.volume)
                assertEquals(SpeakerLanguage.RUSSIAN, result.language)
            }
        }
    }

    @Test fun rejectsMalformedDeniedAndUnappliedResultsButPreservesUnknownLanguage(): Unit = runBlocking {
        Peer().use { peer -> peer.session().use { session ->
            session.connect()
            peer.language = 255
            assertNull(session.audio.read().language)
            assertEquals(255, session.audio.read().languageCode)
            val ordinary = peer.handler
            peer.handler = { listOf(Reply(byteArrayOf(0))) }
            assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { session.audio.read() }.problem)
            peer.handler = { listOf(Reply(byteArrayOf(7))) }
            val denied = assertFailsWith<RobotRequestException> { session.audio.read() }
            assertEquals(RequestProblem.REJECTED, denied.problem); assertEquals(7, denied.responseCode)
            peer.handler = { frame -> if (frame.id == 0x16) listOf(Reply(byteArrayOf(0))) else ordinary(frame) }
            assertEquals(RequestProblem.NOT_APPLIED,
                assertFailsWith<RobotRequestException> { session.audio.setLanguage(SpeakerLanguage.JAPANESE) }.problem)
        } }
    }

    @Test fun boundsMissingRepliesAndReleasesPendingRequestsOnClose(): Unit = runBlocking {
        Peer().use { peer -> peer.session().use { session ->
            session.connect()
            peer.handler = { emptyList() }
            assertEquals(RequestProblem.TIMED_OUT, assertFailsWith<RobotRequestException> { session.audio.read() }.problem)
            val mark = peer.received.size
            val pending = async { runCatching { session.audio.read() } }
            withTimeout(1_000) { while (peer.received.size == mark) delay(5) }
            session.close()
            val failure = withTimeout(500) { pending.await().exceptionOrNull() }
            assertEquals(RequestProblem.DISCONNECTED, (failure as RobotRequestException).problem)
        } }
    }

    @Test fun cancellingAReadDoesNotConsumeTheNextReadReply(): Unit = runBlocking {
        Peer().use { peer -> peer.session().use { session ->
            session.connect()
            val ordinary = peer.handler
            peer.handler = { emptyList() }
            val pending = launch { session.audio.read() }
            withTimeout(1_000) { while (peer.received.isEmpty()) delay(5) }
            pending.cancelAndJoin()
            peer.handler = ordinary
            assertEquals(38, session.audio.read().volume)
        } }
    }
}
