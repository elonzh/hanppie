package cn.elonzh.hanppie.ui.chat

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import cn.elonzh.hanppie.agent.runtime.*
import cn.elonzh.hanppie.ui.settings.ModelSettings
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class ChatAgentTest {
    private val config = ModelSettings(apiKey = "test-not-a-secret")
    private class Fake(private val respond: suspend (Prompt) -> List<StreamFrame>) : PromptExecutor() {
        val prompts = mutableListOf<Prompt>()
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = flow {
            prompts += prompt
            respond(prompt).forEach { emit(it) }
        }
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("Must stream")
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Unused")
        override fun close() {}
    }
    private fun text(value: String) = listOf(StreamFrame.TextDelta(value), StreamFrame.TextComplete(value), StreamFrame.End("stop"))
    private fun call(name: String, args: String = "{}") = listOf(StreamFrame.ToolCallComplete("call-1", name, args), StreamFrame.End("tool_calls"))
    private suspend fun ChatAgent.finished() = withTimeout(5000) { state.first { !it.running } }
    private suspend fun ChatAgent.ready() = withTimeout(5000) { state.first { it.ready } }
    private fun agent(
        status: () -> String,
        execute: suspend (String) -> String,
        stopRobot: suspend () -> String,
        executor: PromptExecutor,
        sessions: TestSessionHistory = TestSessionHistory(),
    ) = ChatAgent(
        status = status,
        execute = execute,
        stopRobot = stopRobot,
        createHttpClient = { error("HTTP client must not be created when a test executor is injected") },
        sessions = sessions,
        executorOverride = executor,
    )

    @Test fun continuousConversationUsesCompletedPrompt(): Unit = runBlocking {
        val fake = Fake { text("记住了") }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake).use { agent ->
            agent.ready()
            agent.send("记住口令：蓝色", config); assertNull(agent.finished().error)
            agent.send("口令是什么？", config); assertNull(agent.finished().error)
            assertTrue(fake.prompts.last().messages.toString().contains("蓝色"))
            assertEquals(1, fake.prompts.last().messages.filterIsInstance<Message.System>().size)
            assertNotNull(agent.state.value.firstTokenMs)
        }
    }
    @Test fun toolResultsReturnToModelAndWaitForExactScriptApproval() = runBlocking {
        var requests = 0; var executions = 0
        val source = "def start():\n    print('test')"
        val history = TestSessionHistory()
        val fake = Fake { if (requests++ == 0) call("execute_lab_python", buildJsonObject { put("source", source) }.toString()) else text("命令已发送") }
        agent({ "已连接" }, { assertEquals(source, it); executions++; "启动命令已发送" }, { "停止命令已发送" }, fake, history).use { agent ->
            agent.ready()
            agent.send("打印 test", config)
            withTimeout(5000) { agent.state.first { it.approval != null || !it.running } }
            assertNull(agent.state.value.error)
            assertEquals(source, agent.state.value.approval); assertEquals(0, executions)
            agent.approve(true); agent.finished()
            assertEquals(1, executions)
            assertTrue(fake.prompts.last().messages.toString().contains("启动命令已发送"))
            val approval = history.events.filterIsInstance<ToolApprovalRequestedEvent>().single()
            val started = history.events.filterIsInstance<ToolCallStartingEvent>().single()
            val result = history.events.filterIsInstance<MessageEvent>()
                .flatMap { it.message.parts }.filterIsInstance<MessagePart.Tool.Result>().single()
            assertEquals("call-1", approval.toolCall.id)
            assertEquals(approval.toolCall.id, started.toolCall.id)
            assertEquals(started.toolCall.id, result.id)
        }
    }

    @Test fun cancelDuringSideEffectRecordsTheExactToolResultAsUnknown() = runBlocking {
        val history = TestSessionHistory()
        val fake = Fake { call("stop_lab") }
        agent({ "已连接" }, { error("No script") }, { awaitCancellation() }, fake, history).use { agent ->
            agent.ready()
            agent.send("停止", config)
            withTimeout(5000) {
                while (history.events.none { it is ToolCallStartingEvent }) yield()
            }
            agent.cancelAndJoin()

            val result = history.events.filterIsInstance<MessageEvent>()
                .flatMap { it.message.parts }.filterIsInstance<MessagePart.Tool.Result>().single()
            assertEquals("call-1", result.id)
            assertEquals("stop_lab", result.tool)
            assertEquals("stop_lab", toolNameFromUnknownNotice(result.output))
        }
    }

    @Test fun closeCallbackRunsAfterActiveToolGetsADurableTerminalEvent() = runBlocking {
        val history = TestSessionHistory()
        val fake = Fake { call("stop_lab") }
        val agent = agent({ "已连接" }, { error("No script") }, { awaitCancellation() }, fake, history)
        try {
            agent.ready()
            agent.send("停止", config)
            withTimeout(5000) {
                while (history.events.none { it is ToolCallStartingEvent }) yield()
            }
            val settled = CompletableDeferred<Unit>()
            agent.closeWhenSettled { settled.complete(Unit) }
            withTimeout(5000) { settled.await() }

            assertTrue(history.events.filterIsInstance<MessageEvent>()
                .flatMap { it.message.parts }.filterIsInstance<MessagePart.Tool.Result>()
                .any { toolNameFromUnknownNotice(it.output) == "stop_lab" })
            assertTrue(history.events.last() is AgentExecutionCancelledEvent)
        } finally {
            agent.close()
        }
    }
    @Test fun cancellationNeverExecutesAndDoesNotReplayASyntheticAssistantMessage() = runBlocking {
        var requests = 0; var executions = 0
        val history = TestSessionHistory()
        val fake = Fake { if (requests++ == 0) call("execute_lab_python", "{\"source\":\"def start(): pass\"}") else text("好的") }
        agent({ "已连接" }, { executions++; "sent" }, { "stop" }, fake, history).use { agent ->
            agent.ready()
            agent.send("准备脚本", config)
            withTimeout(5000) { agent.state.first { it.approval != null || !it.running } }
            agent.cancelAndJoin(); assertEquals(0, executions); assertNull(agent.state.value.approval)
            agent.send("继续聊", config); agent.finished()
            assertFalse(fake.prompts.last().messages.toString().contains("上一轮中断"))
            assertTrue(fake.prompts.last().messages.filterNot { it is Message.System }
                .flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Call>().isEmpty())
            assertTrue(history.events.filterIsInstance<MessageEvent>()
                .any { it.message is Message.System && it.message.textContent() == RUN_INTERRUPTED_NOTICE })
        }
    }
    @Test fun denialIsReturnedToModelWithoutExecuting() = runBlocking {
        var requests = 0
        val fake = Fake { if (requests++ == 0) call("execute_lab_python", "{\"source\":\"def start(): pass\"}") else text("不执行") }
        agent({ "已连接" }, { error("Must not execute") }, { error("Must not stop") }, fake).use { agent ->
            agent.ready()
            agent.send("生成脚本", config)
            withTimeout(5000) { agent.state.first { it.approval != null || !it.running } }
            agent.approve(false); agent.finished()
            assertNull(agent.state.value.error)
            assertTrue(fake.prompts.last().messages.toString().contains("用户拒绝执行"))
        }
    }
    @Test fun truncatedToolStreamIsNeverExecuted(): Unit = runBlocking {
        val fake = Fake { listOf(StreamFrame.ToolCallComplete("c", "stop_lab", "{}"), StreamFrame.End("length")) }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake).use { agent ->
            agent.ready()
            agent.send("停", config); assertNotNull(agent.finished().error)
        }
    }
    @Test fun graphStopsBeforeNinthModelRequest(): Unit = runBlocking {
        var requests = 0
        var statusReads = 0
        val fake = Fake {
            requests++
            listOf(
                StreamFrame.ToolCallComplete("call-$requests", "robot_status", "{}"),
                StreamFrame.End("tool_calls"),
            )
        }
        agent({ statusReads++; "已连接" }, { error("No script") }, { error("No stop") }, fake).use { agent ->
            agent.ready()
            agent.send("持续读取", config)

            assertNotNull(agent.finished().error)
            assertEquals(8, requests)
            assertEquals(8, statusReads)
        }
    }
    @Test fun secretAndInvalidUrlNeverLeak() {
        assertFalse(config.toString().contains(config.apiKey))
        assertFails { config.copy(endpoint = "http://example.com").validate() }
        assertFails { config.copy(endpoint = "https://secret@example.com").validate() }
        assertFails { config.copy(endpoint = "https://example.com?debug=true").validate() }
        assertFails { config.copy(endpoint = "https://example.com/#fragment").validate() }
    }
    @Test fun httpStatusIsUsefulWithoutLeakingProviderBody(): Unit = runBlocking {
        val fake = Fake { throw ai.koog.http.client.KoogHttpClientException(statusCode = 401,
            errorBody = "Authorization: Bearer provider-secret", message = "request-private-content") }
        agent({ "disconnected" }, { error("Must not execute") }, { error("Must not stop") }, fake).use { agent ->
            agent.ready()
            agent.send("hello", config)
            val error = requireNotNull(agent.finished().error)
            assertTrue(error.contains("HTTP 401"))
            assertFalse(error.contains("provider-secret"))
            assertFalse(error.contains("request-private-content"))
        }
    }

    @Test fun completedRunPersistsKoogMessagesButNotTransportFrames() = runBlocking {
        val history = TestSessionHistory()
        val fake = Fake { text("完成") }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake, history).use { agent ->
            agent.ready()
            agent.send("你好", config)
            assertNull(agent.finished().error)
            assertTrue(history.events.first() is AgentStartingEvent)
            assertEquals(1, history.events.filterIsInstance<MessageEvent>().size)
            assertEquals(3, history.events.size)
            assertTrue(history.events.last() is AgentCompletedEvent)
        }
    }

    @Test fun sessionManagementKeepsActiveAndArchivedListsConsistent() = runBlocking {
        val fake = Fake { text("unused") }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake).use { agent ->
            val first = agent.ready().sessionId
            agent.newSession()
            val second = withTimeout(5000) { agent.state.first { it.sessionId != first }.sessionId }
            assertNotNull(second)
            agent.renameSession(second, "巡检")
            withTimeout(5000) { agent.state.first { state -> state.sessions.any { it.id == second && it.title == "巡检" } } }
            agent.archiveSession(second)
            withTimeout(5000) { agent.state.first { state -> state.archivedSessions.any { it.id == second } } }
            agent.restoreSession(second)
            withTimeout(5000) { agent.state.first { state -> state.sessions.any { it.id == second } } }
            agent.deleteSession(second)
            withTimeout(5000) { agent.state.first { state -> (state.sessions + state.archivedSessions).none { it.id == second } } }
        }
        Unit
    }

    @Test fun draftsAreScopedToSessionAndAcceptedTextClearsAfterPersistence() = runBlocking {
        val fake = Fake { text("完成") }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake).use { agent ->
            val first = requireNotNull(agent.ready().sessionId)
            agent.updateDraft("第一段草稿")
            agent.newSession()
            val second = requireNotNull(withTimeout(5000) {
                agent.state.first { it.sessionId != first }.sessionId
            })
            assertEquals("", agent.state.value.draft)
            agent.updateDraft("第二段草稿")
            agent.openSession(first)
            withTimeout(5000) { agent.state.first { it.sessionId == first } }
            assertEquals("第一段草稿", agent.state.value.draft)

            agent.send(agent.state.value.draft, config)
            agent.finished()
            assertEquals("", agent.state.value.draft)
            agent.openSession(second)
            withTimeout(5000) { agent.state.first { it.sessionId == second } }
            assertEquals("第二段草稿", agent.state.value.draft)
        }
    }
}
