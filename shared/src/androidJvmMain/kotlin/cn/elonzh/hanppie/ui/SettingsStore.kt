package cn.elonzh.hanppie.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class SavedSettings(val model: ModelSettings = ModelSettings(), val autoRead: Boolean = false)

/** Blocking platform storage; called only on IO, never from rendering or robot receive threads. */
internal interface SettingsStore {
    fun load(): SavedSettings?
    fun save(settings: SavedSettings)
}

internal val settingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
