package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.agent.skills.SkillFileSystem
import java.nio.file.Files
import cn.elonzh.hanppie.agent.skills.BuiltinSkills
import cn.elonzh.hanppie.agent.skills.SkillLibrary
import kotlinx.coroutines.async
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cn.elonzh.hanppie.agent.runtime.AgentRuntimeStorage
import cn.elonzh.hanppie.agent.runtime.openAgentRuntimeStorage
import cn.elonzh.hanppie.ui.scripts.DirectoryScriptRepository
import cn.elonzh.hanppie.ui.settings.DataStoreSettingsStore
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.databasesDir
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

private val storageLogger = KotlinLogging.logger {}

internal fun createJvmWorkbenchStorage(): WorkbenchStorage {
    val filesDirectory = FileKit.filesDir.apply { createDirectories() }
    val databasesDirectory = FileKit.databasesDir.apply { createDirectories() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(PlatformFile(filesDirectory, "settings.preferences_pb").path) },
    )
    var agentRuntime: AgentRuntimeStorage? = null
    try {
        agentRuntime = openAgentRuntimeStorage(
            databasePath = File(PlatformFile(databasesDirectory, "agent-runtime.db").path).toPath(),
            eventDirectory = File(PlatformFile(filesDirectory, "agent-runtime").path).toPath().resolve("sessions"),
        )
        val scriptRepository = DirectoryScriptRepository(
            userDirectory = PlatformFile(filesDirectory, "scripts"),
            presetsDirectory = PlatformFile(filesDirectory, "presets"),
        )
        storageLogger.info { "Workbench storage initialized" }
        return WorkbenchStorage(
            settings = DataStoreSettingsStore(dataStore),
            scripts = scriptRepository,
            skills = scope.async {
                val directory = File(PlatformFile(filesDirectory, "skills/builtin").path).toPath()
                BuiltinSkills.install { path, bytes ->
                    val target = directory.resolve(path)
                    Files.createDirectories(target.parent)
                    Files.write(target, bytes)
                }
                SkillLibrary.load(SkillFileSystem, directory) { it.toRealPath() }
            },
            agentRuntime = agentRuntime,
            scope = scope,
        )
    } catch (error: Exception) {
        agentRuntime?.close()
        scope.cancel()
        throw error
    }
}
