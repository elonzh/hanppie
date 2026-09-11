package cn.elonzh.hanppie.ui

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

internal data class ScriptLibraryState(
    val scripts: List<StoredScript> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Blocking storage. Callers serialize access and move it to Dispatchers.IO. */
internal interface ScriptStore {
    fun load(): List<StoredScript>
    fun save(scripts: List<StoredScript>)
}

internal class MemoryScriptStore(initial: List<StoredScript> = emptyList()) : ScriptStore {
    private var saved = initial.toList()
    override fun load(): List<StoredScript> = saved.toList()
    override fun save(scripts: List<StoredScript>) { saved = scripts.toList() }
}

@Serializable
private data class ScriptLibraryFile(val version: Int = 1, val scripts: List<StoredScript>)

private val scriptLibraryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal class JsonScriptStore(private val file: Path) : ScriptStore {
    override fun load(): List<StoredScript> {
        if (!Files.exists(file)) return emptyList()
        val decoded = scriptLibraryJson.decodeFromString<ScriptLibraryFile>(Files.readString(file))
        require(decoded.version == 1) { "Unsupported script library version: ${decoded.version}" }
        require(decoded.scripts.map { it.id }.distinct().size == decoded.scripts.size) {
            "Script library contains duplicate identifiers"
        }
        require(decoded.scripts.map { it.name.lowercase(Locale.ROOT) }.distinct().size == decoded.scripts.size) {
            "Script library contains duplicate names"
        }
        return decoded.scripts
    }

    override fun save(scripts: List<StoredScript>) {
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "library-v1-", ".tmp")
        try {
            Files.writeString(temporary, scriptLibraryJson.encodeToString(ScriptLibraryFile(scripts = scripts)))
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

internal class ScriptLibrary(private val store: ScriptStore) {
    val state = MutableStateFlow(ScriptLibraryState())
    private val mutex = Mutex()

    suspend fun load() = mutex.withLock {
        state.value = state.value.copy(loading = true, error = null)
        try {
            val scripts = withContext(Dispatchers.IO) { store.load() }
            state.value = ScriptLibraryState(scripts = scripts.sortedByDescending { it.updatedAtEpochMillis }, loading = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.value = ScriptLibraryState(loading = false, error = error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun create(name: String, source: String): StoredScript = operation {
        val normalized = normalizeScriptName(name)
        ensureUnique(normalized)
        require(source.length <= MAX_SCRIPT_LENGTH) { "Script is too large" }
        val now = System.currentTimeMillis()
        val script = StoredScript(UUID.randomUUID().toString(), normalized, source, now, now)
        persist(listOf(script) + state.value.scripts)
        script
    }

    suspend fun update(id: String, source: String): StoredScript = operation {
        require(source.length <= MAX_SCRIPT_LENGTH) { "Script is too large" }
        val current = state.value.scripts.firstOrNull { it.id == id } ?: error("Script no longer exists")
        val updated = current.copy(source = source, updatedAtEpochMillis = System.currentTimeMillis())
        persist(state.value.scripts.map { if (it.id == id) updated else it })
        updated
    }

    suspend fun rename(id: String, name: String): StoredScript = operation {
        val normalized = normalizeScriptName(name)
        ensureUnique(normalized, exceptId = id)
        val current = state.value.scripts.firstOrNull { it.id == id } ?: error("Script no longer exists")
        val updated = current.copy(name = normalized, updatedAtEpochMillis = System.currentTimeMillis())
        persist(state.value.scripts.map { if (it.id == id) updated else it })
        updated
    }

    suspend fun delete(id: String) = operation {
        check(state.value.scripts.any { it.id == id }) { "Script no longer exists" }
        persist(state.value.scripts.filterNot { it.id == id })
    }

    fun uniqueName(preferred: String): String {
        val base = normalizeScriptName(preferred)
        val names = state.value.scripts.map { it.name.lowercase(Locale.ROOT) }.toSet()
        if (base.lowercase(Locale.ROOT) !in names) return base
        var suffix = 2
        while ("$base $suffix".lowercase(Locale.ROOT) in names) suffix++
        return "$base $suffix"
    }

    private suspend fun persist(scripts: List<StoredScript>) {
        val sorted = scripts.sortedByDescending { it.updatedAtEpochMillis }
        withContext(Dispatchers.IO) { store.save(sorted) }
        state.value = state.value.copy(scripts = sorted, error = null)
    }

    private fun ensureUnique(name: String, exceptId: String? = null) {
        require(state.value.scripts.none { it.id != exceptId && it.name.equals(name, ignoreCase = true) }) {
            "A script named '$name' already exists"
        }
    }

    private suspend fun <T> operation(block: suspend () -> T): T = mutex.withLock {
        state.value = state.value.copy(busy = true, error = null)
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.value = state.value.copy(error = error.message ?: error.javaClass.simpleName)
            throw error
        } finally {
            state.value = state.value.copy(busy = false)
        }
    }
}
