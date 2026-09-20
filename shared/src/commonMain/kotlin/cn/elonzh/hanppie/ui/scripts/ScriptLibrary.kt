package cn.elonzh.hanppie.ui.scripts

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

private const val LOAD_INTERRUPTED = "Script library load was interrupted"

internal data class ScriptLibraryState(
    val scripts: List<StoredScript> = emptyList(),
    val presets: List<StoredScript> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalUuidApi::class)
internal class ScriptLibrary(
    private val repository: ScriptRepository,
    private val clock: Clock = Clock.System,
) {
    val state = MutableStateFlow(ScriptLibraryState())
    private val mutex = Mutex()

    suspend fun load() = mutex.withLock {
        state.value = state.value.copy(loading = true, error = null)
        try {
            val scripts = repository.all()
            val presets = repository.presets()
            state.value = ScriptLibraryState(
                scripts = scripts.sortedByDescending { it.createdAtEpochMillis },
                presets = presets,
                loading = false,
            )
        } catch (error: CancellationException) {
            state.value = state.value.copy(loading = false, error = LOAD_INTERRUPTED)
            throw error
        } catch (error: Exception) {
            scriptLibraryLogger.error(error) { "Could not load the script library" }
            state.value = state.value.copy(loading = false, error = error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun create(
        name: String,
        source: String,
        audioClips: List<LabAudioClip> = emptyList(),
    ): StoredScript = operation {
        val normalized = normalizeScriptName(name)
        ensureUnique(normalized)
        require(source.length <= MAX_SCRIPT_LENGTH) { "Script is too large" }
        val now = clock.now().toEpochMilliseconds()
        val sanitizedAudio = audioClips.distinctBy { it.id }.sortedBy { it.id }
        val script = StoredScript(Uuid.random().toString(), normalized, source, now, now, audioClips = sanitizedAudio)
        repository.insert(script)
        refresh(script)
        script
    }

    suspend fun update(id: String, source: String): StoredScript = operation {
        require(source.length <= MAX_SCRIPT_LENGTH) { "Script is too large" }
        val current = state.value.scripts.firstOrNull { it.id == id } ?: error("Script no longer exists")
        val updated = current.copy(source = source, updatedAtEpochMillis = clock.now().toEpochMilliseconds())
        repository.update(updated)
        refresh(updated)
        updated
    }

    suspend fun rename(id: String, name: String): StoredScript = operation {
        val normalized = normalizeScriptName(name)
        ensureUnique(normalized, exceptId = id)
        val current = state.value.scripts.firstOrNull { it.id == id } ?: error("Script no longer exists")
        val updated = current.copy(name = normalized, updatedAtEpochMillis = clock.now().toEpochMilliseconds())
        repository.update(updated)
        refresh(updated)
        updated
    }

    suspend fun delete(id: String) = operation {
        check(state.value.scripts.any { it.id == id }) { "Script no longer exists" }
        val current = state.value.scripts.first { it.id == id }
        repository.delete(current)
        state.value = state.value.copy(scripts = state.value.scripts.filterNot { it.id == id }, error = null)
    }

    suspend fun savedScripts(): List<StoredScript> = awaitReady().scripts

    suspend fun read(name: String): StoredScript {
        val normalized = normalizeScriptName(name)
        return awaitReady().scripts.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
            ?: error("A script named '$normalized' does not exist")
    }

    /** Creates or atomically replaces/renames a saved script by its user-visible name. */
    suspend fun save(originalName: String?, name: String, source: String): StoredScript {
        awaitReady()
        return operation {
            val normalized = normalizeScriptName(name)
            require(source.length <= MAX_SCRIPT_LENGTH) { "Script is too large" }
            val now = clock.now().toEpochMilliseconds()
            if (originalName == null) {
                ensureUnique(normalized)
                StoredScript(Uuid.random().toString(), normalized, source, now, now).also { script ->
                    repository.insert(script)
                    refresh(script)
                }
            } else {
                val original = normalizeScriptName(originalName)
                val current = state.value.scripts.firstOrNull { it.name.equals(original, ignoreCase = true) }
                    ?: error("A script named '$original' does not exist")
                ensureUnique(normalized, exceptId = current.id)
                current.copy(name = normalized, source = source, updatedAtEpochMillis = now).also { script ->
                    repository.update(script)
                    refresh(script)
                }
            }
        }
    }

    suspend fun deleteByName(name: String): StoredScript {
        awaitReady()
        return operation {
            val normalized = normalizeScriptName(name)
            val current = state.value.scripts.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
                ?: error("A script named '$normalized' does not exist")
            repository.delete(current)
            state.value = state.value.copy(scripts = state.value.scripts.filterNot { it.id == current.id }, error = null)
            current
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
        require(state.value.scripts.none { it.id != exceptId && it.name.equals(name, ignoreCase = true) }) {
            "A script named '$name' already exists"
        }
    }

    private suspend fun awaitReady(): ScriptLibraryState = state.first { !it.loading }.also { ready ->
        check(ready.error == null) { "Script library is unavailable: ${ready.error}" }
    }

    private suspend fun <T> operation(block: suspend () -> T): T = mutex.withLock {
        state.value = state.value.copy(busy = true, error = null)
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            scriptLibraryLogger.error(error) { "Script library operation failed" }
            state.value = state.value.copy(error = error.message ?: error.javaClass.simpleName)
            throw error
        } finally {
            state.value = state.value.copy(busy = false)
        }
    }
}
