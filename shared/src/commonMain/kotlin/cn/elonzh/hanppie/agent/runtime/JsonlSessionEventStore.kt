package cn.elonzh.hanppie.agent.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** File-ordered Session event log following the same cursor contract used by DTEmpower. */
internal class JsonlSessionEventStore(private val directory: JournalDirectory) : SessionEventStore {
    private val json = Json { encodeDefaults = true; explicitNulls = false }


    override suspend fun append(sessionId: String, expectedLastEventId: String?, event: SessionEvent) =
        withSessionLock(sessionId) {
            discardUncommittedTail(path(sessionId))
            val current = readValidatedFile(sessionId)
            validateEvent(event)

            current.events.firstOrNull { stored -> stored.eventId == event.eventId }?.let { stored ->
                if (stored == event) return@withSessionLock
                throw SessionEventIdConflictException(event.eventId)
            }
            if (current.lastEventId != expectedLastEventId) {
                throw SessionCursorConflictException(expectedLastEventId, current.lastEventId)
            }

            val bytes = (json.encodeToString(SessionEvent.serializer(), event) + "\n").encodeToByteArray()
            directory.append(path(sessionId), bytes)
        }

    override suspend fun read(sessionId: String, afterEventId: String?): SessionEventStream =
        withSessionLock(sessionId) {
            discardUncommittedTail(path(sessionId))
            val stream = readValidatedFile(sessionId)
            if (afterEventId == null) return@withSessionLock stream
            val cursorIndex = stream.events.indexOfFirst { event -> event.eventId == afterEventId }
            if (cursorIndex < 0) throw SessionCursorNotFoundException(afterEventId)
            SessionEventStream(stream.events.drop(cursorIndex + 1))
        }

    override suspend fun sessionIds(): List<String> = withContext(Dispatchers.IO) {
        directory.names().filter { it.endsWith(SUFFIX) }.map { it.removeSuffix(SUFFIX) }.filter(::validId)
    }

    override suspend fun delete(sessionId: String) = withSessionLock(sessionId) {
        directory.delete(path(sessionId))
        directory.delete(corruptTailPath(sessionId))
        // The directory lock survives session deletion, preserving its inode for all contenders.
        Unit
    }

    private fun readValidatedFile(sessionId: String): SessionEventStream {
        val file = path(sessionId)
        val bytes = directory.read(file) ?: return SessionEventStream(emptyList())
        if (bytes.isEmpty()) return SessionEventStream(emptyList())
        if (bytes.last() != '\n'.code.toByte()) {
            throw SessionEventCorruptionException("Session event log has an uncommitted tail: $file")
        }
        val events = bytes.decodeToString().dropLast(1).split('\n').mapIndexed { index, line ->
            if (line.isBlank()) {
                throw SessionEventCorruptionException("Session event log contains a blank line at ${index + 1}: $file")
            }
            try {
                json.decodeFromString(SessionEvent.serializer(), line)
            } catch (error: Exception) {
                throw SessionEventCorruptionException("Cannot read Session event at line ${index + 1}: $file", error)
            }
        }
        val eventIds = mutableSetOf<String>()
        events.forEach { event ->
            validateEvent(event)
            if (!eventIds.add(event.eventId)) {
                throw SessionEventCorruptionException("Duplicate Session event ID: ${event.eventId}")
            }
        }
        return SessionEventStream(events)
    }

    /** A line is committed only when its newline is durable; any other tail is quarantined and truncated. */
    private fun discardUncommittedTail(file: String) {
        val bytes = directory.read(file) ?: return
        if (bytes.isEmpty() || bytes.last() == '\n'.code.toByte()) return
        val tailStart = bytes.indexOfLast { it == '\n'.code.toByte() } + 1
        directory.append(corruptTailPath(file.removeSuffix(SUFFIX)),
            bytes.copyOfRange(tailStart, bytes.size) + byteArrayOf('\n'.code.toByte()))
        directory.truncate(file, tailStart.toLong())
    }

    private fun validateEvent(event: SessionEvent) {
        if (event.eventId.isBlank()) throw SessionEventCorruptionException("Session event ID must not be blank")
        if (event.timestamp < 0) throw SessionEventCorruptionException("Session event timestamp must not be negative")
        if (event is AgentRunEvent) {
            if (event.runId.isBlank()) throw SessionEventCorruptionException("Session event runId must not be blank")
            if (event.executionInfo.partName.isBlank()) {
                throw SessionEventCorruptionException("Session event executionInfo.partName must not be blank")
            }
        }
    }

    private suspend fun <T> withSessionLock(sessionId: String, action: () -> T): T =
        withContext(Dispatchers.IO) {
            require(validId(sessionId)) { "Invalid session id" }
            directory.locked(action)
        }

    private fun path(sessionId: String) = sessionId + SUFFIX
    private fun corruptTailPath(sessionId: String) = sessionId + CORRUPT_TAIL_SUFFIX
    private fun validId(value: String) = ID.matches(value)

    private companion object {
        val ID = Regex("[0-9a-fA-F-]{36}")
        const val SUFFIX = ".jsonl"
        const val CORRUPT_TAIL_SUFFIX = ".jsonl.tail.corrupt"
    }
}
