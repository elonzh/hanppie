package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
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

/** App validation keeps resource identity; codec/storage details remain unchanged. */
private class ScriptAudioFailure(val feedback: UiText) : IllegalStateException(feedback.resolve())
private fun rejectAudio(feedback: UiText): Nothing = throw ScriptAudioFailure(feedback)

/** Refuses oversized host files before decoding to prevent host memory exhaustion. */
private const val MAX_AUDIO_FILE_BYTES = 50 * 1024 * 1024

internal data class ScriptAudioState(
    val scriptId: String? = null,
    val clips: List<LabAudioClip> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: UiText? = null,
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
        if (state.value.scriptId == scriptId && state.value.error == null && (scriptId != null || state.value.clips == sanitized)) return@withLock
        state.value = ScriptAudioState(scriptId = scriptId, clips = if (scriptId == null) sanitized else emptyList(), loading = scriptId != null)
        if (scriptId == null) return@withLock
        try {
            state.value = state.value.copy(clips = repository.audio(scriptId).distinctBy { it.id }.sortedBy { it.id }, loading = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            audioLogger.error(error) { "Could not load script audio" }
            state.value = state.value.copy(loading = false, error = uiText(Res.string.script_audio_load_failed_value, error.message ?: error::class.simpleName.orEmpty()))
        }
    }

    /** Persists clips for a newly saved script and updates the bound state. */
    suspend fun saveClipsFor(scriptId: String, clips: List<LabAudioClip>) = mutex.withLock {
        val uniqueClips = clips.distinctBy { it.id }.sortedBy { it.id }
        repository.replaceAllAudio(scriptId, uniqueClips)
        state.value = ScriptAudioState(scriptId = scriptId, clips = uniqueClips, loading = false)
    }

    suspend fun import(name: String, bytes: ByteArray): LabAudioClip = busy {
        val scriptId = state.value.scriptId ?: rejectAudio(uiText(Res.string.script_audio_needs_script))
        val current = state.value.clips
        val id = freeAudioId(current) ?: rejectAudio(uiText(Res.string.script_audio_slots_full, LabAudioClip.MAX_CLIPS))
        if (bytes.size > MAX_AUDIO_FILE_BYTES) rejectAudio(uiText(Res.string.script_audio_file_too_large, MAX_AUDIO_FILE_BYTES / (1024 * 1024)))
        val decoded = importer.decode(name, bytes)
        val clip = LabAudioClip(id, labAudioName(name), decoded.durationMillis, decoded.packets)
        if (LabAudioClip.totalEncodedBytes(current + clip) > LabProgram.MAX_DSP_BYTES)
            rejectAudio(uiText(Res.string.script_audio_budget_limit, LabProgram.MAX_DSP_BYTES / (1024 * 1024)))
        repository.insertAudio(scriptId, clip)
        publish(clip)
        clip
    }

    suspend fun rename(nativeId: Int, name: String): LabAudioClip = busy {
        val scriptId = state.value.scriptId ?: rejectAudio(uiText(Res.string.script_audio_needs_script))
        val renamed = LabAudioClip(current(nativeId).id, labAudioName(name), current(nativeId).durationMillis, current(nativeId).packets)
        repository.updateAudio(scriptId, renamed)
        publish(renamed)
        renamed
    }

    suspend fun delete(nativeId: Int) = busy {
        val scriptId = state.value.scriptId ?: rejectAudio(uiText(Res.string.script_audio_needs_script))
        current(nativeId)
        if (playbackState.value.playingClipId == nativeId) {
            player.stop()
        }
        if (!repository.deleteAudio(scriptId, nativeId)) rejectAudio(uiText(Res.string.script_audio_missing))
        state.value = state.value.copy(clips = state.value.clips.filterNot { it.id == nativeId })
    }

    suspend fun reorder(newOrder: List<LabAudioClip>) = busy {
        val scriptId = state.value.scriptId ?: rejectAudio(uiText(Res.string.script_audio_needs_script))
        player.stop()
        val reordered = newOrder.mapIndexed { index, clip ->
            LabAudioClip(index, clip.name, clip.durationMillis, clip.packets)
        }
        repository.replaceAllAudio(scriptId, reordered)
        state.value = state.value.copy(clips = reordered, error = null)
    }

    /** A queued UI move belongs to the exact bound list that offered the action. */
    suspend fun move(fromIndex: Int, toIndex: Int, expected: ScriptAudioState): Boolean = mutex.withLock {
        if (state.value !== expected || expected.busy || expected.loading || expected.scriptId == null ||
            fromIndex !in expected.clips.indices || toIndex !in expected.clips.indices || fromIndex == toIndex) return@withLock false
        runBusy {
            player.stop()
            val current = expected.clips.toMutableList()
            current.add(toIndex, current.removeAt(fromIndex))
            val reordered = current.mapIndexed { index, clip ->
                LabAudioClip(index, clip.name, clip.durationMillis, clip.packets)
            }
            repository.replaceAllAudio(expected.scriptId, reordered)
            state.value = state.value.copy(clips = reordered, error = null)
            true
        }
    }

    private fun current(nativeId: Int): LabAudioClip =
        state.value.clips.firstOrNull { it.id == nativeId } ?: rejectAudio(uiText(Res.string.script_audio_missing))

    private fun publish(clip: LabAudioClip) {
        val clips = (state.value.clips.filterNot { it.id == clip.id } + clip).sortedBy { it.id }
        state.value = state.value.copy(clips = clips, error = null)
    }

    private suspend fun <T> busy(block: suspend () -> T): T = mutex.withLock { runBusy(block) }

    /** Caller holds the library mutex, including admission checks for list-bound moves. */
    private suspend fun <T> runBusy(block: suspend () -> T): T {
        state.value = state.value.copy(busy = true, error = null)
        try {
            return block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            audioLogger.error(error) { "Script audio operation failed" }
            state.value = state.value.copy(error = if (error is ScriptAudioFailure) error.feedback
                else uiText(Res.string.script_audio_operation_failed_value, error.message ?: error::class.simpleName.orEmpty()))
            throw error
        } finally {
            state.value = state.value.copy(busy = false)
        }
    }
}
