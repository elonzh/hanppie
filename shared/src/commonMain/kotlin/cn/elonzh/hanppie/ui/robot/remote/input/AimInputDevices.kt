package cn.elonzh.hanppie.ui.robot.remote.input

import androidx.compose.runtime.staticCompositionLocalOf

/** Native adapters invoke callbacks on the UI thread and release all listeners when closed. */
internal interface RelativePointer {
    fun capture(onReady: () -> Unit, onMotion: (Double, Double) -> Unit,
        onButton: (primary: Boolean, pressed: Boolean) -> Unit, onLost: () -> Unit): AutoCloseable
}

/** Native event access is denied; callers can explain the required system setting. */
internal class PointerAccessDenied : IllegalStateException("Pointer event access is not authorized")

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

internal val LocalRelativePointer = staticCompositionLocalOf<RelativePointer?> { null }
internal val LocalAimGyroscope = staticCompositionLocalOf<() -> AimGyroscope> { { NoAimGyroscope } }
