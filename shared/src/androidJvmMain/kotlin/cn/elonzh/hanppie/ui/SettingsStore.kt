package cn.elonzh.hanppie.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ControlSettings(
    val gimbalSpeed: Int = 90,
    val translationSpeeds: List<Double> = listOf(.25, .45, .65, .85, 1.0),
    val rotationSpeeds: List<Double> = listOf(30.0, 60.0, 90.0, 120.0, 150.0),
    val creepMultiplier: Double = .25,
    val joystickDeadZone: Double = .12,
    val shortcuts: ControlShortcuts = ControlShortcuts(),
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
