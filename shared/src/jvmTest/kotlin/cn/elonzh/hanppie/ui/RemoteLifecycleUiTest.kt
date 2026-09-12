package cn.elonzh.hanppie.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import cn.elonzh.hanppie.robot.RobotNetwork
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*
import org.junit.Rule
import org.junit.Test

class RemoteLifecycleUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun disconnectedDeviceHasNoCockpitEntry() {
        val model = ConsoleModel()
        try {
            rule.setContent { WorkbenchTheme { Console(model, mutableStateOf(EditorDocument())) } }
            rule.onNodeWithTag("enter-remote").assertDoesNotExist()
        } finally { model.close() }
    }

    @Test fun nativeWindowActivationRestoresSessionWithoutReplayingHeldKeys() {
        // Only loopback UDP. Exercise a real AppSession instead of manually setting remoteEnabled.
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { robot ->
            robot.soTimeout = 50
            val alive = AtomicBoolean(true)
            val responding = AtomicBoolean(true)
            val peer = thread(isDaemon = true) {
                while (alive.get()) {
                    try {
                        val packet = DatagramPacket(ByteArray(65535), 65535)
                        robot.receive(packet)
                        if (responding.get()) robot.send(DatagramPacket(packet.data, packet.length, packet.socketAddress))
                    } catch (_: SocketTimeoutException) { }
                    catch (_: SocketException) { break }
                }
            }
            val network = object : RobotNetwork {
                override fun datagram(): DatagramSocket = object : DatagramSocket(null as SocketAddress?) {
                    override fun bind(address: SocketAddress?) = super.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                    override fun connect(address: InetAddress, port: Int) = super.connect(address, robot.localPort)
                    override fun send(packet: DatagramPacket) {
                        packet.port = robot.localPort
                        super.send(packet)
                    }
                }
            }
            val model = ConsoleModel(SystemSpeech(), robotNetwork = { network })
            try {
                rule.setContent { WorkbenchTheme { RemotePage(model) } }
                model.connect("127.0.0.1", "12345678")
                rule.waitUntil(15_000) { model.remoteEnabled.value || model.state.value.error != null }
                assertNull(model.state.value.error, model.state.value.logs.joinToString("\n"))
                assertTrue(model.remoteEnabled.value)
                rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.W) }
                rule.waitUntil(3_000) { model.remoteInput.value[0] > 0 }
                rule.runOnIdle { model.setForeground(false) }
                rule.waitUntil(3_000) { !model.remoteEnabled.value && model.remoteInput.value.all { it == 0.0 } }
                rule.runOnIdle { model.setForeground(true) }
                rule.waitUntil(5_000) { model.remoteEnabled.value }
                rule.runOnIdle { assertTrue(model.remoteInput.value.all { it == 0.0 }, "Activation must not replay W") }
                rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.W); keyDown(Key.E) }
                rule.waitUntil(3_000) { model.remoteInput.value[2] == 90.0 }
                rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.E) }
                rule.waitUntil(3_000) { model.remoteInput.value.all { it == 0.0 } }

                // A session can also time out while the native window is inactive.
                rule.runOnIdle { model.setForeground(false); responding.set(false) }
                rule.waitUntil(8_000) { !model.state.value.connected }
                rule.runOnIdle { responding.set(true); model.setForeground(true) }
                rule.waitUntil(15_000) { model.state.value.connected && model.remoteEnabled.value }
                rule.runOnIdle { assertTrue(model.remoteInput.value.all { it == 0.0 }) }
            } finally { model.close(); alive.set(false); peer.join(1_000) }
        }
    }

    @Test fun cameraFrameUsesInverseChassisAngle() {
        assertEquals(-90.0, chassisHeadingInCameraFrame(90.0))
        assertEquals(90.0, chassisHeadingInCameraFrame(-90.0))
        assertNull(chassisHeadingInCameraFrame(null))
        assertNull(chassisHeadingInCameraFrame(Double.NaN))
        assertNull(chassisHeadingInCameraFrame(999.0))
    }
}
