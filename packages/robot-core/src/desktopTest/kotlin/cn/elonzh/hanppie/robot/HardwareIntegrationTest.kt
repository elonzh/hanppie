package cn.elonzh.hanppie.robot

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
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
        val session = AppSession(target)
        try {
            session.connect()
            assertTrue(session.connected)
            val controller = LabController(session, target)
            controller.upload("def start():\n    print('Hanppie Kotlin integration: no motion')\n", "Hanppie-NoMotion-Test")
            try { controller.start() } finally { controller.stop() }
            // This test verifies transport/lifecycle, not an unobserved physical effect or print delivery.
        } finally { session.close() }
    }
}
