package cn.elonzh.hanppie.ui.robot.remote.input

import cn.elonzh.hanppie.ui.settings.AimInputSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign

internal enum class AimInputMode { OFF, MOUSE, GYROSCOPE }
internal enum class AimInputProblem { POINTER_UNAVAILABLE, POINTER_ACCESS_DENIED, SENSOR_UNAVAILABLE, SENSOR_STALE, ORIENTATION_CHANGED }
internal data class AimInputState(val mode: AimInputMode = AimInputMode.OFF, val ready: Boolean = false,
    val primaryHeld: Boolean = false, val repeatPrimary: Boolean = false, val problem: AimInputProblem? = null)
internal data class AimVelocity(val pitch: Double = 0.0, val yaw: Double = 0.0)

/** Page-scoped input only. The existing drive ticker, limits and robot lease remain the sole sender.
 * All methods and adapter callbacks run on the UI thread. Each start retires every old callback.
 */
internal class GimbalAimInput(private val pointer: RelativePointer?, private val gyro: AimGyroscope,
    private val nowMillis: () -> Long, private val controlRevision: () -> Long,
    private val onPrimaryPress: () -> Unit = {},
    private val onSecondaryPress: () -> Unit = {}) : AutoCloseable {
    val state = MutableStateFlow(AimInputState())
    val mouseAvailable get() = pointer != null
    val gyroAvailable get() = gyro.available
    private var capture: AutoCloseable? = null
    private var generation = 0L
    private var mouseX = 0.0
    private var mouseY = 0.0
    private var frameAt = 0L
    private var startedAt = 0L
    private var lastStamp = -1L
    private var rotation: Int? = null
    private var primaryAt = 0L
    private var secondaryHeld = false
    private var closed = false
    private var ownerRevision = 0L

    private fun admits(epoch: Long): Boolean {
        if (closed || generation != epoch) return false
        if (controlRevision() != ownerRevision) { stop(); return false }
        return true
    }

    fun startMouse() {
        if (closed) return
        stop()
        ownerRevision = controlRevision()
        val adapter = pointer ?: return fail(AimInputProblem.POINTER_UNAVAILABLE)
        val epoch = generation
        state.value = AimInputState(AimInputMode.MOUSE)
        frameAt = nowMillis(); startedAt = frameAt
        try {
            val handle = adapter.capture(
                onReady = { if (admits(epoch)) state.value = state.value.copy(ready = true) },
                onMotion = { x, y -> if (admits(epoch) && state.value.ready && x.isFinite() && y.isFinite()) {
                    mouseX = (mouseX + x).coerceIn(-10_000.0, 10_000.0)
                    mouseY = (mouseY + y).coerceIn(-10_000.0, 10_000.0)
                } },
                onButton = { primary, pressed -> if (admits(epoch) && state.value.ready) {
                    if (primary) {
                        val previous = state.value.primaryHeld
                        state.value = state.value.copy(primaryHeld = pressed, repeatPrimary = false)
                        if (pressed && !previous) { primaryAt = nowMillis(); onPrimaryPress() }
                    } else {
                        val previous = secondaryHeld; secondaryHeld = pressed
                        if (pressed && !previous) onSecondaryPress()
                    }
                } },
                onLost = { if (generation == epoch) fail(AimInputProblem.POINTER_UNAVAILABLE) },
            )
            if (generation == epoch) capture = handle else handle.close()
        } catch (_: PointerAccessDenied) {
            if (generation == epoch) fail(AimInputProblem.POINTER_ACCESS_DENIED)
        } catch (_: Exception) { if (generation == epoch) fail(AimInputProblem.POINTER_UNAVAILABLE) }
    }

    fun startGyroscope() {
        if (closed) return
        stop()
        ownerRevision = controlRevision()
        if (!gyro.available) return fail(AimInputProblem.SENSOR_UNAVAILABLE)
        try {
            gyro.start()
            startedAt = nowMillis(); frameAt = startedAt
            state.value = AimInputState(AimInputMode.GYROSCOPE)
        } catch (_: Exception) { fail(AimInputProblem.SENSOR_UNAVAILABLE) }
    }

    fun stop() {
        generation++
        val previous = capture; capture = null
        runCatching { previous?.close() }
        runCatching { gyro.stop() }
        mouseX = 0.0; mouseY = 0.0; lastStamp = -1; rotation = null; secondaryHeld = false
        state.value = AimInputState()
    }

    private fun fail(problem: AimInputProblem) { stop(); state.value = AimInputState(problem = problem) }
    override fun close() { stop(); closed = true }

    fun frame(settings: AimInputSettings, maximum: Int): AimVelocity {
        require(maximum in 15..120)
        if (state.value.mode == AimInputMode.OFF || !admits(generation)) return AimVelocity()
        val now = nowMillis()
        val elapsed = now - frameAt; frameAt = now
        val value = when (state.value.mode) {
            AimInputMode.OFF -> return AimVelocity()
            AimInputMode.MOUSE -> {
                if (!state.value.ready) {
                    if (now - startedAt >= 3_000) fail(AimInputProblem.POINTER_UNAVAILABLE)
                    return AimVelocity()
                }
                state.value = state.value.copy(repeatPrimary = state.value.primaryHeld && now - primaryAt >= 250)
                if (elapsed <= 0) return AimVelocity()
                val x = mouseX; val y = mouseY; mouseX = 0.0; mouseY = 0.0
                // A stalled UI must never replay accumulated motion as a burst on its next frame.
                if (elapsed !in 1..100) return AimVelocity()
                val gain = 0.2 * settings.mouseSensitivity / 50.0 * 1000 / elapsed
                AimVelocity(-y * gain * if (settings.mouseInvertY) -1 else 1, x * gain)
            }
            AimInputMode.GYROSCOPE -> {
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
                AimVelocity(quantize(x) * gain * if (settings.gyroInvertY) -1 else 1, -quantize(y) * gain * 1.4)
            }
        }
        fun limited(number: Double) = if (number == 0.0) 0.0 else number.coerceIn(-maximum.toDouble(), maximum.toDouble())
        return AimVelocity(limited(value.pitch), limited(value.yaw))
    }
}
