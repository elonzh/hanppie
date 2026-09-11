package cn.elonzh.hanppie.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class RobotLedColor(
    val red: Int,
    val green: Int,
    val blue: Int,
) {
    init {
        require(red in 0..255 && green in 0..255 && blue in 0..255)
    }

    val hex: String get() = "#%02X%02X%02X".format(red, green, blue)

    companion object {
        fun parse(value: String): RobotLedColor? {
            if (!Regex("#[0-9a-fA-F]{6}").matches(value)) return null
            return RobotLedColor(
                value.substring(1, 3).toInt(16),
                value.substring(3, 5).toInt(16),
                value.substring(5, 7).toInt(16),
            )
        }
    }
}

@Serializable
internal data class RemoteLedSettings(
    val standby: RobotLedColor = RobotLedColor(40, 120, 255),
    val active: RobotLedColor = RobotLedColor(40, 220, 100),
    val recording: RobotLedColor = RobotLedColor(255, 48, 48),
    val talking: RobotLedColor = RobotLedColor(168, 80, 255),
)

@Serializable
internal data class ControlSettings(
    val gimbalSpeed: Int = 90,
    val translationSpeeds: List<Double> = listOf(.25, .45, .65, .85, 1.0),
    val rotationSpeeds: List<Double> = listOf(30.0, 60.0, 90.0, 120.0, 150.0),
    val creepMultiplier: Double = .25,
    val joystickDeadZone: Double = .12,
    val shortcuts: ControlShortcuts = ControlShortcuts(),
    val remoteLeds: RemoteLedSettings = RemoteLedSettings(),
) {
    init {
        require(gimbalSpeed in 15..120)
        require(translationSpeeds.size == 5 && translationSpeeds.all { it.isFinite() && it in .05..1.0 })
        require(rotationSpeeds.size == 5 && rotationSpeeds.all { it.isFinite() && it in 10.0..150.0 })
        require(translationSpeeds.zipWithNext().all { (a, b) -> a <= b })
        require(rotationSpeeds.zipWithNext().all { (a, b) -> a <= b })
        require(creepMultiplier.isFinite() && creepMultiplier in .1..1.0)
        require(joystickDeadZone.isFinite() && joystickDeadZone in 0.0..0.4)
    }
}

@Serializable
internal data class SavedSettings(
    val model: ModelSettings = ModelSettings(),
    val autoRead: Boolean = false,
    val control: ControlSettings = ControlSettings(),
)

/** Blocking platform storage; called only on IO, never from rendering or robot receive threads. */
internal interface SettingsStore {
    fun load(): SavedSettings?
    fun save(settings: SavedSettings)
}

internal val settingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
