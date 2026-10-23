package cn.elonzh.hanppie.ui.settings

import kotlinx.serialization.Serializable

internal enum class InputAxis { FORWARD, LATERAL, YAW, PITCH }
@Serializable internal enum class SensitivityPreset { STANDARD, CUSTOM }

@Serializable
internal data class AxisSensitivity(val forward: Int = 35, val lateral: Int = 35, val yaw: Int = 50, val pitch: Int = 40) {
    init { require(listOf(forward, lateral, yaw, pitch).all { it in 0..100 }) }
    operator fun get(axis: InputAxis): Int = when (axis) {
        InputAxis.FORWARD -> forward; InputAxis.LATERAL -> lateral; InputAxis.YAW -> yaw; InputAxis.PITCH -> pitch
    }
    fun with(axis: InputAxis, value: Int): AxisSensitivity = when (axis) {
        InputAxis.FORWARD -> copy(forward = value); InputAxis.LATERAL -> copy(lateral = value)
        InputAxis.YAW -> copy(yaw = value); InputAxis.PITCH -> copy(pitch = value)
    }
    companion object { val standard = AxisSensitivity(50, 50, 50, 50) }
}

@Serializable
internal data class InputSensitivity(val preset: SensitivityPreset = SensitivityPreset.STANDARD,
    val custom: AxisSensitivity = AxisSensitivity()) {
    val axes: AxisSensitivity get() = if (preset == SensitivityPreset.STANDARD) AxisSensitivity.standard else custom
}

@Serializable
internal data class InputSensitivities(val screen: InputSensitivity = InputSensitivity(),
    val keyboard: InputSensitivity = InputSensitivity()) {
    /** Gain precedes source merging and host limits; the standard preset preserves published input. */
    fun combine(axis: InputAxis, screenValue: Double, keyboardValue: Double): Double {
        require(screenValue.isFinite() && keyboardValue.isFinite())
        return (screenValue * screen.axes[axis] / 50.0 + keyboardValue * keyboard.axes[axis] / 50.0).coerceIn(-1.0, 1.0)
    }
}
