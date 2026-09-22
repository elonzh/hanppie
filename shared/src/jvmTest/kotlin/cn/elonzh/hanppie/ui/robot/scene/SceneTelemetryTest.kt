package cn.elonzh.hanppie.ui.robot.scene

import cn.elonzh.hanppie.robot.product.RobotModel
import cn.elonzh.hanppie.robot.product.RobotProduct
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.hexBytes
import cn.elonzh.hanppie.ui.app.testConsoleModel
import kotlin.test.*
import kotlin.time.Clock

class SceneTelemetryTest {
    @Test fun chassisAndWheelsRefreshIndependentlyOfGimbal() {
        val model = testConsoleModel()
        try {
            val attitude = DussFrame(0, 9, 2, 1, 0, 0x48, 8, "000dd8aad23edb7b063eb512d6bf".hexBytes(), true)
            val wheels = attitude.copy(payload = "000efdff0100fdff0000254dd90fc142b872869b1700879b1700899b1700849b170000000000".hexBytes())
            model.receive(attitude)
            assertNotNull(model.state.value.chassisReceivedAtMillis)
            assertNull(model.state.value.wheelsReceivedAtMillis)
            model.receive(wheels)
            val state = model.state.value
            assertNotNull(state.wheelsReceivedAtMillis)
            assertNull(state.gimbalReceivedAtMillis)
            model.receive(attitude.copy(valid = false))
            model.receive(wheels.copy(payload = wheels.payload.copyOf(20)))
            assertEquals(state.chassisReceivedAtMillis, model.state.value.chassisReceivedAtMillis)
            assertEquals(state.wheelsReceivedAtMillis, model.state.value.wheelsReceivedAtMillis)
        } finally { model.close() }
    }
    @Test fun onlyValidGimbalPacketsRefreshScenePose() {
        val model = testConsoleModel()
        try {
            model.state.value = model.state.value.copy(connected = true, robotProduct = RobotProduct(RobotModel.ROBOMASTER_S1))
            val packet = DussFrame(24, 9, 2, 1, 0, 0x48, 8,
                byteArrayOf(0, 10, 0, 0, 0, 0, 0x84.toByte(), 3, 0x64, 0, 0), true)
            model.receive(packet)
            val first = model.state.value
            assertNotNull(first.gimbalReceivedAtMillis)
            val pose = RobotSceneState.from(first, Clock.System.now().toEpochMilliseconds())
            assertEquals(PoseStatus.LIVE, pose.poseStatus)
            assertEquals(90f, pose.yaw)
            assertEquals(10f, pose.pitch)
            model.receive(packet.copy(valid = false))
            model.receive(packet.copy(id = 0x09))
            assertEquals(first.gimbalReceivedAtMillis, model.state.value.gimbalReceivedAtMillis)
            assertEquals(PoseStatus.STALE, RobotSceneState.from(model.state.value, first.gimbalReceivedAtMillis!! + 1_001).poseStatus)
        } finally { model.close() }
    }
}
