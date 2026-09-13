package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.session.RobotNetwork
import cn.elonzh.hanppie.robot.session.JvmRobotRuntime
import cn.elonzh.hanppie.ui.robot.remote.NoSpeakerInput
import cn.elonzh.hanppie.ui.robot.remote.SpeakerInput
import cn.elonzh.hanppie.ui.scripts.ScriptRepository
import cn.elonzh.hanppie.ui.scripts.StoredScript
import cn.elonzh.hanppie.ui.settings.AppearanceSettings
import cn.elonzh.hanppie.ui.settings.SavedSettings
import cn.elonzh.hanppie.ui.settings.SettingsStore
import cn.elonzh.hanppie.ui.settings.UiPreferences
import cn.elonzh.hanppie.ui.speech.NoSpeechInput
import cn.elonzh.hanppie.ui.speech.SpeechEngine
import cn.elonzh.hanppie.ui.speech.SpeechInput
import cn.elonzh.hanppie.ui.speech.SystemSpeech

internal class MemoryScriptRepository(initial: List<StoredScript> = emptyList()) : ScriptRepository {
    private var saved = initial.toList()
    override suspend fun all(): List<StoredScript> = saved.toList()

    override suspend fun insert(script: StoredScript) {
        saved = listOf(script) + saved
    }

    override suspend fun update(script: StoredScript) {
        saved = saved.map { if (it.id == script.id) script else it }
    }

    override suspend fun delete(script: StoredScript) {
        saved = saved.filterNot { it.id == script.id }
    }
}

internal class MemorySettingsStore : SettingsStore {
    private var settings = SavedSettings()
    private var ui = UiPreferences()

    override suspend fun load(): SavedSettings = settings

    override suspend fun save(settings: SavedSettings) {
        this.settings = settings
    }

    override suspend fun loadUi(): UiPreferences = ui

    override suspend fun saveLanguage(language: String) {
        ui = ui.copy(language = language)
    }

    override suspend fun saveAppearance(appearance: AppearanceSettings) {
        ui = ui.copy(appearance = appearance)
    }

    override suspend fun saveSpeechService(service: String) {
        ui = ui.copy(speechService = service)
    }
}

internal fun testConsoleModel(
    speech: SpeechEngine = SystemSpeech(),
    voiceInput: SpeechInput = NoSpeechInput(),
    speakerInput: SpeakerInput = NoSpeakerInput(),
    robotNetwork: () -> RobotNetwork = { RobotNetwork.Default },
    settingsStore: SettingsStore = MemorySettingsStore(),
    scriptRepository: ScriptRepository = MemoryScriptRepository(),
    prepareNetwork: () -> Unit = {},
): ConsoleModel = ConsoleModel(
    speech = speech,
    voiceInput = voiceInput,
    speakerInput = speakerInput,
    robotRuntime = JvmRobotRuntime(robotNetwork),
    settingsStore = settingsStore,
    scriptRepository = scriptRepository,
    createAgentHttpClient = { error("Agent HTTP client must not be created by isolated UI tests") },
    prepareNetwork = prepareNetwork,
)
