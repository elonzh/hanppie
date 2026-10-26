package cn.elonzh.hanppie.ui.robot.remote.input

import cn.elonzh.hanppie.ui.settings.AimInputSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign

internal enum class AimInputProblem { SENSOR_UNAVAILABLE, SENSOR_STALE, ORIENTATION_CHANGED }
internal data class AimInputState(val active: Boolean = false, val ready: Boolean = false,
    val problem: AimInputProblem? = null)
internal data class AimVelocity(val pitch: Double = 0.0, val yaw: Double = 0.0)

/** Page-scoped gyroscope input. The existing drive ticker, limits and robot lease remain the sole sender.
 * All methods and adapter reads run on the UI thread.
 */
internal class GimbalAimInput(private val gyro: AimGyroscope,
    private val nowMillis: () -> Long, private val controlRevision: () -> Long) : AutoCloseable {
    val state = MutableStateFlow(AimInputState())
    val gyroAvailable get() = gyro.available
    private var startedAt = 0L
    private var lastStamp = -1L
    private var rotation: Int? = null
    private var closed = false
    private var ownerRevision = 0L

    fun startGyroscope() {
        if (closed) return
        stop()
        ownerRevision = controlRevision()
        if (!gyro.available) return fail(AimInputProblem.SENSOR_UNAVAILABLE)
        try {
            gyro.start()
            startedAt = nowMillis()
            state.value = AimInputState(active = true)
        } catch (_: Exception) { fail(AimInputProblem.SENSOR_UNAVAILABLE) }
    }

    fun stop() {
        runCatching { gyro.stop() }
        lastStamp = -1; rotation = null
        state.value = AimInputState()
    }

    private fun fail(problem: AimInputProblem) { stop(); state.value = AimInputState(problem = problem) }
    override fun close() { stop(); closed = true }

    fun frame(settings: AimInputSettings, maximum: Int): AimVelocity {
        require(maximum in 15..120)
        if (!state.value.active || closed) return AimVelocity()
        if (controlRevision() != ownerRevision) { stop(); return AimVelocity() }
        val now = nowMillis()
        val reading = try { gyro.read() } catch (_: Exception) {
            fail(AimInputProblem.SENSOR_UNAVAILABLE); return AimVelocity()
        }
        if (reading == null || reading.ageMillis !in 0..250 || reading.stamp < lastStamp ||
            reading.stamp < 0 || reading.rotation !in 0..3 || !listOf(reading.x, reading.y, reading.z).all { it.isFinite() }) {
            if (state.value.ready) fail(AimInputProblem.SENSOR_STALE)
            else if (now - startedAt >= 3_000) fail(AimInputProblem.SENSOR_UNAVAILABLE)
            return AimVelocity()
        }
        if (rotation != null && rotation != reading.rotation) {
            fail(AimInputProblem.ORIENTATION_CHANGED); return AimVelocity()
        }
        rotation = reading.rotation; lastStamp = reading.stamp
        state.value = state.value.copy(ready = true)
        val x = when (reading.rotation) { 1 -> reading.y; 2 -> -reading.x; 3 -> -reading.y; else -> reading.x }
        val y = when (reading.rotation) { 1 -> -reading.x; 2 -> -reading.y; 3 -> reading.x; else -> reading.y }
        // Preserve the official 0.01 rad/s truncation and horizontal 1.4 ratio.
        fun quantize(number: Double) = sign(number) * (abs(number) * 100).toLong() / 100.0
        val gain = 180 / PI * settings.gyroSensitivity / 50.0
        val value = AimVelocity(quantize(x) * gain * if (settings.gyroInvertY) -1 else 1, -quantize(y) * gain * 1.4)
        fun limited(number: Double) = if (number == 0.0) 0.0 else number.coerceIn(-maximum.toDouble(), maximum.toDouble())
        return AimVelocity(limited(value.pitch), limited(value.yaw))
    }
}
