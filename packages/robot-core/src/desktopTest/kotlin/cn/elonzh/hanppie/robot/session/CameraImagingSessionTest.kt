package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.media.*
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

class CameraImagingSessionTest {
    @Test fun cameraAndEncoderRequestsUseTheAppBrokerAndSourceSwitchAwaitsAPush(): Unit = runBlocking {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val running = AtomicBoolean(true)
        val requests = CopyOnWriteArrayList<DussFrame>()
        val worker = thread(isDaemon = true) {
            var photo = 0
            var index = 0
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535)
                socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                fun reply(sender: Int, set: Int, id: Int, attr: Int, payload: ByteArray, sequence: Int = 7) {
                    val data = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(sender, 2, attr, set, id, payload, sequence))
                    socket.send(DatagramPacket(data, data.size, packet.socketAddress))
                }
                for (frame in Protocol.frames(bytes).filter {
                    it.receiver == 1 && (it.set == 2 && it.id in listOf(0x12, 0xe3) || it.set == 8 && it.id == 0x20) ||
                        it.receiver == 14 && it.set == 1 && it.id == 1
                }) {
                    requests += frame
                    if (frame.set == 2 && frame.id == 0x12) photo = frame.payload.u8(0)
                    if (frame.set == 2 && frame.id == 0xe3) index = frame.payload.u8(0)
                    // A matching sequence from the wrong node must not reject this request.
                    reply(if (frame.receiver == 14) 1 else 14, frame.set, frame.id, 0xc0, byteArrayOf(1), frame.sequence)
                    reply(frame.receiver, frame.set, frame.id, 0xc0, byteArrayOf(0), frame.sequence)
                }
                reply(1, 2, 0xe4, 0, ByteArray(26).apply {
                    this[2] = 0x10; put16(5, 0x140 or (index shl 2)); this[7] = photo.toByte(); put16(8, 100)
                })
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        try {
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort)).use { session ->
                session.connect()
                withTimeout(3_000) { session.camera.state.first { it.imaging != null } }
                assertEquals(VideoResolution.R1080P, session.camera.state.value.imaging?.resolution)
                session.camera.setPhotoSize(CameraPhotoSize.R1440P)
                session.camera.setTransmissionRate(VideoTransmissionRate.GOOD)
                session.camera.requestKeyFrame()
                session.camera.switchCamera(1)
                assertEquals(1, session.camera.state.value.imaging?.cameraIndex)
                assertEquals(listOf("1/2/18/0201", "1/8/32/480d", "14/1/1/00000000002000000020", "1/2/227/01"),
                    requests.map { "${it.receiver}/${it.set}/${it.id}/${it.payload.hex()}" })
                assertTrue(requests.all { it.attr == 0x40 && it.sender == 2 })
            }
        } finally { running.set(false); socket.close(); worker.join(1_000) }
    }
}
