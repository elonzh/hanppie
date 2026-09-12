package cn.elonzh.hanppie.robot.remote

import cn.elonzh.hanppie.robot.session.AppSession
import cn.elonzh.hanppie.robot.session.RobotTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Explicitly enabled single gel shot through the same direct path used by the cockpit. */
class RemoteGelLiveTest {
    @Test fun directGelShotWithMuzzleEffect() = runBlocking {
        val ip = System.getenv("HANPPIE_TEST_ROBOT_IP")
        val appId = System.getenv("HANPPIE_TEST_APPID")
        assumeTrue(
            "实机水弹测试需要显式 IP、AppID 和单发授权",
            !ip.isNullOrBlank() && !appId.isNullOrBlank() && System.getenv("HANPPIE_TEST_ALLOW_GEL") == "1",
        )
        val session = AppSession(RobotTarget(ip!!, appId!!))
        try {
            session.connect()
            session.safetyStop()
            session.enterRemote()
            val sequence = session.fireGelOnce()
            assertTrue(sequence in 0..65535)
            delay(500)
            println("Direct gel command sequence=$sequence; muzzle effect wrapped the command for 200 ms")
        } finally {
            runCatching { session.safetyStop() }
            runCatching { session.exitRemote() }
            session.close()
        }
    }
}
