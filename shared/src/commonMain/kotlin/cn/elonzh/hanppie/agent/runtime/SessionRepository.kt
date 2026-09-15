@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cn.elonzh.hanppie.agent.runtime

import ai.koog.agents.core.agent.execution.AgentExecutionInfo
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import androidx.room3.RoomDatabase
import androidx.room3.withWriteTransaction
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Appends Session facts first and maintains a disposable runtime-owned read model. */
internal class SessionRepository(
    private val events: SessionEventStore,
    private val dao: SessionProjectionDao,
    private val database: RoomDatabase,
    private val clock: Clock = Clock.System,
) : SessionHistory {
    private val json = Json { encodeDefaults = true }
    private val writer = Mutex()

    override suspend fun initialize() {
        val sourceIds = events.sessionIds().toSet()
        dao.sessionIds().filterNot(sourceIds::contains).forEach { orphanedId ->
            database.withWriteTransaction {
                clearProjection(orphanedId)
                dao.deleteSession(orphanedId)
            }
        }
        sourceIds.forEach { rebuildIfNeeded(it) }
        sourceIds.forEach { rejectUnfinishedApprovals(it) }
        sourceIds.forEach { recoverUnfinishedTools(it) }
        dao.unfinishedRuns().forEach { run ->
            val executionInfo = AgentExecutionInfo(null, "processRestartRecovery")
            append(run.sessionId, MessageEvent(
                eventId = eventId(),
                runId = run.id,
                timestamp = now(),
                executionInfo = executionInfo,
                message = prompt("process-restart-recovery") { system(PROCESS_RESTART_NOTICE) }.messages.single(),
            ))
            append(run.sessionId, AgentExecutionFailedEvent(
                eventId = eventId(),
                runId = run.id,
                timestamp = now(),
                executionInfo = executionInfo,
                failure = "ProcessRestart",
            ))
        }
    }

    override suspend fun create(title: String): AgentSession {
        val id = Uuid.random().toString()
        append(id, SessionCreatedEvent(eventId(), now(), title.normalizedTitle()))
        return requireNotNull(dao.find(id)).toAgentSession()
    }

    override suspend fun list(): List<AgentSession> = dao.sessions().map(SessionProjectionEntity::toAgentSession)
    override suspend fun find(id: String): AgentSession? = dao.find(id)?.toAgentSession()
    override suspend fun messages(sessionId: String): List<Message> = dao.messages(sessionId).map {
        json.decodeFromString<Message>(it.messageJson)
    }

    override suspend fun rename(sessionId: String, title: String) {
        append(sessionId, SessionRenamedEvent(eventId(), now(), title.normalizedTitle()))
    }

    override suspend fun delete(sessionId: String) {
        writer.withLock {
            events.delete(sessionId)
            database.withWriteTransaction {
                clearProjection(sessionId)
                dao.deleteSession(sessionId)
            }
        }
    }

    override suspend fun append(sessionId: String, event: SessionEvent): SessionEvent = writer.withLock {
        val stream = events.read(sessionId)
        events.append(sessionId, stream.lastEventId, event)
        database.withWriteTransaction { project(sessionId, event, stream.events.size.toLong() + 1L) }
        event
    }

    suspend fun rebuild(sessionId: String) {
        writer.withLock { rebuildUnlocked(sessionId) }
    }

    private suspend fun rebuildUnlocked(sessionId: String) {
        val source = events.read(sessionId).events
        database.withWriteTransaction {
            clearProjection(sessionId)
            dao.deleteSession(sessionId)
            source.forEachIndexed { index, event -> project(sessionId, event, index.toLong() + 1L) }
        }
    }

    private suspend fun rebuildIfNeeded(sessionId: String) {
        val source = events.read(sessionId)
        if (source.events.isEmpty()) return
        val checkpoint = dao.checkpoint(sessionId)
        if (checkpoint?.lastEventId != source.lastEventId || checkpoint?.eventCount != source.events.size.toLong()) {
            writer.withLock { rebuildUnlocked(sessionId) }
        }
    }

    private suspend fun recoverUnfinishedTools(sessionId: String) {
        val source = events.read(sessionId).events
        val finished = source.filterIsInstance<MessageEvent>().flatMap { event ->
            event.message.parts.filterIsInstance<MessagePart.Tool.Result>().mapNotNull { it.id }
        }.toSet()
        source.filterIsInstance<ToolCallStartingEvent>()
            .filter { event -> event.toolCall.id !in finished }
            .forEach { event ->
                val toolCallId = requireNotNull(event.toolCall.id)
                val notice = toolOutcomeUnknownNotice(event.toolCall.tool)
                val result = prompt("tool-outcome-recovery") {
                    user { toolResult(MessagePart.Tool.Result(toolCallId, event.toolCall.tool, notice, isError = true)) }
                }.messages.single()
                append(sessionId, MessageEvent(eventId(), event.runId, now(), event.executionInfo, result))
                append(sessionId, MessageEvent(
                    eventId(), event.runId, now(), event.executionInfo,
                    prompt("tool-outcome-recovery-notice") { system(notice) }.messages.single(),
                ))
            }
    }

    private suspend fun rejectUnfinishedApprovals(sessionId: String) {
        val source = events.read(sessionId).events
        val resolved = source.filterIsInstance<ToolApprovalResolvedEvent>().map { it.toolCallId }.toSet()
        val started = source.filterIsInstance<ToolCallStartingEvent>().mapNotNull { it.toolCall.id }.toSet()
        source.filterIsInstance<ToolApprovalRequestedEvent>()
            .filter { event -> event.toolCall.id !in resolved && event.toolCall.id !in started }
            .forEach { event ->
                append(sessionId, ToolApprovalResolvedEvent(
                    eventId(), event.runId, now(), event.executionInfo,
                    requireNotNull(event.toolCall.id), accepted = false,
                ))
            }
    }

    private suspend fun clearProjection(sessionId: String) {
        dao.deleteMessages(sessionId)
        dao.deleteRuns(sessionId)
        dao.deleteCheckpoint(sessionId)
    }

    private suspend fun project(sessionId: String, event: SessionEvent, position: Long) {
        val current = dao.find(sessionId)
        when (event) {
            is SessionCreatedEvent -> dao.putSession(SessionProjectionEntity(
                sessionId, event.title, event.timestamp, event.timestamp, event.eventId, "",
            ))
            is SessionRenamedEvent -> current?.let {
                dao.putSession(it.copy(title = event.title, updatedAtEpochMillis = event.timestamp,
                    latestEventId = event.eventId))
            }
            is AgentStartingEvent -> {
                dao.putRun(AgentRunProjection(
                    event.runId, sessionId, event.model.provider.id, event.model.id,
                    event.timestamp, null, null, null,
                ))
                putMessage(sessionId, event.eventId, position, event.timestamp, event.message, current)
            }
            is MessageEvent -> putMessage(sessionId, event.eventId, position, event.timestamp, event.message, current)
            is AgentCompletedEvent -> dao.finishRun(event.runId, event.timestamp, "COMPLETED", null)
            is AgentExecutionFailedEvent -> dao.finishRun(event.runId, event.timestamp, "FAILED", event.failure)
            is AgentExecutionCancelledEvent -> dao.finishRun(event.runId, event.timestamp, "CANCELLED", null)
            is ToolApprovalRequestedEvent,
            is ToolApprovalResolvedEvent,
            is ToolCallStartingEvent,
            -> Unit
        }
        if (event !is SessionCreatedEvent) dao.touchSession(sessionId, event.timestamp, event.eventId)
        dao.putCheckpoint(SessionProjectionCheckpoint(sessionId, event.eventId, position))
    }

    private suspend fun putMessage(
        sessionId: String,
        eventId: String,
        position: Long,
        timestamp: Long,
        message: Message,
        current: SessionProjectionEntity?,
    ) {
        val text = projectedText(message)
        dao.putMessage(SessionMessageProjection(
            eventId, sessionId, position, projectedRole(message), text,
            json.encodeToString<Message>(message), timestamp,
        ))
        current?.let {
            dao.putSession(it.copy(
                updatedAtEpochMillis = timestamp,
                latestEventId = eventId,
                lastMessagePreview = text.takeIf(String::isNotBlank)
                    ?.takeUnless(::isRecoveryNotice)?.take(160) ?: it.lastMessagePreview,
            ))
        }
    }

    private fun projectedRole(message: Message): String = when {
        message.parts.any { it is MessagePart.Tool.Result } -> "TOOL"
        message.parts.any { it is MessagePart.Tool.Call } -> "TOOL_CALL"
        else -> message.role.name.uppercase()
    }

    private fun projectedText(message: Message): String = message.textContent().trim()

    private fun String.normalizedTitle() = trim().replace(Regex("\\s+"), " ").take(80).ifBlank { "新对话" }
    private fun isRecoveryNotice(text: String) =
        text == RUN_INTERRUPTED_NOTICE || text == PROCESS_RESTART_NOTICE || toolNameFromUnknownNotice(text) != null
    private fun eventId() = Uuid.random().toString()
    private fun now() = clock.now().toEpochMilliseconds()
}

internal interface SessionHistory {
    suspend fun initialize()
    suspend fun create(title: String = "新对话"): AgentSession
    suspend fun list(): List<AgentSession>
    suspend fun find(id: String): AgentSession?
    suspend fun messages(sessionId: String): List<Message>
    suspend fun rename(sessionId: String, title: String)
    suspend fun delete(sessionId: String)
    suspend fun append(sessionId: String, event: SessionEvent): SessionEvent
}
