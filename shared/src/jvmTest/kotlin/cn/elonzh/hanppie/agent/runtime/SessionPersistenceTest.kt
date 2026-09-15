package cn.elonzh.hanppie.agent.runtime

import ai.koog.agents.core.agent.execution.AgentExecutionInfo
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import androidx.room3.Room
import cn.elonzh.hanppie.ui.scripts.HanppieDatabase
import cn.elonzh.hanppie.ui.scripts.RoomScriptRepository
import cn.elonzh.hanppie.ui.scripts.StoredScript
import cn.elonzh.hanppie.ui.scripts.buildHanppieDatabase
import cn.elonzh.hanppie.ui.settings.ModelSettings
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SessionPersistenceTest {
    private val executionInfo = AgentExecutionInfo(null, "test")
    private val model = ModelSettings(apiKey = "unused").llModel

    @Test
    fun uncommittedJsonlTailIsQuarantinedBeforeNextAppend() = runBlocking {
        val directory = Files.createTempDirectory("hanppie-session-tail-")
        val sessionId = "22222222-2222-2222-2222-222222222222"
        try {
            val first = SessionCreatedEvent("event-1", 1, "一")
            JsonlSessionEventStore(directory).append(sessionId, null, first)
            Files.writeString(directory.resolve("$sessionId.jsonl"), "{\"eventType\":", APPEND)

            val reopened = JsonlSessionEventStore(directory)
            assertEquals(listOf(first), reopened.read(sessionId).events)
            val second = SessionRenamedEvent("event-2", 2, "二")
            reopened.append(sessionId, first.eventId, second)

            assertEquals(listOf("event-1", "event-2"), reopened.read(sessionId).events.map { it.eventId })
            assertEquals("{\"eventType\":\n",
                Files.readString(directory.resolve("$sessionId.jsonl.tail.corrupt")))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun malformedCommittedJsonlLineIsReported(): Unit = runBlocking {
        val directory = Files.createTempDirectory("hanppie-session-corrupt-")
        val sessionId = "55555555-5555-5555-5555-555555555555"
        try {
            val store = JsonlSessionEventStore(directory)
            store.append(sessionId, null, SessionCreatedEvent("event-1", 1, "一"))
            Files.writeString(directory.resolve("$sessionId.jsonl"), "{not-json}\n", APPEND)

            assertFailsWith<SessionEventCorruptionException> { store.read(sessionId) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun appendRequiresTheExactLastEventCursor() = runBlocking {
        val directory = Files.createTempDirectory("hanppie-session-cursor-")
        val sessionId = "99999999-9999-9999-9999-999999999999"
        try {
            val store = JsonlSessionEventStore(directory)
            store.append(sessionId, null, SessionCreatedEvent("event-1", 1, "一"))

            val error = assertFailsWith<SessionCursorConflictException> {
                store.append(sessionId, null, SessionRenamedEvent("event-2", 2, "二"))
            }
            assertEquals(null, error.expectedLastEventId)
            assertEquals("event-1", error.actualLastEventId)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun duplicateEventIdIsIdempotentOnlyForIdenticalContent(): Unit = runBlocking {
        val directory = Files.createTempDirectory("hanppie-session-id-")
        val sessionId = "88888888-8888-8888-8888-888888888888"
        try {
            val store = JsonlSessionEventStore(directory)
            val event = SessionCreatedEvent("event-1", 1, "一")
            store.append(sessionId, null, event)
            store.append(sessionId, "stale-cursor-is-ignored-for-idempotency", event)
            assertEquals(1, store.read(sessionId).events.size)

            assertFailsWith<SessionEventIdConflictException> {
                store.append(sessionId, event.eventId, event.copy(title = "二"))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun runtimeDatabaseAndApplicationDatabaseHaveIndependentLifecycles() = runBlocking {
        val directory = Files.createTempDirectory("hanppie-storage-boundary-")
        val applicationPath = directory.resolve("hanppie.db")
        val runtimePath = directory.resolve("agent-runtime.db")
        var applicationDatabase: HanppieDatabase? = null
        var runtimeStorage: AgentRuntimeStorage? = null
        try {
            applicationDatabase = buildHanppieDatabase(
                Room.databaseBuilder<HanppieDatabase>(applicationPath.toString()),
            )
            RoomScriptRepository(applicationDatabase.scriptDao()).insert(
                StoredScript("script", "保留脚本", "def start(): pass", 1, 1),
            )
            runtimeStorage = openAgentRuntimeStorage(runtimePath, directory.resolve("events"))
            val session = runtimeStorage.sessions.create("独立会话")

            assertTrue(Files.exists(applicationPath))
            assertTrue(Files.exists(runtimePath))
            assertNotEquals(applicationPath, runtimePath)
            assertEquals("保留脚本", applicationDatabase.scriptDao().getAll().single().name)
            assertEquals(session.id, runtimeStorage.sessions.list().single().id)
        } finally {
            runtimeStorage?.close()
            applicationDatabase?.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun jsonlIsSourceOfTruthAndSqliteProjectionCanBeRebuilt() = runBlocking {
        val directory = Files.createTempDirectory("hanppie-session-")
        var database: AgentRuntimeDatabase? = null
        try {
            database = openRuntime(directory.resolve("agent-runtime.db"))
            val eventStore = JsonlSessionEventStore(directory.resolve("events"))
            val repository = SessionRepository(eventStore, database.sessionProjectionDao(), database)
            val session = repository.create("测试会话")
            val runId = "11111111-1111-1111-1111-111111111111"
            val user = prompt("test") { user("检查状态") }.messages.single() as Message.User
            val assistant = prompt("test") { assistant("目前未连接") }.messages.single()
            repository.append(session.id, AgentStartingEvent(
                "event-start", runId, 2, executionInfo, user,
                ModelSettings(apiKey = "must-not-be-recorded").llModel,
            ))
            repository.append(session.id, MessageEvent("event-message", runId, 3, executionInfo, assistant))
            val toolResult = prompt("test-tool-result") {
                user {
                    toolResult(MessagePart.Tool.Result(
                        "call-1",
                        "execute_lab_python",
                        "{\"status\":\"START_COMMAND_SENT\",\"runId\":\"run-1\"}",
                    ))
                }
            }.messages.single()
            repository.append(session.id, MessageEvent("event-tool-result", runId, 4, executionInfo, toolResult))
            repository.append(session.id, AgentCompletedEvent("event-complete", runId, 5, executionInfo))

            assertEquals(listOf(user, assistant, toolResult), repository.messages(session.id))
            assertEquals("目前未连接", repository.list().single().lastMessagePreview)
            val jsonl = Files.readString(directory.resolve("events/${session.id}.jsonl"))
            assertTrue(jsonl.contains("AgentStarting"))
            assertTrue(jsonl.contains("Message"))
            assertFalse(jsonl.contains("StreamFrame"))
            assertTrue(jsonl.contains("检查状态"))
            assertFalse(jsonl.contains("must-not-be-recorded"))

            database.sessionProjectionDao().deleteMessages(session.id)
            assertTrue(database.sessionProjectionDao().messages(session.id).isEmpty())
            repository.rebuild(session.id)
            assertEquals(listOf(user, assistant, toolResult), repository.messages(session.id))

            repository.rename(session.id, "重命名")
            assertEquals("重命名", repository.list().single().title)
            val last = eventStore.read(session.id).lastEventId
            assertEquals(last, repository.find(session.id)?.latestEventId)

            database.close()
            database = openRuntime(directory.resolve("agent-runtime.db"))
            val reopened = SessionRepository(eventStore, database.sessionProjectionDao(), database)
            reopened.initialize()
            assertEquals(listOf(user, assistant, toolResult), reopened.messages(session.id))
            assertEquals("目前未连接", reopened.list().single().lastMessagePreview)
        } finally {
            database?.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun unfinishedRunIsMarkedFailedDuringStartupRecovery() = runBlocking {
        val directory = Files.createTempDirectory("hanppie-run-recovery-")
        val database = openRuntime(directory.resolve("agent-runtime.db"))
        try {
            val eventStore = JsonlSessionEventStore(directory.resolve("events"))
            val repository = SessionRepository(eventStore, database.sessionProjectionDao(), database)
            val session = repository.create("恢复")
            val runId = "33333333-3333-3333-3333-333333333333"
            repository.append(session.id, AgentStartingEvent(
                "event-start", runId, 2, executionInfo,
                prompt("test") { user("继续") }.messages.single() as Message.User, model,
            ))

            repository.initialize()

            assertTrue(database.sessionProjectionDao().unfinishedRuns().isEmpty())
            val failed = eventStore.read(session.id).events.filterIsInstance<AgentExecutionFailedEvent>().single()
            assertEquals("ProcessRestart", failed.failure)
            assertTrue(repository.messages(session.id).any { it.textContent() == PROCESS_RESTART_NOTICE })
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun unfinishedSideEffectGetsAKoogToolResultWithoutReexecution() = runBlocking {
        val directory = Files.createTempDirectory("hanppie-tool-recovery-")
        val database = openRuntime(directory.resolve("agent-runtime.db"))
        try {
            val eventStore = JsonlSessionEventStore(directory.resolve("events"))
            val repository = SessionRepository(eventStore, database.sessionProjectionDao(), database)
            val session = repository.create("工具恢复")
            val runId = "66666666-6666-6666-6666-666666666666"
            repository.append(session.id, AgentStartingEvent(
                "event-start", runId, 2, executionInfo,
                prompt("test") { user("执行") }.messages.single() as Message.User, model,
            ))
            repository.append(session.id, ToolCallStartingEvent(
                "event-tool", runId, 3, executionInfo,
                MessagePart.Tool.Call("call-side-effect", "execute_lab_python", "{}"),
            ))

            repository.initialize()

            val result = repository.messages(session.id).flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool.Result>().single()
            assertEquals("call-side-effect", result.id)
            assertEquals("execute_lab_python", toolNameFromUnknownNotice(result.output))
            assertTrue(result.isError)
            assertEquals("ProcessRestart",
                eventStore.read(session.id).events.filterIsInstance<AgentExecutionFailedEvent>().single().failure)
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun pendingApprovalIsSafelyRejectedDuringRestartRecovery() = runBlocking {
        val directory = Files.createTempDirectory("hanppie-approval-recovery-")
        val database = openRuntime(directory.resolve("agent-runtime.db"))
        try {
            val eventStore = JsonlSessionEventStore(directory.resolve("events"))
            val repository = SessionRepository(eventStore, database.sessionProjectionDao(), database)
            val session = repository.create("审批恢复")
            val runId = "77777777-7777-7777-7777-777777777777"
            repository.append(session.id, AgentStartingEvent(
                "event-start", runId, 2, executionInfo,
                prompt("test") { user("执行") }.messages.single() as Message.User, model,
            ))
            repository.append(session.id, ToolApprovalRequestedEvent(
                "event-approval", runId, 3, executionInfo,
                MessagePart.Tool.Call("call-pending", "execute_lab_python", "{\"source\":\"def start(): pass\"}"),
            ))

            repository.initialize()

            val resolved = eventStore.read(session.id).events.filterIsInstance<ToolApprovalResolvedEvent>().single()
            assertEquals("call-pending", resolved.toolCallId)
            assertFalse(resolved.accepted)
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun openRuntime(path: java.nio.file.Path): AgentRuntimeDatabase = buildAgentRuntimeDatabase(
        Room.databaseBuilder<AgentRuntimeDatabase>(name = path.toString()),
    )
}
