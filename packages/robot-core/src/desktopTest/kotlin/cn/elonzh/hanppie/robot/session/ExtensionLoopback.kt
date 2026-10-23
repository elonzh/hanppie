package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.extensions.arm.ArmServoBinding
import cn.elonzh.hanppie.robot.protocol.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** A synthetic UDP peer exercises real envelope/request routing; it is not a robot emulator. */
internal class ExtensionLoopback : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
    val port = socket.localPort
    private val running = AtomicBoolean(true)
    val upgrade = AtomicBoolean(false)
    val frames = CopyOnWriteArrayList<DussFrame>()
    val controls get() = frames.filter { it.set == 0x33 && it.id == 0x10 }
    private var binding = ArmServoBinding(1, 2)
    private var mode = 0
    private var sequence = 300
    private val worker = thread(isDaemon = true) {
        while (running.get()) try {
            val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
            val bytes = packet.data.copyOf(packet.length)
            socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
            fun reply(node: Int, set: Int, id: Int, attr: Int, p: ByteArray, seq: Int = sequence++) {
                val data = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(node, 2, attr, set, id, p, seq))
                socket.send(DatagramPacket(data, data.size, packet.socketAddress))
            }
            val incoming = Protocol.frames(bytes)
            for (frame in incoming) {
                frames += frame
                var p = byteArrayOf(0)
                if (frame.set == 0x33) when (frame.id) {
                    0x68 -> p = byteArrayOf(0, binding.main.toByte(), binding.secondary.toByte())
                    0x67 -> binding = ArmServoBinding(frame.payload.u8(0), frame.payload.u8(1))
                    0x21 -> { mode = frame.payload.u8(0); reply(0x3b, 0x33, 0x22, 0, byteArrayOf(mode.toByte())) }
                }
                if (frame.attr == 0x40) reply(frame.receiver, frame.set, frame.id, 0xc0, p, frame.sequence)
            }
            if (incoming.isNotEmpty()) {
                reply(0x28, 0x3f, 0x12, 0, "061803001903001e03001f0300011200061600".hexBytes())
                reply(9, 0x48, 8, 0, "000f000000000000000000".hexBytes())
                reply(9, 0x48, 8, 0, "001001".hexBytes())
                val servo = ByteArray(19).apply { this[1] = 17; this[2] = 1; put16(3, -42); put16(11, 1024) }
                val tof = ByteArray(22).apply { this[1] = 18; this[2] = 1; this[4] = 1; this[5] = 4; this[6] = 0xd2.toByte() }
                val adapter = ByteArray(38).apply { this[1] = 19; this[32] = 1; put16(34, 1023); put16(36, 65535) }
                reply(9, 0x48, 8, 0, servo); reply(9, 0x48, 8, 0, tof); reply(9, 0x48, 8, 0, adapter)
                if (upgrade.get()) reply(0x28, 0, 0x42, 0, byteArrayOf(3, 25, 0))
            }
        } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
    }
    override fun close() { running.set(false); socket.close(); worker.join(1_000) }
}
