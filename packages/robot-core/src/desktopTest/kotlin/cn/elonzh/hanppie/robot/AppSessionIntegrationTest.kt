package cn.elonzh.hanppie.robot

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.thread
import kotlin.test.*

class AppSessionIntegrationTest {
    @Test fun loopbackHandshakeSetupTelemetryAndClose() = runBlocking {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { robot ->
            robot.soTimeout = 100
            val alive = AtomicBoolean(true)
            val sent = CopyOnWriteArrayList<DussFrame>()
            val received = CopyOnWriteArrayList<DussFrame>()
            val losses = CopyOnWriteArrayList<String>()
            var routedSockets = 0
            val network = object : RobotNetwork {
                override fun datagram() = DatagramSocket(null).also { routedSockets++ }
            }
            val responder = thread(isDaemon = true) {
                while (alive.get()) {
                    val packet = DatagramPacket(ByteArray(65535), 65535)
                    try {
                        robot.receive(packet)
                        val bytes = packet.data.copyOf(packet.length)
                        sent.addAll(Protocol.frames(bytes))
                        // Session matching and decoding are exercised using the same envelope as a peer.
                        robot.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                    } catch (_: SocketTimeoutException) { }
                    catch (_: java.net.SocketException) { break }
                }
            }
            val session = AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0,
                remotePort = robot.localPort), onFrame = { received.add(it) }, network = network, onLost = { losses.add(it) })
            try {
                session.connect()
                assertTrue(session.connected)
                assertEquals(2, routedSockets, "Both identity and session must use the selected network")
                withTimeout(2000) { while (received.isEmpty()) delay(10) }
                assertTrue(sent.all { it.valid })
                assertTrue(sent.any { it.set == 0x48 && it.id == 3 })
                assertTrue(sent.filter { it.set == 1 && it.id == 4 }.all { it.payload.contentEquals(Protocol.neutral) })
                session.enterRemote()
                val idleMark = sent.size
                session.drive(-0.0,0.0,-0.0,0.0,0.0)
                delay(100)
                assertTrue(sent.drop(idleMark).any { it.receiver == 0xc3 && it.set == 0x3f && it.id == 0x20 && it.payload.contentEquals(ByteArray(8)) })
                assertFalse(sent.drop(idleMark).any { it.set == 0x3f && it.id == 0x21 })
                assertTrue(sent.drop(idleMark).filter { it.set == 1 && it.id == 4 }.all { it.payload.contentEquals(Protocol.neutral) })
                assertTrue(sent.drop(idleMark).any { it.set == 4 && it.id == 0x0c && it.payload.contentEquals(RemoteControl.gimbalVelocity(0.0,0.0)) })
                session.drive(.2,0.0,0.0,12.0,0.0,cameraRelative=true)
                delay(100)
                assertContentEquals(ByteArray(8),sent.last { it.set == 0x3f && it.id in listOf(0x20,0x21) }.payload, "Missing orientation must stop camera-relative translation")
                assertContentEquals(RemoteControl.gimbalVelocity(12.0,0.0),sent.last { it.set == 4 && it.id == 0x0c }.payload)
                session.send(9,0,0x48,8,byteArrayOf(0,10,0,0,0,0,0x84.toByte(),3,0,0,0))
                withTimeout(1000) { while(session.cameraYaw == null) delay(5) }
                session.drive(.2,0.0,0.0,0.0,0.0,cameraRelative=true)
                delay(80)
                val expected = RemoteControl.cameraVelocity(.2,0.0,90.0)
                assertContentEquals(RemoteControl.velocity(expected.first,expected.second,0.0),sent.last { it.set == 0x3f && it.id == 0x21 }.payload)
                session.drive(0.0,0.0,0.0,0.0,30.0,cameraRelative=true)
                delay(60)
                assertContentEquals(RemoteControl.velocity(0.0,0.0,60.0),sent.last { it.set == 0x3f && it.id == 0x21 }.payload)
                session.halt(); delay(60)
                assertContentEquals(ByteArray(8),sent.last { it.set == 0x3f && it.id in listOf(0x20,0x21) }.payload)
                assertContentEquals(RemoteControl.gimbalVelocity(0.0,0.0),sent.last { it.set == 4 && it.id == 0x0c }.payload)
                repeat(6) { session.drive(.2,0.0,0.0,0.0,0.0,cameraRelative=true); delay(100) }
                assertNull(session.cameraYaw)
                assertContentEquals(ByteArray(8),sent.last { it.set == 0x3f && it.id in listOf(0x20,0x21) }.payload,"Stale telemetry stops even with fresh input")
                session.drive(0.0,0.0,0.0,0.0,30.0,cameraRelative=true); delay(60)
                assertContentEquals(ByteArray(8),sent.last { it.set == 0x3f && it.id in listOf(0x20,0x21) }.payload)
                assertContentEquals(RemoteControl.gimbalVelocity(0.0,0.0),sent.last { it.set == 4 && it.id == 0x0c }.payload)
                session.drive(.2,0.0,0.0,0.0,0.0)
                delay(100)
                assertTrue(sent.any { it.receiver == 0xc3 && it.set == 0x3f && it.id == 0x21 && it.payload.contentEquals(RemoteControl.velocity(.2,0.0,0.0)) })
                delay(350)
                assertContentEquals(Protocol.neutral, sent.last { it.set == 1 && it.id == 4 }.payload)
                assertContentEquals(ByteArray(8),sent.last { it.set == 0x3f && it.id in listOf(0x20,0x21) }.payload)
                val gimbalMark = sent.size
                session.drive(0.0,0.0,0.0,0.0,12.0)
                delay(100)
                assertTrue(sent.drop(gimbalMark).filter { it.set == 1 && it.id == 4 }.all { it.payload.contentEquals(Protocol.neutral) })
                assertTrue(sent.drop(gimbalMark).any { it.set == 4 && it.id == 0x0c && it.payload.contentEquals(RemoteControl.gimbalVelocity(0.0,12.0)) })
                session.drive(0.0,0.0,0.0,0.0,0.0)
                delay(100)
                assertContentEquals(RemoteControl.gimbalVelocity(0.0,0.0),sent.last { it.set == 4 && it.id == 0x0c }.payload)
                val stoppedCount = sent.count { it.set == 4 && it.id == 0x0c }
                delay(100)
                assertTrue(sent.count { it.set == 4 && it.id == 0x0c } > stoppedCount)
                val fireMark=sent.size
                session.fireGelOnce()
                delay(100)
                assertEquals(1,sent.drop(fireMark).count { it.receiver==9 && it.set==0x3f && it.id==0x51 && it.payload.contentEquals(byteArrayOf(1)) })
                assertFalse(sent.drop(fireMark).any { it.id in listOf(0xa1,0xa2,0xa3) },"Firing must not upload Lab")
                session.exitRemote()
                alive.set(false)
                responder.join(1000)
                withTimeout(7000) { while (session.connected) delay(10) }
                assertEquals(1, losses.size)
                assertTrue(losses.single().contains("5 秒"))
            } finally {
                session.close(); alive.set(false); responder.join(1000)
            }
            assertFalse(session.connected)
            assertFailsWith<IllegalStateException> { session.send(9, 0, 1, 4) }
            Unit
        }
    }
}
