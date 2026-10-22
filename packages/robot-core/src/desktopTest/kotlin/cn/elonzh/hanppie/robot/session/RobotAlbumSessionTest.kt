package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.media.album.RobotMediaEntry
import cn.elonzh.hanppie.robot.media.album.RobotMediaPart
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class RobotAlbumSessionTest {
    @Test fun appSessionRoutesNativeAlbumDataBeforeAckAndTransfersOriginalAndThumbnail(): Unit = runBlocking {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val running = AtomicBoolean(true)
        val requests = CopyOnWriteArrayList<DussFrame>()
        val worker = thread(isDaemon = true) {
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535)
                socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                fun reply(set: Int, id: Int, attr: Int, data: ByteArray, sequence: Int = 7) {
                    val encoded = AppEnvelope(bytes.u16(2), 80).direct(
                        Protocol.duss(sender = 1, receiver = 2, attr = attr, set = set, id = id, payload = data, sequence = sequence))
                    socket.send(DatagramPacket(encoded, encoded.size, packet.socketAddress))
                }
                reply(2, 0xe4, 0, ByteArray(26).apply { this[2] = 0x10; put16(5, 0xc0); put16(8, 100) })
                for (frame in Protocol.frames(bytes).filter { it.set == 0 && it.id == 0x26 }) {
                    requests += frame
                    val p = frame.payload
                    if (p.u8(1) != 0x22) {
                        val list = p.u8(1) == 0
                        val content = byteArrayOf(1, 2, 3, 4).take(if (!list && p.u8(16) == 1) 3 else 4).toByteArray()
                        val body = if (list) ByteArray(26).apply {
                            put32(0, 1); put32(4, 26); put32(12, 4); put32(16, 1)
                        } else ByteArray(13 + content.size).apply {
                            put32(0, 1); put32(4, size); content.copyInto(this, 13)
                        }
                        reply(0, 0x27, 0, ByteArray(10 + body.size).apply {
                            this[0] = 0x4a; this[1] = if (list) 1 else 0x21
                            put16(2, size or 0x1000); put16(4, p.u16(4)); body.copyInto(this, 10)
                        })
                    }
                    reply(0, 0x26, 0xc0, byteArrayOf(0), frame.sequence)
                }
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        try {
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort)).use { session ->
                session.connect()
                withTimeout(3_000) { session.camera.state.first { it.storage?.inserted == true } }
                val entry = session.album.list().single()
                assertEquals(RobotMediaEntry(1, 4, 0), entry)
                val thumb = Buffer(); session.album.download(entry, RobotMediaPart.THUMBNAIL, thumb)
                assertContentEquals(byteArrayOf(1, 2, 3), thumb.readByteArray())
                val original = Buffer(); session.album.download(entry, RobotMediaPart.ORIGINAL, original)
                assertContentEquals(byteArrayOf(1, 2, 3, 4), original.readByteArray())
                assertEquals(listOf(0, 0x22, 0x20, 0x22, 0x20, 0x22), requests.map { it.payload.u8(1) })
                assertTrue(requests.all { it.receiver == 1 && it.attr == Protocol.ATTR_NEED_ACK })
            }
        } finally { running.set(false); socket.close(); worker.join(1_000) }
    }

    private fun ByteArray.put32(at: Int, value: Int) { repeat(4) { this[at + it] = (value ushr (it * 8)).toByte() } }
}
