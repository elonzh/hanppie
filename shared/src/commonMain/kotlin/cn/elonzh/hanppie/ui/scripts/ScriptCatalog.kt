package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.robot.lab.LabAudioClip
import kotlinx.serialization.Serializable

@Serializable
internal data class StoredScript(
    val id: String,
    val name: String,
    val source: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val summary: String? = null,
    @kotlinx.serialization.Transient
    val audioClips: List<LabAudioClip> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        require(validScriptName(name))
        require(source.length <= MAX_SCRIPT_LENGTH)
    }
}

internal data class BundledPreset(
    val id: String,
    val folderName: String = id.replace('-', '_'),
    val files: List<String> = listOf("manifest.json", "script.py"),
)

internal val bundledPresets = listOf(
    BundledPreset("music-sprinkler"),
    BundledPreset("space-launch"),
    BundledPreset("firefly-garden"),
    BundledPreset("lucky-cat"),
    BundledPreset("moonlight-waltz"),
    BundledPreset("station-train"),
    BundledPreset("battery-mood-show"),
    BundledPreset("street-chef"),
    BundledPreset("penalty-keeper"),
    BundledPreset("traffic-officer"),
    BundledPreset("bomb-squad"),
    BundledPreset("curious-sentry"),
    BundledPreset(
        "friday-disco",
        files = listOf(
            "manifest.json",
            "script.py",
            "audio/0_friday_night.opus",
        ),
    ),
)

private val presetJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

internal suspend fun loadBundledPreset(preset: BundledPreset): StoredScript {
    val manifestBytes = cn.elonzh.hanppie.resources.Res.readBytes("files/presets/${preset.folderName}/manifest.json")
    val manifest = presetJson.decodeFromString<ScriptManifest>(manifestBytes.decodeToString())
    val scriptSource = cn.elonzh.hanppie.resources.Res.readBytes("files/presets/${preset.folderName}/script.py").decodeToString()
    val audioClips = preset.files.filter { it.startsWith("audio/") && it.endsWith(".opus") }.mapNotNull { audioFile ->
        val fileName = audioFile.substringAfter("audio/").substringBefore(".opus")
        val slot = fileName.substringBefore('_').toIntOrNull() ?: return@mapNotNull null
        val name = fileName.substringAfter('_', missingDelimiterValue = "audio")
        val bytes = cn.elonzh.hanppie.resources.Res.readBytes("files/presets/${preset.folderName}/$audioFile")
        val duration = ScriptBundle.parsePacketsDuration(bytes).coerceAtLeast(LabAudioClip.FRAME_DURATION_MILLIS)
        LabAudioClip(slot, name, duration, bytes)
    }
    return StoredScript(
        id = manifest.id,
        name = manifest.name,
        source = scriptSource,
        createdAtEpochMillis = manifest.createdAtEpochMillis,
        updatedAtEpochMillis = manifest.updatedAtEpochMillis,
        summary = manifest.summary,
        audioClips = audioClips,
    )
}

internal suspend fun loadAllBundledPresets(): List<StoredScript> {
    return bundledPresets.map { loadBundledPreset(it) }
}

internal interface ScriptRepository {
    suspend fun all(): List<StoredScript>
    suspend fun presets(): List<StoredScript>
    suspend fun insert(script: StoredScript)
    suspend fun update(script: StoredScript)
    suspend fun delete(script: StoredScript)
    suspend fun audio(scriptId: String): List<LabAudioClip>
    suspend fun insertAudio(scriptId: String, audio: LabAudioClip)
    suspend fun updateAudio(scriptId: String, audio: LabAudioClip)
    suspend fun deleteAudio(scriptId: String, nativeId: Int): Boolean
    suspend fun replaceAllAudio(scriptId: String, clips: List<LabAudioClip>)
}

internal const val MAX_SCRIPT_LENGTH = 1_000_000

internal fun validScriptName(value: String): Boolean =
    value.isNotBlank() && value.length <= 64 && value.none { it == '\n' || it == '\r' || it.isISOControl() }

internal fun normalizeScriptName(value: String): String = value.trim().also {
    require(validScriptName(it)) { "Script name must contain 1 to 64 visible characters" }
}

internal fun suggestedScriptFileName(displayName: String?): String {
    val safeBase = displayName.orEmpty().trim()
        .map { if (it.isISOControl() || it in "<>:\"/\\|?*") '_' else it }
        .joinToString("")
        .trimEnd(' ', '.')
        .ifBlank { "script" }
    return if (safeBase.endsWith(".py", ignoreCase = true)) safeBase else "$safeBase.py"
}
