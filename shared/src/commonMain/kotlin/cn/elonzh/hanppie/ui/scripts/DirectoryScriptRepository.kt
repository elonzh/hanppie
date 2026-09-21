package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.isRegularFile
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.resolve
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val repoLogger = KotlinLogging.logger {}

internal class DirectoryScriptRepository(
    private val userDirectory: PlatformFile,
    private val presetsDirectory: PlatformFile,
    private val enablePresetSync: Boolean = true,
) : ScriptRepository {
    private val syncMutex = Mutex()
    private var presetsSynced = false

    suspend fun syncBundledPresetsIfNeeded() = syncMutex.withLock {
        if (presetsSynced || !enablePresetSync) return@withLock
        try {
            presetsDirectory.createDirectories()
            val activePresetDirs = bundledPresets.map { it.folderName }.toSet()
            if (presetsDirectory.isDirectory()) {
                presetsDirectory.list().filter { it.isDirectory() && it.name !in activePresetDirs }.forEach {
                    ScriptBundle.deleteRecursively(it)
                }
            }
            for (preset in bundledPresets) {
                val dir = presetsDirectory.resolve(preset.folderName)
                dir.createDirectories()
                for (fileRelPath in preset.files) {
                    val targetFile = dir.resolve(fileRelPath)
                    val resourcePath = "files/presets/${preset.folderName}/$fileRelPath"
                    val bytes = Res.readBytes(resourcePath)
                    val currentBytes = if (targetFile.isRegularFile()) targetFile.readBytes() else null
                    if (currentBytes == null || !currentBytes.contentEquals(bytes)) {
                        if (fileRelPath.contains('/')) {
                            val parentDirName = fileRelPath.substringBeforeLast('/')
                            dir.resolve(parentDirName).createDirectories()
                        }
                        targetFile.write(bytes)
                    }
                }
                val audioDir = dir.resolve("audio")
                if (audioDir.isDirectory()) {
                    val expectedAudioFiles = preset.files
                        .filter { it.startsWith("audio/") }
                        .map { it.substringAfter("audio/") }
                        .toSet()
                    audioDir.list().filter { it.isRegularFile() && it.name !in expectedAudioFiles }.forEach {
                        it.delete()
                    }
                }
            }
            presetsSynced = true
        } catch (e: Exception) {
            repoLogger.warn(e) { "Failed to sync bundled presets to disk" }
        }
    }

    override suspend fun all(): List<StoredScript> {
        userDirectory.createDirectories()
        return ScriptBundle.list(userDirectory).map { it.toStoredScript() }
    }

    override suspend fun presets(): List<StoredScript> {
        syncBundledPresetsIfNeeded()
        presetsDirectory.createDirectories()
        return ScriptBundle.list(presetsDirectory).map { it.toStoredScript() }
    }

    override suspend fun insert(script: StoredScript) {
        userDirectory.createDirectories()
        val dir = userDirectory.resolve(script.id)
        val manifest = ScriptManifest(
            id = script.id,
            name = script.name,
            summary = script.summary,
            createdAtEpochMillis = script.createdAtEpochMillis,
            updatedAtEpochMillis = script.updatedAtEpochMillis,
        )
        ScriptBundle.save(dir, manifest, script.source, script.audioClips)
    }

    override suspend fun update(script: StoredScript) {
        userDirectory.createDirectories()
        val dir = resolveScriptDir(userDirectory, script.id)
        val existing = ScriptBundle.load(dir)
        val manifest = ScriptManifest(
            id = script.id,
            name = script.name,
            summary = script.summary ?: existing?.summary,
            createdAtEpochMillis = script.createdAtEpochMillis,
            updatedAtEpochMillis = script.updatedAtEpochMillis,
        )
        val clips = if (script.audioClips.isNotEmpty()) script.audioClips else existing?.audioClips ?: emptyList()
        ScriptBundle.save(dir, manifest, script.source, clips)
    }

    override suspend fun delete(script: StoredScript) {
        val dir = resolveScriptDir(userDirectory, script.id)
        if (dir.isDirectory()) {
            ScriptBundle.deleteRecursively(dir)
        }
    }

    override suspend fun audio(scriptId: String): List<LabAudioClip> {
        val userDir = resolveScriptDir(userDirectory, scriptId)
        val userPkg = ScriptBundle.load(userDir)
        if (userPkg != null && userPkg.audioClips.isNotEmpty()) return userPkg.audioClips

        syncBundledPresetsIfNeeded()
        val presetDir = resolveScriptDir(presetsDirectory, scriptId)
        val presetPkg = ScriptBundle.load(presetDir)
        return presetPkg?.audioClips ?: userPkg?.audioClips ?: emptyList()
    }

    override suspend fun insertAudio(scriptId: String, audio: LabAudioClip) {
        userDirectory.createDirectories()
        val dir = resolveScriptDir(userDirectory, scriptId)
        dir.createDirectories()
        val audioDir = dir.resolve("audio")
        audioDir.createDirectories()
        audioDir.list().filter { it.isRegularFile() && it.name.startsWith("${audio.id}_") }.forEach { it.delete() }
        val targetFile = audioDir.resolve("${audio.id}_${audio.name}.opus")
        targetFile.write(audio.packets)
    }

    override suspend fun updateAudio(scriptId: String, audio: LabAudioClip) {
        insertAudio(scriptId, audio)
    }

    override suspend fun deleteAudio(scriptId: String, nativeId: Int): Boolean {
        val dir = resolveScriptDir(userDirectory, scriptId)
        val audioDir = dir.resolve("audio")
        if (!audioDir.isDirectory()) return false
        val matching = audioDir.list().filter { it.isRegularFile() && it.name.startsWith("${nativeId}_") }
        if (matching.isEmpty()) return false
        matching.forEach { it.delete() }
        return true
    }

    override suspend fun replaceAllAudio(scriptId: String, clips: List<LabAudioClip>) {
        userDirectory.createDirectories()
        val dir = resolveScriptDir(userDirectory, scriptId)
        dir.createDirectories()
        val audioDir = dir.resolve("audio")
        if (clips.isEmpty()) {
            if (audioDir.isDirectory()) ScriptBundle.deleteRecursively(audioDir)
            return
        }
        audioDir.createDirectories()
        audioDir.list().filter { it.isRegularFile() }.forEach { it.delete() }
        for (clip in clips) {
            val targetFile = audioDir.resolve("${clip.id}_${clip.name}.opus")
            targetFile.write(clip.packets)
        }
    }

    private fun resolveScriptDir(rootDir: PlatformFile, scriptId: String): PlatformFile {
        val exact = rootDir.resolve(scriptId)
        if (exact.isDirectory()) return exact
        val alt = rootDir.resolve(scriptId.replace('-', '_'))
        if (alt.isDirectory()) return alt
        return exact
    }
}
