package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.agent.skills.SkillLibrary
import kotlinx.coroutines.Deferred
import cn.elonzh.hanppie.agent.runtime.AgentRuntimeStorage
import cn.elonzh.hanppie.agent.runtime.SessionHistory
import cn.elonzh.hanppie.ui.scripts.ScriptRepository
import cn.elonzh.hanppie.ui.settings.SettingsStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

private val storageLogger = KotlinLogging.logger {}

internal class WorkbenchStorage(
    val settings: SettingsStore,
    val scripts: ScriptRepository,
    val skills: Deferred<SkillLibrary>,
    private val agentRuntime: AgentRuntimeStorage,
    private val scope: CoroutineScope,
) : AutoCloseable {
    val sessions: SessionHistory get() = agentRuntime.sessions

    override fun close() {
        try {
            agentRuntime.close()
        } finally {
            scope.cancel()
        }
        storageLogger.debug { "Workbench storage closed" }
    }

    companion object {
        fun create(): WorkbenchStorage = createPlatformWorkbenchStorage()
    }
}

internal expect fun createPlatformWorkbenchStorage(): WorkbenchStorage
