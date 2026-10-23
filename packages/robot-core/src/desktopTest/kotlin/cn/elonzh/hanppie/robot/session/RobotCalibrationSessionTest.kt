package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.network.WifiChannelMode
import cn.elonzh.hanppie.robot.product.RobotPowerAction
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class RobotCalibrationSessionTest {
    @Test fun calibrationReportsReachTheTaskBeforeAckAndCloseStopsImuBeforeRetiringTheSocket(): Unit = runBlocking {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val running = AtomicBoolean(true); val requests = CopyOnWriteArrayList<DussFrame>()
        val worker = thread(isDaemon = true) {
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                fun reply(node: Int, set: Int, id: Int, attr: Int, p: ByteArray, sequence: Int) {
                    val data = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(node, 2, attr, set, id, p, sequence))
                    socket.send(DatagramPacket(data, data.size, packet.socketAddress))
                }
                reply(0x28, 0x3f, 0x12, 0, "020004001e0300".hexBytes(), 70)
                for (frame in Protocol.frames(bytes).filter { it.receiver == 3 && it.set == 3 && it.id == 0xf9 ||
                    it.receiver == 4 && it.set == 4 && it.id == 8 }) {
                    requests += frame
                    if (frame.set == 3 && frame.payload.lastOrNull() == 1.toByte()) {
                        val p = ByteArray(62).apply { this[1] = 1; this[12] = 3; this[13] = 25; this[15] = 4 }
                        reply(3, 0x48, 8, 0, p, 71) // wrong source must not become the report
                        reply(9, 0x48, 8, 0, p, 72)
                    }
                    if (frame.set == 4) reply(4, 4, 0x30, 0, byteArrayOf(30, 1), 73)
                    reply(frame.receiver, frame.set, frame.id, 0xc0, byteArrayOf(0), frame.sequence)
                }
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        try {
            val session = AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort))
            session.use {
                session.connect()
                withTimeout(2_000) { while (session.health.value.modules == null) delay(5) }
                session.calibration.begin(CalibrationKind.CHASSIS_AND_GIMBAL)
                assertEquals(3, session.calibration.state.value.imuStatusRaw); assertEquals(25, session.calibration.state.value.imuProgressRaw)
                assertTrue(session.calibration.state.value.blocksControl)
                assertFailsWith<IllegalStateException> { session.enterRemote() }
                assertFailsWith<IllegalStateException> { session.labMode(true) }
                assertFailsWith<IllegalStateException> { session.wifi.setChannelMode(WifiChannelMode.AUTO) {} }
                assertFailsWith<IllegalStateException> { session.power.perform(RobotPowerAction.SHUTDOWN) {} }
                assertFailsWith<IllegalStateException> { session.addressing.begin(ModuleAddressingKind.ARMOR) }
                assertFailsWith<IllegalStateException> { session.media(true) }
                assertFailsWith<IllegalStateException> { session.camera.takePhoto() }
                assertFailsWith<IllegalStateException> { session.camera.setRecording(true) }
                session.safetyStop()
                assertTrue(session.calibration.state.value.blocksControl, "A velocity zero is not native calibration cancellation")
                session.close()
                withTimeout(1_000) { while (requests.count { it.set == 3 && it.payload.lastOrNull() == 3.toByte() } < 2) delay(5) }
                assertEquals(CalibrationPhase.CLOSED, session.calibration.state.value.phase)
                assertEquals(CalibrationProblem.REQUEST_UNKNOWN, session.calibration.state.value.problem)
                assertFailsWith<RobotRequestException> { session.calibration.cancel() }
            }
        } finally { running.set(false); socket.close(); worker.join(1_000) }
    }
}
