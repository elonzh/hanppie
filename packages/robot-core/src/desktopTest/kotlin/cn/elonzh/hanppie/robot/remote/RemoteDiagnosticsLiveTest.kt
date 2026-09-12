package cn.elonzh.hanppie.robot.remote

import cn.elonzh.hanppie.robot.protocol.hexBytes
import cn.elonzh.hanppie.robot.protocol.u16
import cn.elonzh.hanppie.robot.session.AppSession
import cn.elonzh.hanppie.robot.session.RobotTarget
import cn.elonzh.hanppie.robot.telemetry.Telemetry
import kotlinx.coroutines.*
import kotlin.test.*
import org.junit.Assume.assumeTrue
import java.util.concurrent.CopyOnWriteArrayList

class RemoteDiagnosticsLiveTest {
    @Test fun recordIdleWheelsAndPitch() = runBlocking {
        val ip=System.getenv("HANPPIE_TEST_ROBOT_IP")
        val id=System.getenv("HANPPIE_TEST_APPID")
        assumeTrue(!ip.isNullOrBlank() && !id.isNullOrBlank() && System.getenv("HANPPIE_TEST_ALLOW_REMOTE")=="1")
        val samples=CopyOnWriteArrayList<String>()
        val angles=CopyOnWriteArrayList<List<Double>>()
        val wheels=CopyOnWriteArrayList<List<Int>>()
        val session=AppSession(RobotTarget(ip!!,id!!),onFrame={ f ->
            if(f.set==0x48 && f.id==8 && f.payload.size in listOf(11,38)) {
                val p=f.payload
                samples += if(p.size==38) "rpm="+(2..8 step 2).map { p.u16(it).toShort() }
                    else "angles="+(2..8 step 2).map { p.u16(it).toShort().toDouble()/10 }
                if(p.size==38) wheels += (2..8 step 2).map { p.u16(it).toShort().toInt() }
                else angles += (2..8 step 2).map { p.u16(it).toShort().toDouble()/10 }
            }
            Telemetry.motion(f)?.let { println("battery=${it.batteryPercent}") }
        })
        fun report(label:String) { println("$label ${samples.distinct()}"); samples.clear() }
        try {
            session.connect()
            session.send(9,0x40,0x48,3,"020b000001c5b74cc1090002000a00".hexBytes())
            delay(1000); report("connected")
            session.enterRemote(); delay(1500); report("remote-idle")
            val before=angles.last()
            session.drive(0.0,0.0,0.0,0.0,15.0)
            delay(200); session.halt(); delay(600); report("yaw-right")
            val after=angles.last()
            assertTrue(after[2]-before[2] in 1.0..5.0,"15 deg/s right pulse: $before -> $after")
            assertTrue(kotlin.math.abs(after[1]-before[1])<1.0,"Ground pitch changed during yaw")
            wheels.clear()
            delay(2000); report("post-stop")
            assertTrue(wheels.size>=10,"Missing ESC samples")
            for(i in 0..3) assertTrue(kotlin.math.abs(wheels.map { it[i] }.average())<8.0,"Sustained idle wheel speed: $wheels")
            if(System.getenv("HANPPIE_TEST_ALLOW_FOLLOW")=="1") {
                for (direction in listOf(1.0, -1.0)) {
                // Bounded outward aiming; always stops even if orientation or assertion fails.
                withTimeout(20000) {
                    while((session.cameraYaw ?: error("Missing yaw")) * direction < 70.0) {
                        session.drive(0.0,0.0,0.0,0.0,15.0 * direction)
                        delay(50)
                    }
                }
                session.halt(); delay(500); report("before-follow")
                val followStart=angles.last()
                repeat(20) { session.drive(0.0,0.0,0.0,0.0,15.0 * direction,cameraRelative=true); delay(50) }
                session.halt(); delay(500); report("after-follow")
                val followEnd=angles.last()
                val bodyDelta=(followEnd[0]-followEnd[2])-(followStart[0]-followStart[2])
                println("follow-direction=$direction body-yaw-delta=$bodyDelta")
                assertTrue(bodyDelta * direction in 2.0..35.0,"Chassis did not follow direction $direction: $bodyDelta")
                assertTrue(kotlin.math.abs(followEnd[1]-followStart[1])<1.0,"Ground pitch changed during follow")
                wheels.clear(); delay(2000); report("follow-stopped")
                assertTrue(wheels.size >= 10, "Missing post-follow ESC samples")
                for(i in 0..3) assertTrue(kotlin.math.abs(wheels.map { it[i] }.average())<8.0,"Follow did not stop")
                }
            }
        } finally { runCatching { session.halt() }; session.close() }
    }
}
