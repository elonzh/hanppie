package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.lab.LabController
import cn.elonzh.hanppie.robot.lab.LabScriptStatus
import cn.elonzh.hanppie.robot.telemetry.Telemetry
import cn.elonzh.hanppie.robot.product.RobotModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class HardwareIntegrationTest {
    @Test fun explicitTargetReportsProductTypeWithoutMotion() = runBlocking {
        val ip = System.getenv("HANPPIE_TEST_ROBOT_IP")
        val appId = System.getenv("HANPPIE_TEST_APPID")
        assumeTrue("实机产品识别需要显式 IP 和 AppID", !ip.isNullOrBlank() && !appId.isNullOrBlank())
        val session = AppSession(RobotTarget(ip!!, appId!!))
        try {
            session.connect()
            withTimeout(3_000) {
                while (session.product.model == RobotModel.UNKNOWN) delay(25)
            }
            assertTrue(session.product.model != RobotModel.UNKNOWN)
            println("Product query reported ${session.product.model}; working devices=${session.product.capabilities.workingDevices.size}")
        } finally {
            session.close()
        }
    }

    @Test fun explicitTargetConnectUploadAndStartNoMotionScript() = runBlocking {
        val ip = System.getenv("HANPPIE_TEST_ROBOT_IP")
        val appId = System.getenv("HANPPIE_TEST_APPID")
        assumeTrue("实机测试需要显式 IP、AppID 和 Lab 上传授权",
            !ip.isNullOrBlank() && !appId.isNullOrBlank() && System.getenv("HANPPIE_TEST_ALLOW_LAB") == "1")
        val target = RobotTarget(ip!!, appId!!)
        val statuses = java.util.concurrent.CopyOnWriteArrayList<LabScriptStatus>()
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val session = AppSession(target, onFrame = { frame ->
            Telemetry.labScriptStatus(frame)?.let(statuses::add)
            Telemetry.labMessage(frame)?.let { messages.add(it.text) }
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
                while (statuses.none { it.guid.equals(upload.runId, ignoreCase = true) && it.isRunning }) delay(50)
                val afterStartIndex = statuses.size
                while (statuses.drop(afterStartIndex).none { it.isIdle }) delay(50)
                while (messages.none { it.contains("Hanppie Kotlin integration: no motion") }) delay(50)
            }
            assertTrue(statuses.any { it.guid.equals(upload.runId, ignoreCase = true) && it.isRunning })
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
