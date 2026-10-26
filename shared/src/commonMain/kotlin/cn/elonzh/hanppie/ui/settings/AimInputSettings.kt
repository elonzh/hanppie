package cn.elonzh.hanppie.ui.settings

import kotlinx.serialization.Serializable

/** Response preferences only. Starting the gyroscope is an explicit page action. */
@Serializable
internal data class AimInputSettings(val gyroSensitivity: Int = 50, val gyroInvertY: Boolean = false) {
    init { require(gyroSensitivity in 0..100) }
}
