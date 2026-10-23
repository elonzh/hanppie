package cn.elonzh.hanppie.ui.settings

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/** Local response parameters, never device writes. Activity/ownership is not persisted. */
@Serializable
internal data class GamepadInputSettings(val lateralGain: Double = 1.0, val forwardGain: Double = 1.0,
    val yaw: GamepadAxisCurve = GamepadAxisCurve(), val pitch: GamepadAxisCurve = GamepadAxisCurve()) {
    init { require(lateralGain.isFinite() && lateralGain in 0.0..2.0 && forwardGain.isFinite() && forwardGain in 0.0..2.0) }
    fun lateral(raw: Int) = (((raw - 1024) * lateralGain + 1024).toInt().coerceIn(364, 1684) - 1024) / 660.0
    fun forward(raw: Int) = (((raw - 1024) * forwardGain + 1024).toInt().coerceIn(364, 1684) - 1024) / 660.0
}

/** The native dead-zone coefficient d has a threshold d/(1+d), not d percent. */
@Serializable
internal data class GamepadAxisCurve(val gain: Double = 1.0, val exponent: Double = 1.0, val deadZone: Double = 0.0) {
    init {
        require(gain.isFinite() && gain in 0.0..2.0)
        require(exponent.isFinite() && exponent in 0.25..4.0)
        require(deadZone.isFinite() && deadZone in 0.0..1.0)
    }
    fun apply(raw: Int): Double {
        val u = (raw - 1024) / 660.0
        val scaled = sign(u) * ((1 + deadZone) * abs(u) - deadZone).coerceAtLeast(0.0).pow(exponent).coerceAtMost(1.0) * gain
        // Preserve native center remapping/truncation before the existing velocity limits.
        return ((scaled * 660 + 1024).toInt().coerceIn(364, 1684) - 1024) / 660.0
    }
}
