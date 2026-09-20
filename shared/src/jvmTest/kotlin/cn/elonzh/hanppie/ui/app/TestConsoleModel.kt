package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.agent.runtime.SessionHistory
import cn.elonzh.hanppie.agent.runtime.TestSessionHistory
import cn.elonzh.hanppie.robot.session.RobotNetwork
import cn.elonzh.hanppie.robot.session.RobotRuntime
import cn.elonzh.hanppie.robot.session.JvmRobotRuntime
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.ui.robot.audio.LabAudioImporter
import cn.elonzh.hanppie.ui.robot.audio.NoLabAudioImporter
import cn.elonzh.hanppie.ui.robot.remote.NoSpeakerInput
import cn.elonzh.hanppie.ui.robot.remote.SpeakerInput
import cn.elonzh.hanppie.ui.scripts.ScriptRepository
import cn.elonzh.hanppie.ui.scripts.StoredScript
import cn.elonzh.hanppie.ui.settings.AppearanceSettings
import cn.elonzh.hanppie.ui.settings.ConnectionPreferences
import cn.elonzh.hanppie.ui.settings.SavedSettings
import cn.elonzh.hanppie.ui.settings.SettingsStore
import cn.elonzh.hanppie.ui.settings.UiPreferences
import cn.elonzh.hanppie.ui.speech.NoSpeechInput
import cn.elonzh.hanppie.ui.speech.SpeechInput
import io.ktor.client.HttpClient

internal class MemoryScriptRepository(
    initial: List<StoredScript> = emptyList(),
    presets: List<StoredScript>? = null,
) : ScriptRepository {
    private var saved = initial.toList()
    private var presetList: List<StoredScript>? = presets?.toList()
    private val audio = mutableMapOf<Pair<String, Int>, LabAudioClip>()
    override suspend fun all(): List<StoredScript> = saved.toList()
    override suspend fun presets(): List<StoredScript> {
        if (presetList == null) {
            presetList = try { cn.elonzh.hanppie.ui.scripts.loadAllBundledPresets() } catch (_: Exception) { emptyList() }
        }
        return presetList.orEmpty()
    }

    override suspend fun insert(script: StoredScript) {
        saved = listOf(script) + saved
    }

    override suspend fun update(script: StoredScript) {
        saved = saved.map { if (it.id == script.id) script else it }
    }

    override suspend fun delete(script: StoredScript) {
        saved = saved.filterNot { it.id == script.id }
        audio.keys.filter { it.first == script.id }.forEach(audio::remove)
    }

    override suspend fun audio(scriptId: String): List<LabAudioClip> =
        audio.entries.filter { it.key.first == scriptId }.map { it.value }.sortedBy { it.id }

    override suspend fun insertAudio(scriptId: String, audio: LabAudioClip) {
        this.audio[scriptId to audio.id] = audio
    }

    override suspend fun updateAudio(scriptId: String, audio: LabAudioClip) {
        this.audio[scriptId to audio.id] = audio
    }

    override suspend fun deleteAudio(scriptId: String, nativeId: Int): Boolean =
        audio.remove(scriptId to nativeId) != null

    override suspend fun replaceAllAudio(scriptId: String, clips: List<LabAudioClip>) {
        audio.keys.filter { it.first == scriptId }.forEach(audio::remove)
        clips.forEach { clip -> audio[scriptId to clip.id] = clip }
    }
}

internal class MemorySettingsStore : SettingsStore {
    private var settings = SavedSettings()
    private var ui = UiPreferences()
    private var connection = ConnectionPreferences.fresh()

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

    override suspend fun loadConnection(): ConnectionPreferences = connection

    override suspend fun saveConnection(preferences: ConnectionPreferences) {
        connection = preferences
    }
}

internal fun testConsoleModel(
    voiceInput: SpeechInput = NoSpeechInput(),
    speakerInput: SpeakerInput = NoSpeakerInput(),
    audioImporter: LabAudioImporter = NoLabAudioImporter(),
    robotNetwork: () -> RobotNetwork = { RobotNetwork.Default },
    robotRuntime: RobotRuntime = JvmRobotRuntime(robotNetwork),
    settingsStore: SettingsStore = MemorySettingsStore(),
    scriptRepository: ScriptRepository = MemoryScriptRepository(),
    sessionHistory: SessionHistory = TestSessionHistory(),
    prepareNetwork: () -> Unit = {},
    createAgentHttpClient: () -> HttpClient = {
        error("Agent HTTP client must not be created by isolated UI tests")
    },
    scriptStartConfirmationTimeoutMillis: Long = 10_000,
): ConsoleModel = ConsoleModel(
    voiceInput = voiceInput,
    speakerInput = speakerInput,
    audioImporter = audioImporter,
    robotRuntime = robotRuntime,
    settingsStore = settingsStore,
    scriptRepository = scriptRepository,
    sessionHistory = sessionHistory,
    createAgentHttpClient = createAgentHttpClient,
    prepareNetwork = prepareNetwork,
    autoConnectOnStart = false,
    scriptStartConfirmationTimeoutMillis = scriptStartConfirmationTimeoutMillis,
)
