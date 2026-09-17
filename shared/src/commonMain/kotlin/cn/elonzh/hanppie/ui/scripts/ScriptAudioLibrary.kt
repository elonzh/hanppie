package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.robot.lab.LabProgram
import cn.elonzh.hanppie.ui.robot.audio.LabAudioImporter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val audioLogger = KotlinLogging.logger {}

/** The DSP budget only fits seconds of audio, so oversized host files are refused before decoding. */
private const val MAX_AUDIO_FILE_BYTES = 4 * 1024 * 1024

internal data class ScriptAudioState(
    val scriptId: String? = null,
    val clips: List<LabAudioClip> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Chooses the first free resource id; the machine only exposes ids 0..9. */
internal fun freeAudioId(clips: List<LabAudioClip>): Int? =
    (0 until LabAudioClip.MAX_CLIPS).firstOrNull { id -> clips.none { it.id == id } }

/** Resource names come from host file names: drop the extension, then fit the DSP field. */
internal fun labAudioName(raw: String): String {
    val base = raw.trim().substringBeforeLast('.').trim()
    return base.take(LabAudioClip.MAX_NAME_LENGTH).ifBlank { "audio" }
}

/**
 * Custom audio that belongs to one saved script.
 *
 * Audio lives in the DSP container, so it is edited per program, stored next to the script, and handed
 * to the Lab upload as a whole. Drafts have no identity to attach audio to, which is why importing
 * requires a saved script instead of carrying audio in the editor state.
 */
internal class ScriptAudioLibrary(
    private val repository: ScriptRepository,
    private val importer: LabAudioImporter,
) {
    val state = MutableStateFlow(ScriptAudioState())
    private val mutex = Mutex()

    /** Binds the editor's current script; passing null unbinds and drops the loaded clips. */
    suspend fun open(scriptId: String?) = mutex.withLock {
        if (state.value.scriptId == scriptId) return@withLock
        state.value = ScriptAudioState(scriptId = scriptId, loading = scriptId != null)
        if (scriptId == null) return@withLock
        try {
            state.value = state.value.copy(clips = repository.audio(scriptId).map { it.toClip() }, loading = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            audioLogger.error(error) { "Could not load script audio" }
            state.value = state.value.copy(loading = false, error = error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun import(name: String, bytes: ByteArray): LabAudioClip = busy {
        val scriptId = checkNotNull(state.value.scriptId) { "请先保存脚本，再添加自定义音频" }
        val current = state.value.clips
        val id = checkNotNull(freeAudioId(current)) { "自定义音频最多 ${LabAudioClip.MAX_CLIPS} 个" }
        require(bytes.size <= MAX_AUDIO_FILE_BYTES) { "音频文件过大（上限 ${MAX_AUDIO_FILE_BYTES / (1024 * 1024)} MB）" }
        val decoded = importer.decode(name, bytes)
        val clip = LabAudioClip(id, labAudioName(name), decoded.durationMillis, decoded.packets)
        require(LabAudioClip.totalEncodedBytes(current + clip) <= LabProgram.MAX_DSP_BYTES) {
            "音频体积超过 Lab 程序上传预算（${LabProgram.MAX_DSP_BYTES} 字节），请选择更短的音频"
        }
        repository.insertAudio(clip.toStored(scriptId))
        publish(clip)
        clip
    }

    suspend fun rename(nativeId: Int, name: String): LabAudioClip = busy {
        val scriptId = checkNotNull(state.value.scriptId) { "请先保存脚本，再修改自定义音频" }
        val renamed = current(nativeId).renamed(labAudioName(name))
        repository.updateAudio(renamed.toStored(scriptId))
        publish(renamed)
        renamed
    }

    suspend fun delete(nativeId: Int) = busy {
        val scriptId = checkNotNull(state.value.scriptId) { "请先保存脚本，再删除自定义音频" }
        current(nativeId)
        check(repository.deleteAudio(scriptId, nativeId)) { "自定义音频已不存在" }
        state.value = state.value.copy(clips = state.value.clips.filterNot { it.id == nativeId })
    }

    private fun current(nativeId: Int): LabAudioClip =
        state.value.clips.firstOrNull { it.id == nativeId } ?: error("自定义音频已不存在")

    private fun publish(clip: LabAudioClip) {
        val clips = (state.value.clips.filterNot { it.id == clip.id } + clip).sortedBy { it.id }
        state.value = state.value.copy(clips = clips, error = null)
    }

    private suspend fun <T> busy(block: suspend () -> T): T = mutex.withLock {
        state.value = state.value.copy(busy = true, error = null)
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            audioLogger.error(error) { "Script audio operation failed" }
            state.value = state.value.copy(error = error.message ?: error.javaClass.simpleName)
            throw error
        } finally {
            state.value = state.value.copy(busy = false)
        }
    }
}
