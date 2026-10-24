package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val scriptLibraryLogger = KotlinLogging.logger {}

internal data class ScriptLibraryState(
    val scripts: List<StoredScript> = emptyList(),
    val presets: List<StoredScript> = emptyList(),
    val loading: Boolean = true,
    val initialized: Boolean = false,
    val busy: Boolean = false,
    val error: UiText? = null,
)

@OptIn(ExperimentalUuidApi::class)
internal class ScriptLibrary(
    private val repository: ScriptRepository,
    private val clock: Clock = Clock.System,
) {
    val state = MutableStateFlow(ScriptLibraryState())
    private val mutex = Mutex()

    suspend fun load() = mutex.withLock {
        state.value = state.value.copy(loading = true, initialized = false, error = null)
        try {
            val scripts = repository.all()
            val presets = repository.presets()
            state.value = ScriptLibraryState(
                scripts = scripts.sortedByDescending { it.createdAtEpochMillis },
                presets = presets,
                loading = false,
                initialized = true,
            )
        } catch (error: CancellationException) {
            state.value = state.value.copy(loading = false, error = uiText(Res.string.script_library_load_interrupted))
            throw error
        } catch (error: Exception) {
            scriptLibraryLogger.error(error) { "Could not load the script library" }
            state.value = state.value.copy(loading = false, error = uiText(Res.string.script_library_load_failed_value, error.message ?: error::class.simpleName.orEmpty()))
        }
    }

    suspend fun create(
        name: String,
        source: String,
        audioClips: List<LabAudioClip> = emptyList(),
    ): StoredScript = operation {
        val normalized = normalizedName(name)
        ensureUnique(normalized)
        if (source.length > MAX_SCRIPT_LENGTH) inputFailure(uiText(Res.string.script_library_source_too_large, MAX_SCRIPT_LENGTH))
        val now = clock.now().toEpochMilliseconds()
        val sanitizedAudio = audioClips.distinctBy { it.id }.sortedBy { it.id }
        val script = StoredScript(Uuid.random().toString(), normalized, source, now, now, audioClips = sanitizedAudio)
        repository.insert(script)
        refresh(script)
        script
    }

    suspend fun update(id: String, source: String): StoredScript = operation {
        if (source.length > MAX_SCRIPT_LENGTH) inputFailure(uiText(Res.string.script_library_source_too_large, MAX_SCRIPT_LENGTH))
        val current = state.value.scripts.firstOrNull { it.id == id } ?: stateFailure(uiText(Res.string.script_library_missing))
        val updated = current.copy(source = source, updatedAtEpochMillis = clock.now().toEpochMilliseconds())
        repository.update(updated)
        refresh(updated)
        updated
    }

    suspend fun rename(id: String, name: String): StoredScript = operation {
        val normalized = normalizedName(name)
        ensureUnique(normalized, exceptId = id)
        val current = state.value.scripts.firstOrNull { it.id == id } ?: stateFailure(uiText(Res.string.script_library_missing))
        val updated = current.copy(name = normalized, updatedAtEpochMillis = clock.now().toEpochMilliseconds())
        repository.update(updated)
        refresh(updated)
        updated
    }

    suspend fun delete(id: String): StoredScript {
        return operation {
            val current = state.value.scripts.firstOrNull { it.id == id } ?: stateFailure(uiText(Res.string.script_library_missing))
            repository.delete(current)
            state.value = state.value.copy(scripts = state.value.scripts.filterNot { it.id == id }, error = null)
            current
        }
    }

    suspend fun savedScripts(): List<StoredScript> = awaitReady().scripts

    suspend fun read(id: String): StoredScript? =
        awaitReady().scripts.firstOrNull { it.id == id }

    /** Load audio from storage, independently of whichever document the editor has open. */
    suspend fun executionSnapshot(id: String): StoredScript {
        awaitReady()
        return mutex.withLock {
            requireInitialized(state.value)
            val script = state.value.scripts.firstOrNull { it.id == id }
                ?: throw IllegalStateException(uiText(Res.string.script_library_missing).resolve())
            script.copy(audioClips = repository.audio(script.id))
        }
    }

    /** Creates or atomically replaces/renames a saved script by its stable ID. */
    suspend fun save(scriptId: String?, name: String, source: String): StoredScript {
        return operation {
            val normalized = normalizedName(name)
            if (source.length > MAX_SCRIPT_LENGTH) inputFailure(uiText(Res.string.script_library_source_too_large, MAX_SCRIPT_LENGTH))
            val now = clock.now().toEpochMilliseconds()
            if (scriptId == null) {
                ensureUnique(normalized)
                StoredScript(Uuid.random().toString(), normalized, source, now, now).also { script ->
                    repository.insert(script)
                    refresh(script)
                }
            } else {
                val current = state.value.scripts.firstOrNull { it.id == scriptId }
                    ?: stateFailure(uiText(Res.string.script_library_missing))
                ensureUnique(normalized, exceptId = current.id)
                current.copy(name = normalized, source = source, updatedAtEpochMillis = now).also { script ->
                    repository.update(script)
                    refresh(script)
                }
            }
        }
    }

    fun uniqueName(preferred: String): String {
        val base = normalizeScriptName(preferred)
        val names = state.value.scripts.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in names) return base
        var suffix = 2
        while ("$base $suffix".lowercase() in names) suffix++
        return "$base $suffix"
    }

    private fun refresh(script: StoredScript) {
        val scripts = listOf(script) + state.value.scripts.filterNot { it.id == script.id }
        state.value = state.value.copy(scripts = scripts.sortedByDescending { it.createdAtEpochMillis }, error = null)
    }

    private fun ensureUnique(name: String, exceptId: String? = null) {
        if (state.value.scripts.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) })
            inputFailure(uiText(Res.string.script_library_name_in_use, name))
    }

    private fun normalizedName(name: String): String = try { normalizeScriptName(name) }
        catch (_: IllegalArgumentException) { inputFailure(uiText(Res.string.script_name_unavailable)) }

    /** These failures run inside the serialized operation; catch retains their resource identity. */
    private fun inputFailure(message: UiText): Nothing {
        state.value = state.value.copy(error = message)
        throw IllegalArgumentException(message.resolve())
    }
    private fun stateFailure(message: UiText): Nothing {
        state.value = state.value.copy(error = message)
        throw IllegalStateException(message.resolve())
    }

    private fun requireInitialized(snapshot: ScriptLibraryState): ScriptLibraryState {
        check(snapshot.initialized) {
            uiText(Res.string.script_library_unavailable_value, snapshot.error?.resolve().orEmpty()).resolve()
        }
        return snapshot
    }
    private suspend fun awaitReady(): ScriptLibraryState = requireInitialized(state.first { !it.loading })

    private suspend fun <T> operation(block: suspend () -> T): T {
        awaitReady()
        return mutex.withLock {
            // A queued reload may have failed after awaitReady returned but before this lock.
            requireInitialized(state.value)
            state.value = state.value.copy(busy = true, error = null)
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                scriptLibraryLogger.error(error) { "Script library operation failed" }
                if (state.value.error == null) state.value = state.value.copy(
                    error = uiText(Res.string.script_library_operation_failed_value, error.message ?: error::class.simpleName.orEmpty()))
                throw error
            } finally {
                state.value = state.value.copy(busy = false)
            }
        }
    }
}
