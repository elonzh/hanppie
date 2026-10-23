package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.lab.mobile.*
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class MobileInfoSessionTest {
    private class Source : MobileSensorSource {
        override val availableMask = 7
        @Volatile var active = 0
        @Volatile var samples = emptyList<MobileSensorReading>()
        override fun start(mask: Int) { active = mask }
        override fun read() = samples
        override fun stop() { active = 0; samples = emptyList() }
    }
    private class Peer : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        private val running = AtomicBoolean(true)
        private val echo = AtomicBoolean(true)
        val calls = CopyOnWriteArrayList<DussFrame>()
        @Volatile private var address: SocketAddress? = null
        @Volatile private var sessionId = 0
        private val worker = thread(isDaemon=true) {
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535),65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                address = packet.socketAddress; sessionId = bytes.u16(2)
                if (echo.get()) socket.send(DatagramPacket(bytes,bytes.size,packet.socketAddress))
                calls.addAll(Protocol.frames(bytes))
            } catch (_: SocketTimeoutException) {} catch (_: SocketException) { break }
        }
        fun subscribe(mask: Int, sequence: Int, sender: Int = 0xc9) {
            val bytes = AppEnvelope(sessionId,80).direct(Protocol.duss(sender,2,0x40,0x3f,0xab,
                byteArrayOf(mask.toByte(),(mask ushr 8).toByte()),sequence))
            socket.send(DatagramPacket(bytes,bytes.size,checkNotNull(address)))
        }
        fun pauseReplies() { echo.set(false) }
        fun target() = RobotTarget("127.0.0.1","12345678",localPort=0,remotePort=socket.localPort)
        override fun close() { running.set(false); socket.close(); worker.join(1000) }
    }
    private suspend fun until(predicate: () -> Boolean) = withTimeout(3000) { while (!predicate()) delay(5) }

    @Test fun actualSessionRoutesSubscriptionDataAndClearsForegroundRunAndExpiredLink(): Unit = runBlocking {
        val source = Source()
        Peer().use { peer -> AppSession(peer.target(),mobileSensors=source).use { app ->
            app.connect(); app.labMode(true); app.setLabRun("0123456789abcdef0123456789abcdef")
            peer.subscribe(15,6000,sender=9); delay(40)
            assertTrue(peer.calls.none { it.id==0xab && it.sequence==6000 })
            peer.subscribe(15,6001)
            until { peer.calls.any { it.id==0xab && it.sequence==6001 } }
            val ack = peer.calls.single { it.id==0xab && it.sequence==6001 }
            assertEquals(2,ack.sender); assertEquals(0xc9,ack.receiver); assertEquals(0x80,ack.attr)
            assertContentEquals(byteArrayOf(0),ack.payload); assertEquals(7,source.active)
            source.samples=listOf(MobileSensorReading(MobileSensor.ACCELERATION,1f,-2f,0.5f,1,0),
                MobileSensorReading(MobileSensor.ATTITUDE,30f,340f,90f,1,0),
                MobileSensorReading(MobileSensor.ROTATION_RATE,0.25f,-0.5f,1f,1,0))
            until { peer.calls.any { it.id==0xac } }
            val push = peer.calls.first { it.id==0xac }
            assertEquals(2,push.sender); assertEquals(0xc9,push.receiver); assertEquals(0,push.attr)
            assertEquals("010c0000803f000000c00000003f020c0000f0410000aa430000b442030c0000803e000000bf0000803f",push.payload.hex())
            java.io.File("build/reports/mobile-info-push.bin").apply { parentFile.mkdirs(); writeBytes(push.payload) }
            app.setMobileSensorsActive(false); assertEquals(0,source.active)
            val count=peer.calls.count { it.id==0xac }
            app.setMobileSensorsActive(true)
            source.samples=listOf(MobileSensorReading(MobileSensor.ACCELERATION,2f,2f,2f,2,0))
            delay(140); assertEquals(count,peer.calls.count { it.id==0xac })
            peer.subscribe(4,6002); until { source.active==4 }
            app.setLabRun(null); assertEquals(0,source.active)
            peer.subscribe(7,6003); until { peer.calls.any { it.id==0xab && it.sequence==6003 } }
            assertEquals(1,peer.calls.single { it.id==0xab && it.sequence==6003 }.payload.u8(0))
            app.setLabRun("f".repeat(32)); peer.subscribe(1,6004); until { source.active==1 }
            peer.pauseReplies(); until { source.active==0 }
            app.close(); assertEquals(0,source.active)
        } }
    }

    @Test fun desktopRespondsUnsupportedToTheRealRequestAndHasNoUnsolicitedAckOrReadings(): Unit = runBlocking {
        Peer().use { peer -> AppSession(peer.target()).use { app ->
            app.connect(); app.labMode(true); app.setLabRun("f".repeat(32))
            delay(40); assertTrue(peer.calls.none { it.id==0xab || it.id==0xac })
            peer.subscribe(15,6100)
            until { peer.calls.any { it.id==0xab && it.sequence==6100 } }
            assertContentEquals(byteArrayOf(1),peer.calls.single { it.id==0xab && it.sequence==6100 }.payload)
            delay(140); assertTrue(peer.calls.none { it.id==0xac })
        } }
    }
}
