package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.ui.scripts.HanppieDatabase
import cn.elonzh.hanppie.ui.scripts.ScriptRepository
import cn.elonzh.hanppie.ui.settings.SettingsStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

private val storageLogger = KotlinLogging.logger {}

internal class WorkbenchStorage(
    val settings: SettingsStore,
    val scripts: ScriptRepository,
    private val database: HanppieDatabase,
    private val scope: CoroutineScope,
) : AutoCloseable {
    override fun close() {
        database.close()
        scope.cancel()
        storageLogger.debug { "Workbench storage closed" }
    }

    companion object {
        fun create(): WorkbenchStorage = createPlatformWorkbenchStorage()
    }
}

internal expect fun createPlatformWorkbenchStorage(): WorkbenchStorage
