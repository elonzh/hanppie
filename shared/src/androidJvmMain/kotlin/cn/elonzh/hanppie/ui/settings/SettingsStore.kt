package cn.elonzh.hanppie.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
        require(joystickDeadZone.isFinite() && joystickDeadZone in 0.0..0.4)
    }
}

@Serializable
internal data class SavedSettings(
    val model: ModelSettings = ModelSettings(),
    val autoRead: Boolean = false,
    val control: ControlSettings = ControlSettings(),
)

internal data class UiPreferences(
    val language: String = "system",
    val appearance: AppearanceSettings = AppearanceSettings(),
    val speechService: String? = null,
)

internal interface SettingsStore {
    suspend fun load(): SavedSettings
    suspend fun save(settings: SavedSettings)
    suspend fun loadUi(): UiPreferences
    suspend fun saveLanguage(language: String)
    suspend fun saveAppearance(appearance: AppearanceSettings)
    suspend fun saveSpeechService(service: String)
}

internal val settingsJson = Json { encodeDefaults = true }

internal class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {
    override suspend fun load(): SavedSettings {
        val preferences = dataStore.data.first()
        return preferences[Keys.settings]
            ?.let { settingsJson.decodeFromString<SavedSettings>(it) }
            ?: SavedSettings()
    }

    override suspend fun save(settings: SavedSettings) {
        dataStore.edit { preferences ->
            preferences[Keys.settings] = settingsJson.encodeToString(settings)
        }
    }

    override suspend fun loadUi(): UiPreferences {
        val preferences = dataStore.data.first()
        return UiPreferences(
            language = preferences[Keys.language] ?: "system",
            appearance = AppearanceSettings.decode(preferences[Keys.appearance]),
            speechService = preferences[Keys.speechService],
        )
    }

    override suspend fun saveLanguage(language: String) {
        require(language in listOf("system", "zh", "en"))
        dataStore.edit { it[Keys.language] = language }
    }

    override suspend fun saveAppearance(appearance: AppearanceSettings) {
        dataStore.edit { it[Keys.appearance] = appearance.encode() }
    }

    override suspend fun saveSpeechService(service: String) {
        require(service.isNotBlank())
        dataStore.edit { it[Keys.speechService] = service }
    }

    private object Keys {
        val settings = stringPreferencesKey("settings")
        val language = stringPreferencesKey("language")
        val appearance = stringPreferencesKey("appearance")
        val speechService = stringPreferencesKey("speech_service")
    }
}
