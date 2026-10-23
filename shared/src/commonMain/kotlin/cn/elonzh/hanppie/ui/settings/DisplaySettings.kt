package cn.elonzh.hanppie.ui.settings

import kotlinx.serialization.Serializable

@Serializable
internal enum class ReticleStyle { CROSS, ROUND }

@Serializable
internal enum class SceneQuality { LOW, MEDIUM, HIGH }

/** Host rendering only; device sight calibration and headlight values never enter this file. */
@Serializable
internal data class DisplaySettings(
    val reticle: ReticleStyle = ReticleStyle.CROSS,
    val fillVideo: Boolean = false,
    val showGimbalAngle: Boolean = true,
    val showArmPosition: Boolean = false,
    val sceneQuality: SceneQuality = SceneQuality.HIGH,
)
