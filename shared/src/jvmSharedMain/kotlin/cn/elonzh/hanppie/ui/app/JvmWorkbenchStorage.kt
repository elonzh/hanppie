package cn.elonzh.hanppie.ui.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import cn.elonzh.hanppie.agent.runtime.AgentRuntimeStorage
import cn.elonzh.hanppie.agent.runtime.openAgentRuntimeStorage
import cn.elonzh.hanppie.ui.scripts.HanppieDatabase
import cn.elonzh.hanppie.ui.scripts.RoomScriptRepository
import cn.elonzh.hanppie.ui.scripts.buildHanppieDatabase
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
    var applicationDatabase: HanppieDatabase? = null
    var agentRuntime: AgentRuntimeStorage? = null
    try {
        applicationDatabase = buildHanppieDatabase(
            Room.databaseBuilder<HanppieDatabase>(
                name = PlatformFile(databasesDirectory, "hanppie.db").path,
            ),
        )
        agentRuntime = openAgentRuntimeStorage(
            databasePath = File(PlatformFile(databasesDirectory, "agent-runtime.db").path).toPath(),
            eventDirectory = File(PlatformFile(filesDirectory, "agent-runtime").path).toPath().resolve("sessions"),
        )
        storageLogger.info { "Workbench storage initialized" }
        return WorkbenchStorage(
            settings = DataStoreSettingsStore(dataStore),
            scripts = RoomScriptRepository(applicationDatabase.scriptDao()),
            agentRuntime = agentRuntime,
            applicationDatabase = applicationDatabase,
            scope = scope,
        )
    } catch (error: Exception) {
        agentRuntime?.close()
        applicationDatabase?.close()
        scope.cancel()
        throw error
    }
}
