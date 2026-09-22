package cn.elonzh.hanppie.agent.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** File-ordered Session event log following the same cursor contract used by DTEmpower. */
internal class JsonlSessionEventStore(private val directory: Path) : SessionEventStore {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    init {
        Files.createDirectories(directory)
        // Upgrades require all old application processes to be stopped first.
        withDirectoryLock {
            Files.list(directory).use { paths ->
                paths.filter {
                    validId(it.fileName.toString().removeSuffix(".lock")) &&
                            it.fileName.toString().endsWith(".lock") && Files.isRegularFile(it)
                }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

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

            val bytes = (json.encodeToString(SessionEvent.serializer(), event) + "\n")
                .toByteArray(StandardCharsets.UTF_8)
            FileChannel.open(path(sessionId), CREATE, WRITE, APPEND).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
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
        if (!Files.exists(directory)) emptyList() else Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(SUFFIX) }
                .map { it.fileName.toString().removeSuffix(SUFFIX) }
                .filter(::validId)
                .toList()
        }
    }

    override suspend fun delete(sessionId: String) = withSessionLock(sessionId) {
        Files.deleteIfExists(path(sessionId))
        Files.deleteIfExists(corruptTailPath(sessionId))
        // The directory lock survives session deletion, preserving its inode for all contenders.
        Unit
    }

    private fun readValidatedFile(sessionId: String): SessionEventStream {
        val file = path(sessionId)
        if (!Files.exists(file)) return SessionEventStream(emptyList())
        val bytes = Files.readAllBytes(file)
        if (bytes.isEmpty()) return SessionEventStream(emptyList())
        if (bytes.last() != '\n'.code.toByte()) {
            throw SessionEventCorruptionException("Session event log has an uncommitted tail: $file")
        }
        val events = bytes.toString(StandardCharsets.UTF_8).dropLast(1).split('\n').mapIndexed { index, line ->
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
    private fun discardUncommittedTail(file: Path) {
        if (!Files.exists(file)) return
        val bytes = Files.readAllBytes(file)
        if (bytes.isEmpty() || bytes.last() == '\n'.code.toByte()) return
        val lastNewline = bytes.indexOfLast { byte -> byte == '\n'.code.toByte() }
        val tailStart = lastNewline + 1
        Files.write(corruptTailPath(file.fileName.toString().removeSuffix(SUFFIX)),
            bytes.copyOfRange(tailStart, bytes.size), CREATE, WRITE, APPEND)
        Files.write(corruptTailPath(file.fileName.toString().removeSuffix(SUFFIX)),
            byteArrayOf('\n'.code.toByte()), CREATE, WRITE, APPEND)
        FileChannel.open(file, WRITE).use { channel ->
            channel.truncate(tailStart.toLong())
            channel.force(true)
        }
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
            withDirectoryLock(action)
        }

    private fun <T> withDirectoryLock(action: () -> T): T {
        val lockFile = directory.toRealPath().resolve(LOCK_FILE)
        return PROCESS_LOCKS.computeIfAbsent(lockFile) { ReentrantLock() }.withLock {
            FileChannel.open(lockFile, CREATE, WRITE)
                .use { channel -> channel.lock().use { action() } }
        }
    }

    private fun path(sessionId: String) = directory.resolve(sessionId + SUFFIX)
    private fun corruptTailPath(sessionId: String) = directory.resolve(sessionId + CORRUPT_TAIL_SUFFIX)
    private fun validId(value: String) = ID.matches(value)

    private companion object {
        val PROCESS_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
        val ID = Regex("[0-9a-fA-F-]{36}")
        const val SUFFIX = ".jsonl"
        const val LOCK_FILE = ".sessions.lock"
        const val CORRUPT_TAIL_SUFFIX = ".jsonl.tail.corrupt"
    }
}
