package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.robot.telemetry.GimbalTelemetry
import kotlin.math.abs

/** Bounded feedback control over the existing, verified gimbal velocity command. */
internal class GimbalCentering {
    private var deadline: Long? = null

    fun start(now: Long) { deadline = now + 5_000 }
    fun cancel() { deadline = null }

    fun velocity(now: Long, sample: GimbalTelemetry?, receivedAt: Long?, manualInput: Boolean): Pair<Double, Double>? {
        val until = deadline ?: return null
        if (manualInput || now >= until || sample == null || receivedAt == null ||
            now - receivedAt !in 0..500 || !sample.pitchDegrees.isFinite() || !sample.yawDegrees.isFinite() ||
            abs(sample.pitchDegrees) > 90 || abs(sample.yawDegrees) > 360) {
            cancel()
            return null
        }
        fun rate(angle: Double) = if (abs(angle) <= 1.0) 0.0 else (-angle * 3).coerceIn(-60.0, 60.0)
        val velocity = rate(sample.pitchDegrees) to rate(sample.yawDegrees)
        if (velocity.first == 0.0 && velocity.second == 0.0) cancel()
        return velocity
    }
}
