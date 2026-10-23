package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.maintenance.CalibrationKind
import cn.elonzh.hanppie.robot.maintenance.ModuleAddressingKind
import cn.elonzh.hanppie.robot.maintenance.firmware.*
import cn.elonzh.hanppie.robot.network.WifiChannelMode
import cn.elonzh.hanppie.robot.product.RobotPowerAction
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import kotlinx.io.Buffer
import java.net.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

/** Synthetic App UDP peer plus a real loopback FTP control/data connection; never reaches a robot. */
class RobotFirmwareSessionTest {
    @Test fun negotiatedRealFtpAndEarlyReportsReachTheTaskWhileOtherOwnersStayBlocked(): Unit = runBlocking {
        val bundle = firmwarePackageFixture()
        val received = CompletableFuture<ByteArray>(); val ftp = ServerSocket(0); val paths = CopyOnWriteArrayList<String>()
        val ftpThread = thread(isDaemon = true) {
            try { ftp.accept().use { control ->
                control.soTimeout = 3000
                val reader = control.getInputStream().bufferedReader(); val writer = control.getOutputStream().bufferedWriter()
                fun reply(text: String) { writer.write("$text\r\n"); writer.flush() }
                var data: ServerSocket? = null
                try {
                    reply("220 firmware fixture")
                    while (true) {
                        val command = reader.readLine() ?: break
                        when {
                            command.startsWith("USER ") -> reply("331 password")
                            command.startsWith("PASS") -> reply("230 logged in")
                            command == "TYPE I" -> reply("200 binary")
                            command == "PASV" -> {
                                data = ServerSocket(0).apply { soTimeout = 3000 }
                                val port = data.localPort
                                reply("227 Entering Passive Mode (127,0,0,1,${port / 256},${port % 256})")
                            }
                            command.startsWith("STOR ") -> {
                                paths += command.removePrefix("STOR "); reply("150 ready")
                                val bytes = checkNotNull(data).accept().use { it.getInputStream().readBytes() }
                                data.close(); data = null; received.complete(bytes); reply("226 complete")
                            }
                            else -> reply("500 unexpected")
                        }
                    }
                } finally { data?.close() }
            } } catch (error: Throwable) { received.completeExceptionally(error) }
        }
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 30 }
        val running = AtomicBoolean(true); val installed = AtomicBoolean(false); val requests = CopyOnWriteArrayList<DussFrame>()
        val worker = thread(isDaemon = true) {
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                fun reply(node: Int, set: Int, id: Int, attr: Int, payload: ByteArray, sequence: Int) {
                    val data = AppEnvelope(bytes.u16(2), 80).direct(Protocol.duss(node, 2, attr, set, id, payload, sequence))
                    socket.send(DatagramPacket(data, data.size, packet.socketAddress))
                }
                reply(0x28, 0x3f, 0x12, 0, byteArrayOf(5) + (0x0314..0x0317).flatMap { listOf(it.toByte(), (it ushr 8).toByte(), 0.toByte()) }.toByteArray() + byteArrayOf(10, 3, 0), 70)
                reply(9, 0x48, 8, 0, ByteArray(62).apply { this[1] = 1; this[10] = 80 }, 71)
                for (frame in Protocol.frames(bytes).filter { it.receiver == 0x28 && it.set == 0 && it.id in setOf(0x4f, 7, 8, 0x0a, 0x41) }) {
                    requests += frame
                    val p = when (frame.id) {
                        0x4f -> {
                            val cfg = if (installed.get()) bundle.configuration else bundle.configuration.decodeToString().replace("00.06.0521", "00.06.0520").encodeToByteArray()
                            byteArrayOf(0) + ByteArray(4) { (cfg.size ushr (it * 8)).toByte() } + ByteArray(4) + cfg
                        }
                        8 -> ByteArray(71).apply {
                            this[1] = 1; this[4] = 127
                            this[5] = ftp.localPort.toByte(); this[6] = (ftp.localPort ushr 8).toByte()
                            "/stage/negotiated.tar".encodeToByteArray().copyInto(this, 7)
                        }
                        0x0a -> {
                            installed.set(true)
                            reply(8, 0, 0x42, 0, byteArrayOf(3, 100, 0), 72)
                            reply(0x28, 0, 0x42, 0, byteArrayOf(4, 1, 0), 73) // A terminal without this round's progress is insufficient.
                            reply(0x28, 0, 0x42, 0, byteArrayOf(3, 75, 0x29), 74)
                            reply(0x28, 0, 0x42, 0, byteArrayOf(4, 1, 0), 75)
                            byteArrayOf(0xf7.toByte())
                        }
                        else -> byteArrayOf(0)
                    }
                    reply(frame.receiver, frame.set, frame.id, 0xc0, p, frame.sequence)
                }
            } catch (_: SocketTimeoutException) { } catch (_: SocketException) { break }
        }
        try {
            AppSession(RobotTarget("127.0.0.1", "12345678", localPort = 0, remotePort = socket.localPort)).use { session ->
                session.connect()
                withTimeout(3_000) { while (session.health.value.battery == null || session.health.value.modules == null) delay(5) }
                session.firmware.refreshConfiguration()
                val current = assertNotNull(session.firmware.state.value.configuration)
                session.firmware.install(bundle.inspection, current, FirmwareInstallConsent()) { Buffer().apply { write(bundle.archive) } }
                assertContentEquals(bundle.archive, received.get(3, TimeUnit.SECONDS))
                assertEquals(listOf("/stage/negotiated.tar"), paths)
                assertEquals(FirmwarePhase.REPORTED_SUCCESS, session.firmware.state.value.phase)
                assertEquals(0xf7, session.firmware.state.value.transferAcceptedCode)
                assertTrue(session.firmware.state.value.blocksControl)
                assertFailsWith<IllegalStateException> { session.enterRemote() }
                assertFailsWith<IllegalStateException> { session.labMode(true) }
                assertFailsWith<IllegalStateException> { session.addressing.begin(ModuleAddressingKind.ARMOR) }
                assertFailsWith<IllegalStateException> { session.calibration.begin(CalibrationKind.GIMBAL) }
                assertFailsWith<IllegalStateException> { session.wifi.setChannelMode(WifiChannelMode.AUTO) {} }
                assertFailsWith<IllegalStateException> { session.power.perform(RobotPowerAction.SHUTDOWN) {} }
                assertFailsWith<IllegalStateException> { session.media(true) }
                assertFailsWith<IllegalStateException> { session.camera.takePhoto() }
                session.safetyStop(); assertTrue(session.firmware.state.value.blocksControl)
                session.firmware.refreshConfiguration()
                assertEquals(FirmwarePhase.VERIFIED, session.firmware.state.value.phase)
                assertFalse(session.firmware.state.value.blocksControl)
                withTimeout(1_000) { while (requests.none { it.id == 0x41 }) delay(5) }
            }
        } finally {
            running.set(false); socket.close(); ftp.close(); worker.join(1000); ftpThread.join(1000)
        }
    }
}
