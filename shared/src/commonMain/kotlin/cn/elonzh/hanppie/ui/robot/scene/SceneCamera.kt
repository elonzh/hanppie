package cn.elonzh.hanppie.ui.robot.scene

import io.github.erkko68.filament.compose.scene.Position
import kotlin.math.*

internal data class SceneCameraPose(val eye: Position, val target: Position, val fov: Double)

/** Presentation-only camera path; it never commands the physical gimbal. */
internal fun sceneCamera(
    azimuth: Float, elevation: Float, distance: Float, focus: Float,
    progress: Float, yaw: Float, pitch: Float, chassisYaw: Float, appearance: RobotAppearance,
): SceneCameraPose {
    fun radians(value: Float) = value * PI.toFloat() / 180f
    fun mix(a: Position, b: Position, t: Float) = Position(
        a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
    fun rotateY(point: Position, angle: Float): Position {
        val a = radians(-angle)
        return Position(point.x * cos(a) + point.z * sin(a), point.y, -point.x * sin(a) + point.z * cos(a))
    }
    val a = radians(azimuth)
    val e = radians(elevation)
    val target = Position(-focus * distance * cos(a), .48f, focus * distance * sin(a))
    val eye = Position(target.x + distance * cos(e) * sin(a), target.y + distance * sin(e), target.z + distance * cos(e) * cos(a))
    val t = progress.coerceIn(0f, 1f)
    if (t == 0f) return SceneCameraPose(eye, target, 35.0)
    val turret = appearance == RobotAppearance.TURRET
    val p = radians(if (turret) pitch else 0f)
    // Original camera and pitch pivot, in the model's display coordinates.
    val lensLocal = if (turret) Position(.0118f, .604f + .0863f * cos(p) + .214f * sin(p), -.0023f + -.0863f * sin(p) + .214f * cos(p))
        else Position(0f, .606f, .051f)
    val lens = rotateY(rotateY(lensLocal, if (turret) yaw else 0f), chassisYaw)
    val forward = rotateY(Position(0f, sin(p), cos(p)), chassisYaw + if (turret) yaw else 0f)
    val approach = Position(lens.x + forward.x * .35f, lens.y + 1f, lens.z + forward.z * .35f)
    val end = Position(lens.x + forward.x * .08f, lens.y + forward.y * .08f, lens.z + forward.z * .08f)
    // First orbit above and behind the robot, then advance along its viewing direction.
    val orbitAngle = radians(azimuth + (nearestYaw(azimuth, 180f - chassisYaw - if (turret) yaw else 0f) - azimuth) * (t / .58f).coerceAtMost(1f))
    val orbitEye = Position(sin(orbitAngle) * distance, eye.y + .35f * sin(t * PI.toFloat()), cos(orbitAngle) * distance)
    val cameraEye = if (t < .58f) mix(eye, orbitEye, (t / .20f).coerceAtMost(1f)) else {
        val u = (t - .58f) / .42f
        val q0 = mix(orbitEye, approach, u)
        val q1 = mix(approach, end, u)
        mix(q0, q1, u)
    }
    val viewTarget = Position(lens.x + forward.x * 2f, lens.y + forward.y * 2f, lens.z + forward.z * 2f)
    return SceneCameraPose(cameraEye, mix(target, viewTarget, ((t - .45f) / .55f).coerceIn(0f, 1f)), 35.0 + 25.0 * t)
}
