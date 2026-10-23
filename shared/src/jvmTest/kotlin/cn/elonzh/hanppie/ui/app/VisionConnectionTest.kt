package cn.elonzh.hanppie.ui.app

import androidx.compose.ui.graphics.ImageBitmap
import cn.elonzh.hanppie.robot.vision.*
import kotlinx.coroutines.*
import kotlin.test.*

class VisionConnectionTest {
    @Test fun rootAdmissionForegroundAndConnectionGenerationDoNotResumeOrRetargetVision(): Unit = runBlocking {
        val sessions = listOf(RobotSessionFixture(), RobotSessionFixture())
        val model = testConsoleModel(robotRuntime = AddressingRuntime(sessions))
        suspend fun await(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }
        try {
            model.connect("127.0.0.1", "12345678"); await { model.state.value.connected && !model.state.value.busy }
            model.videoFrames.publish(model.state.value.connectedAddress, ImageBitmap(2, 2))
            model.enableRemote(); await { model.remoteEnabled.value && !model.state.value.busy }
            model.startVision(VisionMode.ROBOT_AIM)
            assertEquals(listOf(VisionMode.ROBOT_AIM), sessions[0].visionStarts)
            sessions[0].vision.value = VisionState(VisionPhase.ACTIVE, VisionMode.ROBOT_AIM, enableMayBeActive = true)
            await { model.vision.value.ownsGimbal }
            model.state.value = model.state.value.copy(busy = true)
            model.startVision(VisionMode.MARKERS); assertEquals(1, sessions[0].visionStarts.size)
            model.state.value = model.state.value.copy(busy = false)
            model.setForeground(false); await { !model.remoteEnabled.value && !model.vision.value.ownsPipeline }
            model.setForeground(true); delay(50)
            assertEquals(1, sessions[0].visionStarts.size)
            model.pauseConnection()
            model.connect("127.0.0.2", "12345678"); await { model.state.value.connected && !model.state.value.busy }
            sessions[0].vision.value = VisionState(VisionPhase.ACTIVE, VisionMode.ROBOT_AIM, enableMayBeActive = true)
            delay(50)
            assertEquals(VisionPhase.OFF, model.vision.value.phase)
            assertTrue(sessions[1].visionStarts.isEmpty())
            assertFalse(model.remoteEnabled.value)
        } finally { model.close() }
    }
}
