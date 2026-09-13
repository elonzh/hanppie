@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cn.elonzh.hanppie.agent.runtime

import ai.koog.prompt.message.Message
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class TestSessionHistory : SessionHistory {
    private val entries = linkedMapOf<String, AgentSession>()
    private val storedMessages = mutableMapOf<String, MutableList<Message>>()
    private val eventState = MutableStateFlow<List<SessionEvent>>(emptyList())
    val events: List<SessionEvent> get() = eventState.value

    override suspend fun initialize() = Unit

    override suspend fun create(title: String): AgentSession {
        val now = Clock.System.now().toEpochMilliseconds()
        val session = AgentSession(Uuid.random().toString(), title, now, now, null, "")
        entries[session.id] = session
        storedMessages[session.id] = mutableListOf()
        return session
    }

    override suspend fun list() = entries.values.sortedByDescending { it.updatedAtEpochMillis }
    override suspend fun find(id: String) = entries[id]
    override suspend fun messages(sessionId: String): List<Message> = storedMessages[sessionId].orEmpty().toList()

    override suspend fun rename(sessionId: String, title: String) {
        entries[sessionId]?.let { entries[sessionId] = it.copy(title = title) }
    }

    override suspend fun delete(sessionId: String) {
        entries.remove(sessionId)
        storedMessages.remove(sessionId)
    }

    override suspend fun append(sessionId: String, event: SessionEvent): SessionEvent {
        eventState.update { it + event }
        val message = when (event) {
            is AgentStartingEvent -> event.message
            is MessageEvent -> event.message
            else -> null
        }
        message?.let { storedMessages.getOrPut(sessionId) { mutableListOf() } += it }
        entries[sessionId]?.let { session ->
            entries[sessionId] = session.copy(
                updatedAtEpochMillis = event.timestamp,
                latestEventId = event.eventId,
                lastMessagePreview = message?.textContent()?.trim()?.take(160) ?: session.lastMessagePreview,
            )
        }
        return event
    }
}
