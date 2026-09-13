package cn.elonzh.hanppie.agent.runtime

internal data class SessionEventStream(val events: List<SessionEvent>) {
    val lastEventId: String? get() = events.lastOrNull()?.eventId
}

internal interface SessionEventStore {
    suspend fun append(sessionId: String, expectedLastEventId: String?, event: SessionEvent)
    suspend fun read(sessionId: String, afterEventId: String? = null): SessionEventStream
    suspend fun sessionIds(): List<String>
    suspend fun delete(sessionId: String)
}

internal sealed class SessionEventStoreException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class SessionCursorConflictException(
    val expectedLastEventId: String?,
    val actualLastEventId: String?,
) : SessionEventStoreException(
    "Session event cursor conflict: expected=$expectedLastEventId, actual=$actualLastEventId",
)

internal class SessionCursorNotFoundException(eventId: String) :
    SessionEventStoreException("Session event cursor does not exist: $eventId")

internal class SessionEventIdConflictException(eventId: String) :
    SessionEventStoreException("Session event ID has conflicting content: $eventId")

internal class SessionEventCorruptionException(message: String, cause: Throwable? = null) :
    SessionEventStoreException(message, cause)
