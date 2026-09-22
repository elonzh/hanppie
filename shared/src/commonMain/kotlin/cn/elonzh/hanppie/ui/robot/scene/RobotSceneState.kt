package cn.elonzh.hanppie.ui.robot.scene

import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.robot.product.RobotModel
import cn.elonzh.hanppie.ui.app.ConsoleState

/** Appearance selection is separate from protocol capabilities and never gates robot access. */
internal enum class RobotAppearance(val asset: String) {
    TURRET("robot-s1"), ARM("robot-ep"), CHASSIS("robot-base"),
}

internal enum class PoseStatus { PREVIEW, WAITING, LIVE, STALE, STATIC }

internal data class RobotSceneState(
    val appearance: RobotAppearance,
    val poseStatus: PoseStatus,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val chassisYaw: Float? = null,
    val wheelAngles: List<Float>? = null,
    val chassisStatus: PoseStatus = PoseStatus.WAITING,
    val wheelsStatus: PoseStatus = PoseStatus.WAITING,
) {
    companion object {
        fun from(state: ConsoleState, nowMillis: Long): RobotSceneState {
            val components = state.robotProduct.capabilities.components
            val appearance = when {
                !state.connected -> RobotAppearance.TURRET
                RobotComponent.GIMBAL in components -> RobotAppearance.TURRET
                RobotComponent.ARM in components -> RobotAppearance.ARM
                // Product metadata selects an illustration, never an assumed capability.
                state.robotProduct.model == RobotModel.ROBOMASTER_S1 -> RobotAppearance.TURRET
                state.robotProduct.model == RobotModel.ROBOMASTER_EP -> RobotAppearance.ARM
                else -> RobotAppearance.CHASSIS
            }
            if (!state.connected) return RobotSceneState(appearance, PoseStatus.PREVIEW)
            fun freshness(received: Long?) = when {
                received == null -> PoseStatus.WAITING
                nowMillis - received in 0..1_000 -> PoseStatus.LIVE
                else -> PoseStatus.STALE
            }
            fun scene(status: PoseStatus, yaw: Float = 0f, pitch: Float = 0f) = RobotSceneState(
                appearance, status, yaw, pitch,
                state.chassisAttitude?.yawDegrees,
                state.wheels?.anglesDegrees?.mapIndexed { index, angle -> if (index == 1 || index == 2) -angle else angle },
                freshness(state.chassisReceivedAtMillis), freshness(state.wheelsReceivedAtMillis),
            )
            if (appearance != RobotAppearance.TURRET) return scene(PoseStatus.STATIC)
            val pose = state.gimbal
            val received = state.gimbalReceivedAtMillis
            if (pose == null || received == null || !pose.yawDegrees.isFinite() || !pose.pitchDegrees.isFinite() ||
                pose.yawDegrees !in -360.0..360.0 || pose.pitchDegrees !in -180.0..180.0) {
                return scene(PoseStatus.WAITING)
            }
            val status = if (nowMillis - received in 0..1_000) PoseStatus.LIVE else PoseStatus.STALE
            return scene(status, pose.yawDegrees.toFloat(), pose.pitchDegrees.toFloat())
        }
    }
}

/** Cross the signed-angle seam without showing a spurious full revolution. */
internal fun nearestYaw(previous: Float, next: Float): Float =
    previous + (((next - previous) % 360f + 540f) % 360f - 180f)
