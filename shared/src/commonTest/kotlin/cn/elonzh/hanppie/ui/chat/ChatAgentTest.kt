package cn.elonzh.hanppie.ui.chat

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import cn.elonzh.hanppie.agent.runtime.*
import cn.elonzh.hanppie.agent.tools.*
import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.agent_model_request_limit_reached
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.settings.ModelSettings
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class ChatAgentTest {
    private val config = ModelSettings(apiKey = "test-not-a-secret")
    private class Fake(
        private val respond: suspend (Prompt) -> List<StreamFrame>,
    ) : PromptExecutor() {
        val prompts = mutableListOf<Prompt>()
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = flow {
            prompts += prompt
            respond(prompt).forEach { emit(it) }
        }
        // Only streaming is used now: no request exists for anything but the conversation itself.
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
            error("Must stream")
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Unused")
        override fun close() {}
    }
    private fun text(value: String) = listOf(StreamFrame.TextDelta(value), StreamFrame.TextComplete(value), StreamFrame.End("stop"))
    private fun call(name: String, args: String = "{}", id: String = "call-1") =
        listOf(StreamFrame.ToolCallComplete(id, name, args), StreamFrame.End("tool_calls"))
    private suspend fun ChatAgent.finished() = withTimeout(5000) { state.first { !it.running } }
    private suspend fun ChatAgent.ready() = withTimeout(5000) { state.first { it.ready } }
    private fun testTools(
        status: suspend () -> RobotStatusTool.Result = { testStatus("未连接") },
        labApiReference: suspend (String) -> LabApiReferenceTool.Result = { testReference(it) },
        listLabScripts: suspend () -> ListLabScriptsTool.Result = { ListLabScriptsTool.Result(emptyList()) },
        readLabScript: suspend (String) -> ReadLabScriptTool.Result = {
            ReadLabScriptTool.Result(it, "script:$it", 1, 1)
        },
        saveLabScript: suspend (String?, String, String) -> SaveLabScriptTool.Result = { original, name, source ->
            SaveLabScriptTool.Result(name, original == null, source.length, 1)
        },
        deleteLabScript: suspend (String) -> DeleteLabScriptTool.Result = {
            DeleteLabScriptTool.Result(it, DeleteLabScriptTool.Status.DELETED)
        },
        execute: suspend (String) -> ExecuteLabPythonTool.Result = {
            ExecuteLabPythonTool.Result(ExecuteLabPythonTool.Status.START_COMMAND_SENT, "run-1")
        },
        stopRobot: suspend () -> StopLabTool.Result = {
            StopLabTool.Result(StopLabTool.Status.STOP_COMMAND_SENT)
        },
    ) = ToolRegistry {
        tool(RobotStatusTool(status))
        tool(LabApiReferenceTool(labApiReference))
        tool(ListLabScriptsTool(listLabScripts))
        tool(ReadLabScriptTool(readLabScript))
        tool(SaveLabScriptTool(saveLabScript))
        tool(DeleteLabScriptTool(deleteLabScript))
        tool(ExecuteLabPythonTool(execute))
        tool(StopLabTool(stopRobot))
    }

    private fun agent(
        status: () -> String,
        execute: suspend (String) -> String,
        stopRobot: suspend () -> String,
        executor: PromptExecutor,
        sessions: SessionHistory = TestSessionHistory(),
        operationTimeoutMillis: Long = 120_000,
    ) = ChatAgent(
        toolRegistry = testTools(
            status = { testStatus(status()) },
            execute = { ExecuteLabPythonTool.Result(ExecuteLabPythonTool.Status.START_COMMAND_SENT, execute(it)) },
            stopRobot = {
                stopRobot()
                StopLabTool.Result(StopLabTool.Status.STOP_COMMAND_SENT)
            },
        ),
        createHttpClient = { error("HTTP client must not be created when a test executor is injected") },
        sessions = sessions,
        executorOverride = executor,
        operationTimeoutMillis = operationTimeoutMillis,
    )

    private fun testStatus(value: String) = RobotStatusTool.Result(
        connected = value == "已连接",
        script = RobotStatusTool.ScriptRun(phase = ScriptRunPhase.IDLE, recentMessages = listOf(value)),
    )

    private fun testReference(query: String, fact: String = "reference:$query") = LabApiReferenceTool.Result(
        inCatalog = true,
        availableCategories = listOf("test"),
        sections = listOf(LabApiReferenceTool.Section("test", listOf(fact))),
        guidance = "verified",
    )

    private class FailingCreateHistory(
        private val delegate: TestSessionHistory = TestSessionHistory(),
    ) : SessionHistory by delegate {
        override suspend fun create(title: String): AgentSession = error("storage is full")
    }

    @Test fun aSessionCreateFailureLeavesTheComposerUsable() = runBlocking {
        val fake = Fake { text("unused") }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake, FailingCreateHistory()).use { agent ->
            agent.ready()
            agent.send("第一条消息", config)
            val failed = withTimeout(5_000) { agent.state.first { state -> state.error != null } }
            assertFalse(failed.running, "a storage failure must not leave the conversation running")
            assertTrue(failed.ready, "the composer has to stay usable after a storage failure")
            assertNull(failed.sessionId)
        }
    }

    /** The title a conversation carries: its first message, and nothing else. */
    @Test fun theTitleIsTheFirstMessageAndStaysThatWay(): Unit = runBlocking {
        val sessions = TestSessionHistory()
        val fake = Fake { text("记住了") }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake, sessions).use { agent ->
            agent.ready()
            agent.send("记住口令：蓝色", config)
            agent.finished()
            delay(200)
            assertEquals("记住口令：蓝色", sessions.list().first().title)
        }
    }

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
    @Test fun sideEffectResultEndsRunAfterExactScriptApproval() = runBlocking {
        var requests = 0; var executions = 0
        val source = "def start():\n    print('test')"
        val history = TestSessionHistory()
        val fake = Fake {
            check(requests++ == 0) { "Side-effect result must not trigger another model request" }
            call("execute_lab_python", buildJsonObject { put("source", source) }.toString())
        }
        agent({ "已连接" }, { assertEquals(source, it); executions++; "启动命令已发送" }, { "停止命令已发送" }, fake, history).use { agent ->
            agent.ready()
            agent.send("打印 test", config)
            withTimeout(5000) { agent.state.first { it.approval != null || !it.running } }
            assertNull(agent.state.value.error)
            assertEquals(source, agent.state.value.approval?.preview); assertEquals(0, executions)
            agent.approve(true); agent.finished()
            assertEquals(1, executions)
            assertEquals(1, requests)
            assertContains(agent.state.value.lastReply, "START_COMMAND_SENT")
            assertContains(agent.state.value.lastReply, "启动命令已发送")
            val approval = history.events.filterIsInstance<ToolApprovalRequestedEvent>().single()
            val started = history.events.filterIsInstance<ToolCallStartingEvent>().single()
            val result = history.events.filterIsInstance<MessageEvent>()
                .flatMap { it.message.parts }.filterIsInstance<MessagePart.Tool.Result>().single()
            assertEquals("call-1", approval.toolCall.id)
            assertEquals(approval.toolCall.id, started.toolCall.id)
            assertEquals(started.toolCall.id, result.id)
            val structuredResult = Json.parseToJsonElement(result.output).jsonObject
            assertEquals("START_COMMAND_SENT", structuredResult.getValue("status").jsonPrimitive.content)
            assertEquals("启动命令已发送", structuredResult.getValue("runId").jsonPrimitive.content)
            assertEquals(1, agent.state.value.lines.count { it.role == ChatRole.TOOL })
            assertFalse(agent.state.value.lines.any {
                it.role == ChatRole.ASSISTANT && it.text == "启动命令已发送"
            })
        }
    }

    @Test fun sideEffectCompletionImmediatelyAcceptsTheNextMessage() = runBlocking {
        var requests = 0
        val source = "def start(): pass"
        val history = TestSessionHistory()
        val fake = Fake {
            if (requests++ == 0) {
                call("execute_lab_python", buildJsonObject { put("source", source) }.toString())
            } else {
                text("可以继续")
            }
        }
        agent({ "已连接" }, { "启动命令已发送" }, { "停止命令已发送" }, fake, history).use { agent ->
            agent.ready()
            assertTrue(agent.send("执行", config))
            withTimeout(5000) { agent.state.first { it.approval != null || !it.running } }
            agent.approve(true)
            assertNull(agent.finished().error)
            assertEquals(ChatPhase.IDLE, agent.state.value.phase)

            assertTrue(agent.send("继续", config))
            assertNull(agent.finished().error)
            assertEquals(2, history.events.filterIsInstance<AgentStartingEvent>().size)
            assertEquals("可以继续", agent.state.value.lastReply)
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
        val history = TestSessionHistory()
        val fake = Fake {
            check(requests++ == 0) { "Rejected side effect must not trigger another model request" }
            call("execute_lab_python", "{\"source\":\"def start(): pass\"}")
        }
        agent({ "已连接" }, { error("Must not execute") }, { error("Must not stop") }, fake, history).use { agent ->
            agent.ready()
            agent.send("生成脚本", config)
            withTimeout(5000) { agent.state.first { it.approval != null || !it.running } }
            agent.approve(false); agent.finished()
            assertNull(agent.state.value.error)
            assertEquals(1, requests)
            assertContains(agent.state.value.lastReply, "USER_REJECTED")
            assertTrue(history.events.none { event -> event is ToolCallStartingEvent })
        }
    }
    @Test fun truncatedToolStreamIsNeverExecuted(): Unit = runBlocking {
        val fake = Fake { listOf(StreamFrame.ToolCallComplete("c", "stop_lab", "{}"), StreamFrame.End("length")) }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake).use { agent ->
            agent.ready()
            agent.send("停", config); assertNotNull(agent.finished().error)
        }
    }

    @Test fun failedRunIsTraceableInternallyAndImmediatelyAcceptsTheNextMessage(): Unit = runBlocking {
        var requests = 0
        val history = TestSessionHistory()
        val fake = Fake {
            if (requests++ == 0) {
                listOf(StreamFrame.ToolCallComplete("c", "stop_lab", "{}"), StreamFrame.End("length"))
            } else {
                text("恢复完成")
            }
        }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake, history).use { agent ->
            agent.ready()
            assertTrue(agent.send("停", config))
            val error = requireNotNull(agent.finished().error)
            val failedRun = history.events.filterIsInstance<AgentStartingEvent>().single().runId
            assertFalse(error.contains(failedRun))
            val failure = history.events.filterIsInstance<AgentExecutionFailedEvent>().single()
            assertEquals(failedRun, failure.runId)
            assertContains(failure.failure, "Model output was truncated")
            assertEquals(ChatPhase.IDLE, agent.state.value.phase)

            assertTrue(agent.send("继续", config))
            assertNull(agent.finished().error)
            assertEquals("恢复完成", agent.state.value.lastReply)
        }
    }

    @Test fun busyRuntimeRejectsACommandWithoutExposingAnInternalReference(): Unit = runBlocking {
        val fake = Fake { text("unused") }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake).use { agent ->
            agent.ready()
            agent.state.value = agent.state.value.copy(phase = ChatPhase.MANAGING)

            assertFalse(agent.send("不会丢失", config))
            val error = requireNotNull(agent.state.value.error)
            assertContains(error, ChatPhase.MANAGING.name)
            assertFalse(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").containsMatchIn(error))
        }
    }
    @Test fun runawayReadOnlyToolLoopFailsAtTheModelRequestLimit(): Unit = runBlocking {
        var requests = 0
        var statusReads = 0
        val fake = Fake {
            requests++
            listOf(
                StreamFrame.ToolCallComplete("call-$requests", "robot_status", "{}"),
                StreamFrame.End("tool_calls"),
            )
        }
        val history = TestSessionHistory()
        agent({ statusReads++; "已连接" }, { error("No script") }, { error("No stop") }, fake, history).use { agent ->
            agent.ready()
            agent.send("持续读取", config)

            val error = requireNotNull(agent.finished().error)
            assertEquals(tr(Res.string.agent_model_request_limit_reached), error)
            assertEquals(8, requests)
            assertEquals(8, statusReads)
            assertTrue(agent.state.value.lastReply.isEmpty())
            assertContains(
                history.events.filterIsInstance<AgentExecutionFailedEvent>().single().failure,
                ModelRequestLimitExceededException::class.simpleName!!,
            )
        }
    }

    @Test fun stopResultEndsRunWithoutAnotherModelRequest(): Unit = runBlocking {
        var requests = 0
        var stops = 0
        val fake = Fake {
            check(requests++ == 0) { "Stop result must not trigger another model request" }
            call("stop_lab")
        }
        agent({ "已连接" }, { error("No script") }, { stops++; "停止命令已发送；未获得机内停止确认。" }, fake).use { agent ->
            agent.ready()
            agent.send("停止", config)

            assertNull(agent.finished().error)
            assertEquals(1, requests)
            assertEquals(1, stops)
            assertContains(agent.state.value.lastReply, "STOP_COMMAND_SENT")
            assertFalse(agent.state.value.lastReply.contains("robotStopConfirmed"))
        }
    }

    @Test fun sendingAtTheVisibleLineLimitStillAdvancesTheUserMessageRevision(): Unit = runBlocking {
        val history = TestSessionHistory()
        val session = history.create("满记录")
        repeat(250) { index ->
            val message = if (index % 2 == 0) {
                prompt("history-$index") { user("用户历史 $index") }.messages.single()
            } else {
                prompt("history-$index") { assistant("回复历史 $index") }.messages.single()
            }
            history.append(session.id, MessageEvent(
                eventId = "history-event-$index",
                runId = "history-run",
                timestamp = index.toLong(),
                executionInfo = ai.koog.agents.core.agent.execution.AgentExecutionInfo(null, "test"),
                message = message,
            ))
        }
        val releaseModel = CompletableDeferred<Unit>()
        val fake = Fake {
            releaseModel.await()
            text("完成")
        }
        ChatAgent(
            toolRegistry = testTools(),
            createHttpClient = { error("unused") },
            sessions = history,
            executorOverride = fake,
        ).use { agent ->
            agent.ready()
            assertEquals(250, agent.state.value.lines.size)
            val revision = agent.state.value.userMessageRevision
            agent.updateDraft("第 251 条")
            assertTrue(agent.send("第 251 条", config))

            withTimeout(5_000) { agent.state.first { it.userMessageRevision > revision } }
            assertEquals(250, agent.state.value.lines.size)
            assertEquals(ChatRole.USER, agent.state.value.lines.last().role)
            assertEquals("第 251 条", agent.state.value.lines.last().text)

            releaseModel.complete(Unit)
            assertNull(agent.finished().error)
        }
    }

    @Test fun multipleLabApiCategoriesCanBeReadBeforeAScriptIsSavedWithoutRunningIt(): Unit = runBlocking {
        var requests = 0
        val referenceQueries = mutableListOf<String>()
        var saved: Triple<String?, String, String>? = null
        var executions = 0
        val history = TestSessionHistory()
        val source = "def start():\n    log_ctrl.print_msg('ready')"
        val fake = Fake {
            when (requests++) {
                0 -> call("lab_api_reference", "{\"query\":\"index\"}", "reference-index-call")
                1 -> call("lab_api_reference", "{\"query\":\"runtime logging\"}", "reference-call")
                2 -> call(
                    "save_lab_script",
                    buildJsonObject {
                        put("name", "状态脚本")
                        put("source", source)
                    }.toString(),
                    "save-call",
                )
                else -> text("已保存，尚未运行。")
            }
        }
        ChatAgent(
            toolRegistry = testTools(
                status = { testStatus("已连接") },
                labApiReference = { query -> referenceQueries += query; testReference(query) },
                saveLabScript = { original, name, savedSource ->
                    saved = Triple(original, name, savedSource)
                    SaveLabScriptTool.Result(name, original == null, savedSource.length, 1)
                },
                execute = {
                    executions++
                    ExecuteLabPythonTool.Result(ExecuteLabPythonTool.Status.START_COMMAND_SENT, "run-1")
                },
            ),
            createHttpClient = { error("unused") },
            sessions = history,
            executorOverride = fake,
        ).use { agent ->
            agent.ready()
            agent.send("编写并保存状态脚本", config)

            assertNull(agent.finished().error)
            assertEquals(listOf("index", "runtime logging"), referenceQueries)
            assertEquals(Triple(null, "状态脚本", source), saved)
            assertEquals(0, executions)
            assertEquals("已保存，尚未运行。", agent.state.value.lastReply)
            assertEquals(3, agent.state.value.lines.count { it.role == ChatRole.TOOL })
            assertFalse(agent.state.value.lines.any { it.text.contains("reference:runtime logging") })
            val toolResults = history.events.filterIsInstance<MessageEvent>()
                .flatMap { it.message.parts }.filterIsInstance<MessagePart.Tool.Result>()
            assertEquals(3, toolResults.size)
            val reference = Json.parseToJsonElement(toolResults.first().output).jsonObject
            assertTrue(reference.getValue("inCatalog").jsonPrimitive.boolean)
            assertEquals("test", reference.getValue("sections").jsonArray.single().jsonObject
                .getValue("category").jsonPrimitive.content)
            val save = Json.parseToJsonElement(toolResults.last().output).jsonObject
            assertEquals("状态脚本", save.getValue("name").jsonPrimitive.content)
            assertEquals(source.length, save.getValue("sourceLength").jsonPrimitive.int)
        }

        agent(
            status = { "已连接" },
            execute = { error("Reopening history must not execute a script") },
            stopRobot = { error("Reopening history must not stop the robot") },
            executor = Fake { error("Reopening history must not call the model") },
            sessions = history,
        ).use { reopened ->
            val lines = reopened.ready().lines
            val toolLines = lines.filter { it.role == ChatRole.TOOL }
            assertEquals(3, toolLines.size)
            assertTrue(toolLines.all { it.toolCall != null && it.toolResult != null })
            assertFalse(lines.any { it.text.contains("reference:runtime logging") })
        }
    }

    @Test fun deletingASavedScriptRequiresDurableApproval(): Unit = runBlocking {
        var requests = 0
        var deleted: String? = null
        val history = TestSessionHistory()
        val fake = Fake {
            if (requests++ == 0) {
                call("delete_lab_script", "{\"name\":\"旧巡检\"}", "delete-call")
            } else {
                text("已删除旧巡检。")
            }
        }
        ChatAgent(
            toolRegistry = testTools(
                status = { testStatus("已连接") },
                deleteLabScript = { name ->
                    deleted = name
                    DeleteLabScriptTool.Result(name, DeleteLabScriptTool.Status.DELETED)
                },
            ),
            createHttpClient = { error("unused") },
            sessions = history,
            executorOverride = fake,
        ).use { agent ->
            agent.ready()
            agent.send("删除旧巡检", config)
            withTimeout(5_000) { agent.state.first { it.approval != null || !it.running } }

            assertEquals("delete_lab_script", agent.state.value.approval?.toolCall?.tool)
            assertEquals("旧巡检", agent.state.value.approval?.preview)
            assertNull(deleted)
            agent.approve(true)
            assertNull(agent.finished().error)
            assertEquals("旧巡检", deleted)
            assertEquals("delete-call", history.events.filterIsInstance<ToolApprovalRequestedEvent>().single().toolCall.id)
            assertTrue(history.events.filterIsInstance<ToolApprovalResolvedEvent>().single().accepted)
        }
    }

    @Test fun generatedLabScriptsRejectImportsBeforeApproval(): Unit = runBlocking {
        var executions = 0
        val fake = Fake {
            call("execute_lab_python", "{\"source\":\"import time\\ndef start(): pass\"}")
        }
        agent({ "已连接" }, { executions++; "executed" }, { "stopped" }, fake).use { agent ->
            agent.ready()
            agent.send("运行", config)

            assertNull(agent.finished().error)
            assertContains(agent.state.value.lastReply, "import")
            assertEquals(0, executions)
            assertNull(agent.state.value.approval)
        }
    }

    @Test fun approvalWaitDoesNotConsumeModelOrToolTimeout(): Unit = runBlocking {
        var executions = 0
        val fake = Fake { call("execute_lab_python", "{\"source\":\"def start(): pass\"}") }
        agent(
            status = { "已连接" },
            execute = { executions++; "启动命令已发送" },
            stopRobot = { error("No stop") },
            executor = fake,
            operationTimeoutMillis = 100,
        ).use { agent ->
            agent.ready()
            agent.send("执行", config)
            withTimeout(5000) { agent.state.first { it.approval != null || !it.running } }
            delay(200)
            assertTrue(agent.state.value.running)

            agent.approve(true)
            assertNull(agent.finished().error)
            assertEquals(1, executions)
        }
    }
    @Test fun secretAndInvalidUrlNeverLeak() {
        assertFalse(config.toString().contains(config.apiKey))
        assertFails { config.copy(endpoint = "http://example.com").validate() }
        assertFails { config.copy(endpoint = "https://secret@example.com").validate() }
        assertFails { config.copy(endpoint = "https://example.com?debug=true").validate() }
        assertFails { config.copy(endpoint = "https://example.com/#fragment").validate() }
    }
    @Test fun originalProviderExceptionIsNotRewritten(): Unit = runBlocking {
        val fake = Fake { throw ai.koog.http.client.KoogHttpClientException(statusCode = 401,
            errorBody = "Authorization: Bearer provider-secret", message = "request-private-content") }
        val history = TestSessionHistory()
        agent({ "disconnected" }, { error("Must not execute") }, { error("Must not stop") }, fake, history).use { agent ->
            agent.ready()
            agent.send("hello", config)
            val error = requireNotNull(agent.finished().error)
            val runId = history.events.filterIsInstance<AgentStartingEvent>().single().runId
            assertFalse(error.contains(runId))
            assertContains(error, "request-private-content")
            val failure = history.events.filterIsInstance<AgentExecutionFailedEvent>().single().failure
            assertContains(failure, "KoogHttpClientException")
            assertContains(failure, "request-private-content")
            assertContains(failure, "ChatAgentTest")
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

    @Test fun anEmptyComposerStoresNothingUntilAMessageIsSent() = runBlocking {
        val fake = Fake { text("unused") }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake, TestSessionHistory()).use { agent ->
            val ready = agent.ready()
            assertNull(ready.sessionId, "an unused new chat must not create a session")
            assertTrue(ready.sessions.isEmpty())
            agent.newSession()
            assertNull(agent.state.value.sessionId)
            assertTrue(agent.state.value.sessions.isEmpty())
        }
    }

    @Test fun theFirstMessageCreatesTheSessionAndTheListKeepsTheTitle() = runBlocking {
        val fake = Fake { text("unused") }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake, TestSessionHistory()).use { agent ->
            agent.ready()
            agent.send("看看电量", config)
            agent.finished()
            val created = withTimeout(5_000) { agent.state.first { it.sessionId != null } }
            val sessionId = requireNotNull(created.sessionId)
            assertEquals(1, created.sessions.size)
            assertEquals("看看电量", created.sessions.single { it.id == sessionId }.title)

            agent.renameSession(sessionId, "巡检")
            withTimeout(5_000) { agent.state.first { state -> state.sessions.any { it.id == sessionId && it.title == "巡检" } } }
            agent.deleteSession(sessionId)
            withTimeout(5_000) { agent.state.first { state -> state.sessions.none { it.id == sessionId } } }
            // Deleting the open conversation falls back to an empty composer, not to a new stored session.
            assertNull(agent.state.value.sessionId)
        }
    }

    @Test fun draftsAreScopedToConversationAndClearedOnceSent() = runBlocking {
        val fake = Fake { text("完成") }
        agent({ "未连接" }, { error("No robot") }, { error("No robot") }, fake, TestSessionHistory()).use { agent ->
            agent.ready()
            agent.updateDraft("第一个会话的草稿")
            assertEquals("第一个会话的草稿", agent.state.value.draft)

            agent.send("第一条消息", config)
            agent.finished()
            val first = requireNotNull(agent.state.value.sessionId)
            assertEquals("", agent.state.value.draft, "a sent draft is cleared")

            agent.updateDraft("第一个会话的新草稿")
            agent.newSession()
            withTimeout(5_000) { agent.state.first { it.sessionId == null } }
            assertEquals("", agent.state.value.draft)

            agent.openSession(first)
            withTimeout(5_000) { agent.state.first { it.sessionId == first } }
            assertEquals("第一个会话的新草稿", agent.state.value.draft)
        }
    }
}
