package cn.elonzh.hanppie.ui.chat

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
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
    private fun agent(
        status: () -> String,
        execute: suspend (String) -> String,
        stopRobot: suspend () -> String,
        executor: PromptExecutor,
    ) = ChatAgent(
        status = status,
        execute = execute,
        stopRobot = stopRobot,
        createHttpClient = { error("HTTP client must not be created when a test executor is injected") },
        executorOverride = executor,
    )

    @Test fun continuousConversationUsesCompletedPrompt(): Unit = runBlocking {
        val fake = Fake { text("记住了") }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake).use { agent ->
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
        val fake = Fake { if (requests++ == 0) call("execute_lab_python", buildJsonObject { put("source", source) }.toString()) else text("命令已发送") }
        agent({ "已连接" }, { assertEquals(source, it); executions++; "启动命令已发送" }, { "停止命令已发送" }, fake).use { agent ->
            agent.send("打印 test", config)
            withTimeout(5000) { agent.state.first { it.approval != null || !it.running } }
            assertNull(agent.state.value.error)
            assertEquals(source, agent.state.value.approval); assertEquals(0, executions)
            agent.approve(true); agent.finished()
            assertEquals(1, executions)
            assertTrue(fake.prompts.last().messages.toString().contains("启动命令已发送"))
        }
    }
    @Test fun cancellationNeverExecutesAndKeepsInterruptedContext() = runBlocking {
        var requests = 0; var executions = 0
        val fake = Fake { if (requests++ == 0) call("execute_lab_python", "{\"source\":\"def start(): pass\"}") else text("好的") }
        agent({ "已连接" }, { executions++; "sent" }, { "stop" }, fake).use { agent ->
            agent.send("准备脚本", config)
            withTimeout(5000) { agent.state.first { it.approval != null || !it.running } }
            agent.cancelAndJoin(); assertEquals(0, executions); assertNull(agent.state.value.approval)
            agent.send("继续聊", config); agent.finished()
            assertTrue(fake.prompts.last().messages.toString().contains("上一轮中断"))
        }
    }
    @Test fun denialIsReturnedToModelWithoutExecuting() = runBlocking {
        var requests = 0
        val fake = Fake { if (requests++ == 0) call("execute_lab_python", "{\"source\":\"def start(): pass\"}") else text("不执行") }
        agent({ "已连接" }, { error("Must not execute") }, { error("Must not stop") }, fake).use { agent ->
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
            agent.send("停", config); assertNotNull(agent.finished().error)
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
            agent.send("hello", config)
            val error = requireNotNull(agent.finished().error)
            assertTrue(error.contains("HTTP 401"))
            assertFalse(error.contains("provider-secret"))
            assertFalse(error.contains("request-private-content"))
        }
    }
}
