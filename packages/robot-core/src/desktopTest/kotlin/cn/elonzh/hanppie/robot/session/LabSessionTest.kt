package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.lab.LabController
import cn.elonzh.hanppie.robot.lab.LabProgram
import cn.elonzh.hanppie.robot.lab.LabResourceProtocol
import cn.elonzh.hanppie.robot.protocol.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.*

/** Synthetic packets exercise the real App envelope and pending-request owner, not device acceptance. */
class LabSessionTest {
    private data class Reply(val payload: ByteArray, val sender: Int? = null, val receiver: Int = 2,
        val sequence: Int? = null, val attr: Int = 0xc0, val setOffset: Int = 0, val idOffset: Int = 0, val valid: Boolean = true)

    private class Peer : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val received = CopyOnWriteArrayList<DussFrame>()
        val allFrames = CopyOnWriteArrayList<DussFrame>()
        @Volatile private var endpoint: SocketAddress? = null
        @Volatile private var envelopeSession = 1
        private val alive = AtomicBoolean(true)
        @Volatile var handler: (DussFrame) -> List<Reply> = { listOf(Reply(byteArrayOf(0))) }
        private val worker = thread(isDaemon = true) {
            while (alive.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535)
                socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                endpoint = packet.socketAddress
                envelopeSession = bytes.u16(2)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                allFrames.addAll(Protocol.frames(bytes))
                for (frame in Protocol.frames(bytes).filter { it.attr == 0x40 }) {
                    received += frame
                    for (reply in handler(frame)) {
                        val payload = Protocol.duss(reply.sender ?: frame.receiver, reply.receiver, reply.attr,
                            frame.set + reply.setOffset, frame.id + reply.idOffset, reply.payload, reply.sequence ?: frame.sequence)
                        if (!reply.valid) payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
                        val data = AppEnvelope(bytes.u16(2), 80).direct(payload)
                        socket.send(DatagramPacket(data, data.size, packet.socketAddress))
                    }
                }
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        fun session() = AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort))
        fun push(payload: ByteArray, id: Int = 0xba, attr: Int = 0x40, sequence: Int = 0x5566, sender: Int = 0xc9) {
            val bytes = AppEnvelope(envelopeSession, 80).direct(Protocol.duss(sender, 2, attr, 0x3f, id, payload, sequence))
            socket.send(DatagramPacket(bytes, bytes.size, checkNotNull(endpoint)))
        }
        override fun close() { alive.set(false); socket.close(); worker.join(1_000) }
    }

    private val program = LabProgram("pass", "0123456789abcdef0123456789abcdef", "0123456789abcdef")

    @Test fun customUiUsesNativeAckSequenceAndEventRouteAndClearsBetweenRuns(): Unit = runBlocking {
        Peer().use { peer -> peer.session().use { session ->
            session.connect(); session.labMode(true); session.setLabRun(program.guid)
            peer.push("00000000".hexBytes())
            peer.push("01000000".hexBytes(), sequence = 0x5567)
            peer.push("00008002010401000000010400000000".hexBytes(), sequence = 0x5568)
            peer.push(byteArrayOf(2) + program.guid.encodeToByteArray(), id = 0xa5, attr = 0, sender = 9)
            withTimeout(2_000) { while (session.labUi.state.value.widgets.size != 2 || !session.labUi.state.value.running) delay(5) }
            withTimeout(2_000) { while (peer.allFrames.count { it.id == 0xba && it.attr == 0x80 } < 3) delay(5) }
            val acks = peer.allFrames.filter { it.id == 0xba && it.attr == 0x80 }
            assertEquals(listOf(0x5566, 0x5567, 0x5568), acks.map { it.sequence })
            assertTrue(acks.all { it.valid && it.sender == 0x42 && it.receiver == 0xc9 && it.payload.hex() == "00" })
            val button = session.labUi.state.value.widgets.values.single { it.key.type.raw == 1 }
            session.labUi.setVisible(program.guid, true)
            withTimeout(2_000) { while (!session.labUi.state.value.inputEnabled) delay(5) }
            session.labUi.interact(program.guid, button.key, button.token, 1)
            session.labUi.setVisible(program.guid, false)
            withTimeout(2_000) { while (peer.received.count { it.id == 0xbb } < 2) delay(5) }
            val events = peer.received.filter { it.id == 0xbb }
            assertEquals(listOf("01000100", "01000200"), events.map { it.payload.hex() })
            assertTrue(events.all { it.sender == 2 && it.receiver == 0xc9 && it.set == 0x3f && it.attr == 0x40 })
            session.setLabRun(null)
            peer.push("0100800103046c617465".hexBytes(), sequence = 0x5570)
            withTimeout(2_000) { while (peer.allFrames.none { it.id == 0xba && it.sequence == 0x5570 }) delay(5) }
            assertTrue(session.labUi.state.value.widgets.isEmpty())
        } }
    }

    @Test fun closingAVisibleSessionReleasesAPressedProgramButtonBeforeSocketClose(): Unit = runBlocking {
        Peer().use { peer -> peer.session().use { session ->
            session.connect(); session.labMode(true); session.setLabRun(program.guid)
            peer.push("00000000".hexBytes()); peer.push("01000000".hexBytes())
            peer.push("00008002010401000000010400000000".hexBytes())
            peer.push(byteArrayOf(2) + program.guid.encodeToByteArray(), id = 0xa5, attr = 0, sender = 9)
            withTimeout(2_000) { while (session.labUi.state.value.widgets.size != 2 || !session.labUi.state.value.running) delay(5) }
            session.labUi.setVisible(program.guid, true)
            withTimeout(2_000) { while (!session.labUi.state.value.inputEnabled) delay(5) }
            val button = session.labUi.state.value.widgets.values.single { it.key.type.raw == 1 }
            session.labUi.interact(program.guid, button.key, button.token, 1)
            session.close()
            withTimeout(2_000) { while (peer.received.none { it.id == 0xbb && it.payload.hex() == "01000200" }) delay(5) }
            assertNull(session.labUi.state.value.runId)
        } }
    }

    @Test fun rawLabResponsesStillRequireExactPeerSequenceCommandAttributeAndCrc(): Unit = runBlocking {
        Peer().use { peer -> peer.session().use { session ->
            session.connect()
            peer.handler = { frame -> if (frame.receiver == 0xa9) listOf(
                Reply(byteArrayOf(0), sender = 9), Reply(byteArrayOf(0), receiver = 3),
                Reply(byteArrayOf(0), sequence = (frame.sequence + 1) and 65535),
                Reply(byteArrayOf(0), attr = 0), Reply(byteArrayOf(0), setOffset = 1),
                Reply(byteArrayOf(0), idOffset = 1), Reply(byteArrayOf(0), valid = false),
                Reply(byteArrayOf(1), attr = 0x80),
            ) else listOf(Reply(byteArrayOf(7))) }
            val reply = session.requestLab(0xa3, LabResourceProtocol.programQuery(program))
            assertContentEquals(byteArrayOf(1), reply)
            assertFalse(LabResourceProtocol.programMatches(reply))
            assertEquals(RequestProblem.REJECTED, assertFailsWith<RobotRequestException> { session.audio.read() }.problem)
            val query = peer.received.single { it.receiver == 0xa9 && it.id == 0xa3 }
            assertEquals(0x3f, query.set)
            assertEquals(49, query.payload.size)
            assertEquals(1, query.payload.u8(0))
            assertFailsWith<IllegalArgumentException> { session.requestLab(0xa1, byteArrayOf()) }
        } }
    }

    @Test fun actualLabStartWaitsForRegistrationAndProgramQueryBeforeExecution(): Unit = runBlocking {
        Peer().use { peer -> peer.session().use { session ->
            session.connect()
            val matches = AtomicBoolean(false)
            peer.handler = { frame -> listOf(Reply(if (frame.id == 0xa3 && frame.payload.u8(0) == 1 && !matches.get())
                byteArrayOf(1) else byteArrayOf(0))) }
            val controller = LabController(session, {})
            val upload = controller.upload("def start():\n    pass\n", "fixture")
            assertFailsWith<IllegalStateException> { controller.start() }
            assertFalse(peer.received.any { it.id == 0xa3 && it.payload.u8(0) == 0x52 })
            controller.stop()
            matches.set(true)
            assertEquals(upload.runId, controller.start())
            assertEquals(upload.runId, session.labUi.state.value.runId)
            withTimeout(2_000) { while (peer.received.none { it.id == 0xa3 && it.payload.u8(0) == 0x52 }) delay(5) }
            val frames = peer.received.filter { it.receiver == 0xa9 && it.id in listOf(0xa2, 0xa3) }
            assertEquals(listOf(0xa2, 0xa3, 0xa3), frames.takeLast(3).map { it.id })
            assertEquals(1, frames[frames.lastIndex - 1].payload.u8(0))
            assertEquals(0x52, frames.last().payload.u8(0))
            controller.stop()
            assertNull(session.labUi.state.value.runId)
        } }
    }

    @Test fun cancellationTimeoutAndCloseRetireLabRequestsAndIgnoreLateReplies(): Unit = runBlocking {
        Peer().use { peer -> peer.session().use { session ->
            session.connect()
            peer.handler = { emptyList() }
            fun queries() = peer.received.filter { it.receiver == 0xa9 && it.id == 0xa3 }
            val cancelled = async { session.requestLab(0xa3, LabResourceProtocol.programQuery(program)) }
            withTimeout(2_000) { while (queries().isEmpty()) delay(5) }
            val previous = queries().last().sequence
            cancelled.cancelAndJoin()
            peer.handler = { listOf(Reply(byteArrayOf(0), sequence = previous), Reply(byteArrayOf(1))) }
            assertContentEquals(byteArrayOf(1), session.requestLab(0xa3, LabResourceProtocol.programQuery(program)))
            peer.handler = { emptyList() }
            assertEquals(RequestProblem.TIMED_OUT,
                assertFailsWith<RobotRequestException> { session.requestLab(0xa3, LabResourceProtocol.programQuery(program)) }.problem)
            val mark = queries().size
            val closing = async { runCatching { session.requestLab(0xa3, LabResourceProtocol.programQuery(program)) } }
            withTimeout(2_000) { while (queries().size <= mark) delay(5) }
            session.close()
            assertEquals(RequestProblem.DISCONNECTED, (closing.await().exceptionOrNull() as RobotRequestException).problem)
        } }
    }
}
