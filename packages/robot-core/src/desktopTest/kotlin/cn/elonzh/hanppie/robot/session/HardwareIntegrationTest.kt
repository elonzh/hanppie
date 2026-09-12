package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.lab.LabController
import cn.elonzh.hanppie.robot.lab.LabRunEvent
import cn.elonzh.hanppie.robot.lab.LabRunEventType
import cn.elonzh.hanppie.robot.lab.LabRunProtocol
import cn.elonzh.hanppie.robot.telemetry.Telemetry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Opt-in only. No target defaults, no motion, no firmware modification. */
class HardwareIntegrationTest {
    @Test fun explicitTargetConnectUploadAndStartNoMotionScript() = runBlocking {
        val ip = System.getenv("HANPPIE_TEST_ROBOT_IP")
        val appId = System.getenv("HANPPIE_TEST_APPID")
        assumeTrue("实机测试需要显式 IP、AppID 和 Lab 上传授权",
            !ip.isNullOrBlank() && !appId.isNullOrBlank() && System.getenv("HANPPIE_TEST_ALLOW_LAB") == "1")
        val target = RobotTarget(ip!!, appId!!)
        val events = java.util.concurrent.CopyOnWriteArrayList<LabRunEvent>()
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val session = AppSession(target, onFrame = { frame ->
            Telemetry.labMessage(frame)?.let { message ->
                LabRunProtocol.decode(message.text)?.let(events::add) ?: messages.add(message.text)
            }
        })
        var running = false
        var controller: LabController? = null
        try {
            session.connect()
            assertTrue(session.connected)
            val current = LabController(session, target)
            controller = current
            val upload = current.upload(
                "def start():\n    log_ctrl.print_msg('Hanppie Kotlin integration: no motion')\n",
                "Hanppie-NoMotion-Test",
            )
            assertEquals(upload.runId, current.start())
            running = true
            withTimeout(15_000) {
                while (events.none { it.runId == upload.runId && it.type == LabRunEventType.COMPLETED }) delay(50)
            }
            assertTrue(events.any { it.runId == upload.runId && it.type == LabRunEventType.STARTED })
            assertTrue(messages.any { it.contains("Hanppie Kotlin integration: no motion") })
            assertTrue(current.complete(upload.runId))
            running = false
            // Completion cleanup must make the single native slot immediately reusable.
            current.upload("def start():\n    pass\n", "Hanppie-Reuse-Test")
            println("S1 confirmed start, print forwarding, completion, cleanup, and slot reuse")
        } finally {
            if (running) runCatching { controller?.stop() }
            session.close()
        }
        Unit
    }
}
