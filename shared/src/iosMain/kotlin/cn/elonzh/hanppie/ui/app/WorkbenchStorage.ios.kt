package cn.elonzh.hanppie.ui.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import cn.elonzh.hanppie.agent.runtime.*
import cn.elonzh.hanppie.agent.skills.*
import cn.elonzh.hanppie.ui.scripts.DirectoryScriptRepository
import cn.elonzh.hanppie.ui.settings.DataStoreSettingsStore
import io.github.vinceglb.filekit.*
import kotlinx.coroutines.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import okio.Path.Companion.toPath

internal actual fun createPlatformWorkbenchStorage(): WorkbenchStorage {
    val files = FileKit.filesDir.apply { createDirectories() }
    val databases = FileKit.databasesDir.apply { createDirectories() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val preferences = PreferenceDataStoreFactory.createWithPath(scope = scope) {
        PlatformFile(files, "settings.preferences_pb").path.toPath()
    }
    val database = buildAgentRuntimeDatabase(Room.databaseBuilder<AgentRuntimeDatabase>(
        name = PlatformFile(databases, "agent-runtime.db").path,
    ))
    try {
        val sessions = SessionRepository(
            JsonlSessionEventStore(IosJournalDirectory(PlatformFile(files, "agent-runtime/sessions").path)),
            database.sessionProjectionDao(), database,
        )
        return WorkbenchStorage(
            DataStoreSettingsStore(preferences),
            DirectoryScriptRepository(PlatformFile(files, "scripts"), PlatformFile(files, "presets")),
            scope.async {
                val root = Path(PlatformFile(files, "skills/builtin").path)
                BuiltinSkills.install { path, bytes ->
                    val target = Path(root, path)
                    SystemFileSystem.createDirectories(checkNotNull(target.parent))
                    SystemFileSystem.sink(target).buffered().use { it.write(bytes) }
                }
                SkillLibrary.load(IosSkillFileSystem, root, SystemFileSystem::resolve)
            },
            AgentRuntimeStorage(sessions, database), scope,
        )
    } catch (error: Exception) { database.close(); scope.cancel(); throw error }
}
