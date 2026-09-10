package cn.elonzh.hanppie.robot

import kotlinx.coroutines.*
import kotlin.test.*
import org.junit.Assume.assumeTrue
import java.util.concurrent.CopyOnWriteArrayList

/** Explicitly enabled short gimbal motion only; never fires or drives the chassis. */
class RemoteOrientationLiveTest {
    @Test fun gimbalOrientationAndIdle() = runBlocking {
        val ip = System.getenv("HANPPIE_TEST_ROBOT_IP")
        val appId = System.getenv("HANPPIE_TEST_APPID")
        assumeTrue(!ip.isNullOrBlank() && !appId.isNullOrBlank() && System.getenv("HANPPIE_TEST_ALLOW_REMOTE") == "1")
        val samples = CopyOnWriteArrayList<Double>()
        val signalQuality = CopyOnWriteArrayList<Int>()
        val session = AppSession(RobotTarget(ip!!,appId!!),onFrame={
            Telemetry.gimbalYaw(it)?.let(samples::add)
            Telemetry.wifiSignalQuality(it)?.let(signalQuality::add)
            if(it.set==0x48 && it.id==8 && it.payload.size==11) println("gimbal=${it.payload.hex()}")
        })
        try {
            session.connect(); session.enterRemote()
            delay(1500)
            assertNotNull(session.cameraYaw,"Relative yaw subscription")
            samples.clear(); delay(3000)
            assertTrue(samples.size > 5)
            println("signal quality=${signalQuality.takeLast(10)}")
            assertTrue(signalQuality.isNotEmpty(), "Missing native Wi-Fi signal-quality push")
            println("idle range=${samples.minOrNull()}..${samples.maxOrNull()}")
            assertTrue(samples.maxOrNull()!! - samples.minOrNull()!! < 1.0,"Idle yaw drift must stay below 1 degree / 3 seconds")
            val before = session.cameraYaw!!
            session.drive(0.0,0.0,0.0,0.0,90.0)
            delay(200); session.halt(); delay(800)
            val after = session.cameraYaw!!
            println("right yaw before=$before after=$after")
            assertTrue(after > before + 5.0,"90 deg/s right input must visibly increase clockwise relative yaw")
            samples.clear(); delay(3000)
            println("post-stop range=${samples.minOrNull()}..${samples.maxOrNull()}")
            assertTrue(samples.maxOrNull()!! - samples.minOrNull()!! < 1.0)
        } finally { session.halt(); session.close() }
    }
}
