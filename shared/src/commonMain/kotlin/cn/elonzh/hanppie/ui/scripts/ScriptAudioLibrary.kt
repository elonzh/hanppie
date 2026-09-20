package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.robot.lab.LabProgram
import cn.elonzh.hanppie.ui.robot.audio.AudioPlaybackState
import cn.elonzh.hanppie.ui.robot.audio.LabAudioImporter
import cn.elonzh.hanppie.ui.robot.audio.LabAudioPlayer
import cn.elonzh.hanppie.ui.robot.audio.NoLabAudioPlayer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val player: LabAudioPlayer = NoLabAudioPlayer(),
) {
    val state = MutableStateFlow(ScriptAudioState())
    val playbackState: StateFlow<AudioPlaybackState> = player.state
    private val mutex = Mutex()

    fun play(clip: LabAudioClip) {
        if (playbackState.value.playingClipId == clip.id && !playbackState.value.isPlaying) {
            resume()
        } else {
            player.play(clip)
        }
    }
    fun pause() = player.pause()
    fun resume() = player.resume()
    fun stop() = player.stop()

    /** Binds the editor's current script; passing null unbinds and drops the loaded clips, or seeds initialClips. */
    suspend fun open(scriptId: String?, initialClips: List<LabAudioClip> = emptyList()) = mutex.withLock {
        player.stop()
        val sanitized = initialClips.distinctBy { it.id }.sortedBy { it.id }
        if (state.value.scriptId == scriptId && (scriptId != null || state.value.clips == sanitized)) return@withLock
        state.value = ScriptAudioState(scriptId = scriptId, clips = if (scriptId == null) sanitized else emptyList(), loading = scriptId != null)
        if (scriptId == null) return@withLock
        try {
            state.value = state.value.copy(clips = repository.audio(scriptId).distinctBy { it.id }.sortedBy { it.id }, loading = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            audioLogger.error(error) { "Could not load script audio" }
            state.value = state.value.copy(loading = false, error = error.message ?: error.javaClass.simpleName)
        }
    }

    /** Persists clips for a newly saved script and updates the bound state. */
    suspend fun saveClipsFor(scriptId: String, clips: List<LabAudioClip>) = mutex.withLock {
        val uniqueClips = clips.distinctBy { it.id }.sortedBy { it.id }
        repository.replaceAllAudio(scriptId, uniqueClips)
        state.value = ScriptAudioState(scriptId = scriptId, clips = uniqueClips, loading = false)
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
        repository.insertAudio(scriptId, clip)
        publish(clip)
        clip
    }

    suspend fun rename(nativeId: Int, name: String): LabAudioClip = busy {
        val scriptId = checkNotNull(state.value.scriptId) { "请先保存脚本，再修改自定义音频" }
        val renamed = LabAudioClip(current(nativeId).id, labAudioName(name), current(nativeId).durationMillis, current(nativeId).packets)
        repository.updateAudio(scriptId, renamed)
        publish(renamed)
        renamed
    }

    suspend fun delete(nativeId: Int) = busy {
        val scriptId = checkNotNull(state.value.scriptId) { "请先保存脚本，再删除自定义音频" }
        current(nativeId)
        if (playbackState.value.playingClipId == nativeId) {
            player.stop()
        }
        check(repository.deleteAudio(scriptId, nativeId)) { "自定义音频已不存在" }
        state.value = state.value.copy(clips = state.value.clips.filterNot { it.id == nativeId })
    }

    suspend fun reorder(newOrder: List<LabAudioClip>) = busy {
        val scriptId = checkNotNull(state.value.scriptId) { "请先保存脚本，再调整音频顺序" }
        player.stop()
        val reordered = newOrder.mapIndexed { index, clip ->
            LabAudioClip(index, clip.name, clip.durationMillis, clip.packets)
        }
        repository.replaceAllAudio(scriptId, reordered)
        state.value = state.value.copy(clips = reordered, error = null)
    }

    suspend fun move(fromIndex: Int, toIndex: Int) = busy {
        val scriptId = checkNotNull(state.value.scriptId) { "请先保存脚本，再调整音频顺序" }
        val current = state.value.clips.toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return@busy
        player.stop()
        val moved = current.removeAt(fromIndex)
        current.add(toIndex, moved)
        val reordered = current.mapIndexed { index, clip ->
            LabAudioClip(index, clip.name, clip.durationMillis, clip.packets)
        }
        repository.replaceAllAudio(scriptId, reordered)
        state.value = state.value.copy(clips = reordered, error = null)
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
