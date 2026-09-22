package cn.elonzh.hanppie.ui.robot.scene

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.device.DevicePage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real session -> ConsoleState -> GPU. Motion is separately opt-in and bounded to short pulses. */
@OptIn(ExperimentalTestApi::class)
class RobotSceneLiveTest {
    @Test fun liveTelemetryReachesHomepage() = runDesktopComposeUiTest(width = 1040, height = 700) {
        val ip = System.getenv("HANPPIE_TEST_ROBOT_IP")
        val appId = System.getenv("HANPPIE_TEST_APPID")
        assumeTrue(System.getenv("HANPPIE_GPU_TESTS") == "1" && !ip.isNullOrBlank() && !appId.isNullOrBlank())
        Localization.initialize("zh", null)
        mainClock.autoAdvance = false
        val model = testConsoleModel()
        val output = File("build/reports/ui/live-scene").apply { mkdirs() }
        fun tick() { mainClock.advanceTimeByFrame(); Thread.sleep(15) }
        fun await(predicate: () -> Boolean) = waitUntil(timeoutMillis = 30_000) { tick(); predicate() }
        fun settle() { repeat(50) { tick() }; waitForIdle() }
        fun record(name: String): ImageBitmap {
            settle()
            val state = model.state.value
            println("$name battery=${state.battery} signal=${state.signalQuality} pose=${state.gimbal} chassis=${state.chassisAttitude} wheels=${state.wheels} received=${state.gimbalReceivedAtMillis} raw=${state.values}")
            File(output, "$name-telemetry.txt").writeText(state.frames.filter { "48:8 " in it }.joinToString("\n"))
            return onNodeWithTag("robot-scene-viewport").captureToImage().also { bitmap ->
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.readPixels(pixels)
                val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
                image.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
                ImageIO.write(image, "png", File(output, "$name.png"))
            }
        }
        fun pulse(x: Double = 0.0, z: Double = 0.0, pitch: Double = 0.0, yaw: Double = 0.0) {
            val deadline = System.nanoTime() + 400_000_000L
            try {
                while (System.nanoTime() < deadline) {
                    model.drive(x, 0.0, z, pitch, yaw)
                    Thread.sleep(20)
                }
            } finally { model.drive(0.0, 0.0, 0.0, 0.0, 0.0) }
        }
        try {
            setContent {
                val state by model.state.collectAsState()
                WorkbenchTheme { DevicePage(model, state, false, Modifier.fillMaxSize(), {}, {}) }
            }
            model.connect(ip!!, appId!!)
            await { model.state.value.connected && model.state.value.gimbal != null }
            await { model.state.value.chassisAttitude != null && model.state.value.wheels != null }
            await { onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "示意模型 · 云台姿态更新中")).fetchSemanticsNodes().isNotEmpty() }
            val timestamps = mutableSetOf<Long>()
            repeat(150) { tick(); model.state.value.gimbalReceivedAtMillis?.let(timestamps::add) }
            assertTrue(timestamps.size >= 10, "A single cached pose is not a live stream")
            assertTrue(!model.remoteEnabled.value, "Home subscription must work outside remote mode")
            println("Normal-mode unique gimbal frames=${timestamps.size}")
            record("normal-mode")
            if (System.getenv("HANPPIE_SCENE_ALLOW_MOTION") == "1") {
                model.enableRemote()
                await { model.remoteEnabled.value && !model.state.value.busy }
                settle()
                val start = model.state.value.gimbal!!
                val before = record("before")
                pulse(yaw = 20.0)
                val yawImage = record("yaw-positive")
                val yaw = model.state.value.gimbal!!
                assertTrue(yaw.yawDegrees - start.yawDegrees > 2.0, "Real yaw sensor must respond with the documented sign")
                assertTrue(changedPixels(before, yawImage) > 200, "Real sensor changes must reach GPU geometry")
                pulse(yaw = -20.0)
                record("yaw-return")
                pulse(pitch = 15.0)
                record("pitch-positive")
                assertTrue(abs(model.state.value.gimbal!!.pitchDegrees - start.pitchDegrees) > 2.0)
                pulse(pitch = -15.0)
                record("pitch-return")
                val wheelsBefore = model.state.value.wheels!!.anglesDegrees
                val wheelImageBefore = record("before-wheels")
                pulse(x = .08)
                val wheelImage = record("chassis-forward")
                assertTrue(model.state.value.wheels!!.anglesDegrees.zip(wheelsBefore).all { (a, b) -> abs(nearestYaw(b, a) - b) > 10f })
                assertTrue(changedPixels(wheelImageBefore, wheelImage) > 200, "Native encoder changes must reach wheel geometry")
                pulse(x = -.08)
                record("chassis-return")
                val chassisBefore = model.state.value.chassisAttitude!!.yawDegrees
                pulse(z = 10.0)
                record("chassis-right")
                assertTrue(model.state.value.chassisAttitude!!.yawDegrees - chassisBefore > 2f)
                pulse(z = -10.0)
                record("chassis-left")
                assertEquals(List(5) { 0.0 }, model.remoteInput.value)
                model.leaveRemote()
                await { !model.remoteEnabled.value }
                record("normal-mode-restored")
            }
        } finally {
            model.haltRemote()
            model.close()
        }
    }

    private fun changedPixels(first: ImageBitmap, second: ImageBitmap): Int {
        val a = IntArray(first.width * first.height)
        val b = IntArray(second.width * second.height)
        first.readPixels(a); second.readPixels(b)
        return a.indices.count { index -> (0..2).any { channel ->
            abs(((a[index] shr (channel * 8)) and 255) - ((b[index] shr (channel * 8)) and 255)) > 20
        } }
    }
}
