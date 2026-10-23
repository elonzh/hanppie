package cn.elonzh.hanppie.ui.settings

import kotlinx.serialization.Serializable

/** Response preferences only. Capturing the pointer or starting sensors is an explicit page action. */
@Serializable
internal data class AimInputSettings(val mouseSensitivity: Int = 50, val mouseInvertY: Boolean = false,
    val gyroSensitivity: Int = 50, val gyroInvertY: Boolean = false) {
    init { require(mouseSensitivity in 0..100 && gyroSensitivity in 0..100) }
}
