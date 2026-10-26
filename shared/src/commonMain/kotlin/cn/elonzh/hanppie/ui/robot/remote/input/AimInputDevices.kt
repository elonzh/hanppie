package cn.elonzh.hanppie.ui.robot.remote.input

import androidx.compose.runtime.staticCompositionLocalOf

/** Calibrated device angular rate in rad/s, with current screen rotation in quarter turns. */
internal data class GyroscopeReading(val x: Double, val y: Double, val z: Double,
    val stamp: Long, val ageMillis: Long, val rotation: Int)

/** Driving requires calibrated rates and screen orientation; Lab's device-axis stream is separate. */
internal interface AimGyroscope {
    val available: Boolean
    fun start()
    fun read(): GyroscopeReading?
    fun stop()
}

internal object NoAimGyroscope : AimGyroscope {
    override val available = false
    override fun start() = Unit
    override fun read(): GyroscopeReading? = null
    override fun stop() = Unit
}

internal val LocalAimGyroscope = staticCompositionLocalOf<() -> AimGyroscope> { { NoAimGyroscope } }
