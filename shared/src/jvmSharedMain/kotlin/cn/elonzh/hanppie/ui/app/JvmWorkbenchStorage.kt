package cn.elonzh.hanppie.ui.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
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
    var database: HanppieDatabase? = null
    try {
        database = buildHanppieDatabase(
            Room.databaseBuilder<HanppieDatabase>(
                name = PlatformFile(databasesDirectory, "hanppie.db").path,
            ),
        )
        storageLogger.info { "Workbench storage initialized" }
        return WorkbenchStorage(
            settings = DataStoreSettingsStore(dataStore),
            scripts = RoomScriptRepository(database.scriptDao()),
            database = database,
            scope = scope,
        )
    } catch (error: Exception) {
        database?.close()
        scope.cancel()
        throw error
    }
}
