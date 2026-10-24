package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.remote.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Synthetic UDP exercises the real session envelope, receive route and single writer. */
class GunCoolingSessionTest {
    private class Peer : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val port = socket.localPort
        val cooling = CopyOnWriteArrayList<DussFrame>()
        private data class Endpoint(val address: SocketAddress, val session: Int)
        private val endpoint = AtomicReference<Endpoint?>()
        private val alive = AtomicBoolean(true)
        private val worker = thread(isDaemon = true) {
            while (alive.get()) try {
                val packet = DatagramPacket(ByteArray(65535),65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes,bytes.size,packet.socketAddress))
                if (bytes.size < 4) continue
                endpoint.set(Endpoint(packet.socketAddress,bytes.u16(2)))
                for (frame in Protocol.frames(bytes))
                    if (frame.receiver == 9 && frame.set == 0x3f && frame.id == 0x5b) cooling += frame
            } catch (_: SocketTimeoutException) {} catch (_: SocketException) { break }
        }
        fun heat(value: Int) = send(0, 0x0e, ByteArray(15).apply { put16(12,value) }, 700)
        fun ack(frame: DussFrame) = send(0xc0, 0x5b, byteArrayOf(0), frame.sequence)
        private fun send(attr: Int, id: Int, body: ByteArray, sequence: Int) {
            val target = checkNotNull(endpoint.get())
            val data = AppEnvelope(target.session,80).direct(Protocol.duss(9,2,attr,0x3f,id,body,sequence))
            socket.send(DatagramPacket(data,data.size,target.address))
        }
        override fun close() { alive.set(false); socket.close(); worker.join(1_000) }
    }

    @Test fun coolingRequiresFreshZeroAndItsOwnResetAckAndRetiresOnExit(): Unit = runBlocking {
        suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }
        Peer().use { peer ->
            AppSession(RobotTarget("127.0.0.1","12345678",localPort = 0,remotePort = peer.port)).use { session ->
                session.connect(); session.enterRemote()
                waitFor { peer.cooling.isNotEmpty() }
                assertContentEquals(byteArrayOf(1), peer.cooling.single().payload, "Existing FPV entry reset stays unchanged")
                peer.heat(500); waitFor { session.gunCooling.value.canCool }
                session.coolGun(); waitFor { peer.cooling.size == 2 }
                val request = peer.cooling.last(); assertContentEquals(byteArrayOf(0), request.payload)
                assertFailsWith<IllegalStateException> { session.fireInfrared() }
                assertFailsWith<IllegalStateException> { session.fireGelOnce() }
                peer.ack(request); waitFor { session.gunCooling.value.phase == GunCoolingPhase.COOLING }
                peer.heat(0); waitFor { peer.cooling.size == 3 }
                val reset = peer.cooling.last(); assertContentEquals(byteArrayOf(1), reset.payload)
                peer.ack(request); delay(50); assertEquals(GunCoolingPhase.RESETTING,session.gunCooling.value.phase)
                peer.ack(reset); waitFor { !session.gunCooling.value.ownsCooling }
                session.fireInfrared()
                peer.heat(500); waitFor { session.gunCooling.value.canCool }
                session.coolGun(); waitFor { peer.cooling.size == 4 }
                session.exitRemote()
                assertEquals(GunCoolingState(), session.gunCooling.value)
                peer.ack(peer.cooling.last()); peer.heat(0); delay(50)
                assertEquals(GunCoolingState(), session.gunCooling.value)
                session.enterRemote(); waitFor { peer.cooling.size == 5 }
                assertContentEquals(byteArrayOf(1),peer.cooling.last().payload)
                assertFalse(session.gunCooling.value.ownsCooling)
            }
        }
    }
}
