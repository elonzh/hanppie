package cn.elonzh.hanppie.ui.robot.scene

import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.telemetry.GimbalTelemetry
import cn.elonzh.hanppie.robot.telemetry.ChassisAttitude
import cn.elonzh.hanppie.robot.telemetry.WheelTelemetry
import cn.elonzh.hanppie.ui.app.ConsoleState
import kotlin.test.*

class RobotSceneStateTest {
    @Test fun driveChannelsKeepTheirOwnFreshnessAndClearOnDisconnect() {
        val state = connected.copy(chassisAttitude = ChassisAttitude(15f, 2f, 3f), chassisReceivedAtMillis = 1000,
            wheels = WheelTelemetry(listOf(20, -20, -20, 20), listOf(10f, 350f, 350f, 10f)), wheelsReceivedAtMillis = 100)
        val scene = RobotSceneState.from(state, 1200)
        assertEquals(15f, scene.chassisYaw)
        assertEquals(listOf(10f, -350f, -350f, 10f), scene.wheelAngles)
        assertEquals(PoseStatus.LIVE, scene.chassisStatus)
        assertEquals(PoseStatus.STALE, scene.wheelsStatus)
        assertNull(RobotSceneState.from(state.lost("test"), 1200).wheelAngles)
        assertNull(state.lost("test").chassisAttitude)
    }
    private val connected = ConsoleState(connected = true, robotProduct = RobotProduct(RobotModel.ROBOMASTER_S1),
        gimbal = GimbalTelemetry(89.0, 45.0, -32.0, 12.0, 0), gimbalReceivedAtMillis = 1_000L)

    @Test fun poseUsesRelativeAnglesAndExpiresWithoutAnotherPacket() {
        val live = RobotSceneState.from(connected, 1_100)
        assertEquals(PoseStatus.LIVE, live.poseStatus)
        assertEquals(-32f, live.yaw)
        assertEquals(12f, live.pitch)
        assertEquals(PoseStatus.STALE, RobotSceneState.from(connected, 2_001).poseStatus)
        assertEquals(PoseStatus.STALE, RobotSceneState.from(connected, 999).poseStatus)
        assertEquals(live.yaw, RobotSceneState.from(connected, 2_001).yaw)
    }

    @Test fun signedAngleWrapDoesNotMakeTheModelSpin() {
        assertEquals(181f, nearestYaw(179f, -179f))
        assertEquals(-181f, nearestYaw(-179f, 179f))
        assertEquals(370f, nearestYaw(350f, 10f))
    }

    @Test fun disconnectClearsPoseAndTimestamp() {
        val lost = connected.lost("test")
        assertNull(lost.gimbalReceivedAtMillis)
        assertNull(lost.gimbal)
        assertEquals(PoseStatus.PREVIEW, RobotSceneState.from(lost, 1_100).poseStatus)
        assertEquals(0f, RobotSceneState.from(lost, 1_100).yaw)
    }

    @Test fun missingOrInvalidTelemetryDoesNotInventAPose() {
        assertEquals(PoseStatus.WAITING, RobotSceneState.from(connected.copy(gimbalReceivedAtMillis = null), 1_100).poseStatus)
        for (yaw in listOf(Double.NaN, Double.POSITIVE_INFINITY, 361.0)) {
            assertEquals(PoseStatus.WAITING, RobotSceneState.from(connected.copy(
                gimbal = connected.gimbal!!.copy(yawDegrees = yaw)), 1_100).poseStatus)
        }
    }

    @Test fun capabilitySelectsAppearanceEvenWhenProductIsUnknown() {
        val product = RobotProduct(capabilities = RobotCapabilities(listOf(WorkingRobotDevice(RobotComponent.GIMBAL.deviceId, emptyList()))))
        assertEquals(RobotAppearance.TURRET, RobotSceneState.from(connected.copy(robotProduct = product), 1_100).appearance)
        assertEquals(RobotAppearance.CHASSIS, RobotSceneState.from(connected.copy(robotProduct = RobotProduct()), 1_100).appearance)
        assertEquals(PoseStatus.STATIC, RobotSceneState.from(connected.copy(robotProduct = RobotProduct(RobotModel.ROBOMASTER_EP)), 1_100).poseStatus)
    }

}
