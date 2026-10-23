package cn.elonzh.hanppie.robot.lab.mobile

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** Unity's quaternion.eulerAngles uses extrinsic Z-X-Y, unlike CoreMotion's roll/pitch/yaw. */
fun mobileAttitude(x: Double, y: Double, z: Double, w: Double): Triple<Float, Float, Float>? {
    val norm = sqrt(x*x + y*y + z*z + w*w)
    if (!norm.isFinite() || norm < 1e-12) return null
    val a = x/norm; val b = y/norm; val c = z/norm; val d = w/norm
    val sinPitch = (2 * (d*a - b*c)).coerceIn(-1.0, 1.0)
    val pitch = asin(sinPitch)
    val yaw: Double
    val roll: Double
    if (abs(sinPitch) < 0.999999) {
        yaw = atan2(2*(a*c + d*b), 1 - 2*(a*a + b*b))
        roll = atan2(2*(a*b + d*c), 1 - 2*(a*a + c*c))
    } else {
        yaw = atan2(2*(d*b - a*c), 1 - 2*(b*b + c*c))
        roll = 0.0
    }
    fun degrees(angle: Double) = ((angle * 180 / kotlin.math.PI + 360) % 360).toFloat()
    return Triple(degrees(pitch), degrees(yaw), degrees(roll))
}
