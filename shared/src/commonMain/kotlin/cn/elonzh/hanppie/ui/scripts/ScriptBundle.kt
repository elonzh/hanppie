package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.robot.lab.LabAudioClip
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.isRegularFile
import io.github.vinceglb.filekit.lastModified
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.nameWithoutExtension
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.resolve
import io.github.vinceglb.filekit.write
import io.github.vinceglb.filekit.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ScriptManifest(
    val id: String,
    val name: String,
    val summary: String? = null,
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
)

internal data class ScriptPackage(
    val manifest: ScriptManifest,
    val source: String,
    val audioClips: List<LabAudioClip> = emptyList(),
) {
    val id: String get() = manifest.id
    val name: String get() = manifest.name
    val summary: String? get() = manifest.summary
    val createdAtEpochMillis: Long get() = manifest.createdAtEpochMillis
    val updatedAtEpochMillis: Long get() = manifest.updatedAtEpochMillis

    fun toStoredScript(): StoredScript = StoredScript(
        id = id,
        name = name,
        source = source,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        summary = summary,
        audioClips = audioClips,
    )
}

internal object ScriptBundle {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun parsePacketsDuration(bytes: ByteArray): Long {
        var offset = 0
        var frameCount = 0
        while (offset + 2 <= bytes.size) {
            val length = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2 + length
            frameCount++
        }
        return frameCount * LabAudioClip.FRAME_DURATION_MILLIS
    }

    suspend fun load(directory: PlatformFile): ScriptPackage? {
        if (!directory.isDirectory()) return null
        val scriptFile = directory.resolve("script.py")
        if (!scriptFile.isRegularFile()) return null
        val source = scriptFile.readString()

        val manifestFile = directory.resolve("manifest.json")
        val manifest = if (manifestFile.isRegularFile()) {
            try {
                json.decodeFromString<ScriptManifest>(manifestFile.readString())
            } catch (_: Exception) {
                fallbackManifest(directory, scriptFile)
            }
        } else {
            fallbackManifest(directory, scriptFile)
        }

        val audioDir = directory.resolve("audio")
        val audioClips = if (audioDir.isDirectory()) {
            audioDir.list()
                .filter { it.isRegularFile() && it.extension.equals("opus", ignoreCase = true) }
                .mapNotNull { file ->
                    val base = file.nameWithoutExtension
                    val slot = base.substringBefore('_').toIntOrNull() ?: return@mapNotNull null
                    if (slot !in 0 until LabAudioClip.MAX_CLIPS) return@mapNotNull null
                    val name = base.substringAfter('_', missingDelimiterValue = "audio")
                    val packets = file.readBytes()
                    if (packets.isEmpty()) return@mapNotNull null
                    val duration = parsePacketsDuration(packets).coerceAtLeast(LabAudioClip.FRAME_DURATION_MILLIS)
                    LabAudioClip(slot, name, duration, packets)
                }
                .distinctBy { it.id }
                .sortedBy { it.id }
        } else emptyList()

        return ScriptPackage(manifest, source, audioClips)
    }

    suspend fun save(
        directory: PlatformFile,
        manifest: ScriptManifest,
        source: String,
        clips: List<LabAudioClip>? = null,
    ) {
        directory.createDirectories()
        val scriptFile = directory.resolve("script.py")
        scriptFile.writeString(source)

        val manifestFile = directory.resolve("manifest.json")
        manifestFile.writeString(json.encodeToString(manifest))

        if (clips != null) {
            val audioDir = directory.resolve("audio")
            if (clips.isEmpty()) {
                if (audioDir.isDirectory()) {
                    deleteRecursively(audioDir)
                }
            } else {
                audioDir.createDirectories()
                val currentFileNames = clips.map { "${it.id}_${it.name}.opus" }.toSet()
                audioDir.list().forEach { file ->
                    if (file.name !in currentFileNames) file.delete()
                }
                for (clip in clips) {
                    val file = audioDir.resolve("${clip.id}_${clip.name}.opus")
                    file.write(clip.packets)
                }
            }
        }
    }

    suspend fun list(rootDir: PlatformFile): List<ScriptPackage> {
        if (!rootDir.isDirectory()) return emptyList()
        return rootDir.list()
            .filter { it.isDirectory() }
            .mapNotNull { load(it) }
            .sortedByDescending { it.createdAtEpochMillis }
    }

    suspend fun deleteRecursively(directory: PlatformFile) {
        if (directory.isDirectory()) {
            directory.list().forEach { child ->
                deleteRecursively(child)
            }
        }
        directory.delete()
    }

    private fun fallbackManifest(directory: PlatformFile, scriptFile: PlatformFile): ScriptManifest {
        val lastModified = try {
            scriptFile.lastModified().toEpochMilliseconds()
        } catch (_: Exception) {
            0L
        }
        return ScriptManifest(
            id = directory.name,
            name = directory.name,
            createdAtEpochMillis = lastModified,
            updatedAtEpochMillis = lastModified,
        )
    }
}
