package cn.elonzh.hanppie.agent.runtime

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.builder.*
import ai.koog.agents.core.dsl.extension.*
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import ai.koog.prompt.streaming.toMessageResponse
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Explicit LLM -> tools -> LLM graph used for every Hanppie agent run. */
@OptIn(ExperimentalUuidApi::class)
internal fun hanppieAgentStrategy(
    onStreamFrame: suspend (StreamFrame) -> Unit,
    onMessage: suspend (Message) -> Unit,
    terminalToolNames: Set<String> = emptySet(),
    toolsWithoutRequiredArguments: Set<String> = emptySet(),
    maxModelRequests: Int = 8,
    modelRequestTimeoutMillis: Long = 120_000,
): AIAgentGraphStrategy<String, String> {
    require(maxModelRequests > 0) { "maxModelRequests must be positive" }
    require(modelRequestTimeoutMillis > 0) { "modelRequestTimeoutMillis must be positive" }
    var modelRequests = 0

    return strategy("hanppie-agent") {
        val nodeExecuteTool by nodeExecuteTools(parallel = false)

        suspend fun AIAgentLLMWriteSession.requestResponse(): Message.Assistant {
            if (++modelRequests > maxModelRequests) throw ModelRequestLimitExceededException(maxModelRequests)
            val frames = withTimeout(modelRequestTimeoutMillis) {
                requestLLMStreaming().onEach(onStreamFrame).toList()
            }
            check(frames.any { frame -> frame is StreamFrame.End }) { "Model output was interrupted" }
            check(frames.filterIsInstance<StreamFrame.End>().none { frame -> frame.finishReason.isTokenLimit() }) {
                "Model output was truncated"
            }
            val response = frames.withUsableToolCalls(toolsWithoutRequiredArguments).toMessageResponse().withStableToolCallIds()
            check(response.hasToolCall() || response.visibleText().isNotBlank()) {
                "Model returned neither visible text nor a tool call"
            }
            onMessage(response)
            appendPrompt { message(response) }
            return response
        }

        val nodeCallInitialModel by node<String, Message.Assistant> { input ->
            llm.writeSession {
                appendPrompt { user(input) }
                requestResponse()
            }
        }

        val nodeRecordToolResults by node<ReceivedToolResults, ReceivedToolResults> { results ->
            val resultMessage = prompt("hanppie-tool-results") {
                user { results.toolResults.forEach { result -> toolResult(result.toMessagePart()) } }
            }.messages.single()
            onMessage(resultMessage)
            llm.writeSession {
                appendPrompt { message(resultMessage) }
            }
            results
        }

        val nodeCallModelAfterTools by node<ReceivedToolResults, Message.Assistant> {
            llm.writeSession { requestResponse() }
        }

        edge(nodeStart forwardTo nodeCallInitialModel)
        edge(nodeCallInitialModel forwardTo nodeExecuteTool onToolCalls { true })
        edge(
            (nodeCallInitialModel forwardTo nodeFinish)
                onCondition { response -> !response.hasToolCall() }
                transformed { response -> response.visibleText() },
        )
        edge(nodeExecuteTool forwardTo nodeRecordToolResults)
        edge(
            (nodeRecordToolResults forwardTo nodeFinish)
                .onCondition { results -> results.endsRun(terminalToolNames) }
                .transformed { results -> results.visibleOutput() },
        )
        edge(
            (nodeRecordToolResults forwardTo nodeCallModelAfterTools)
                .onCondition { results -> !results.endsRun(terminalToolNames) },
        )
        edge(nodeCallModelAfterTools forwardTo nodeExecuteTool onToolCalls { true })
        edge(
            (nodeCallModelAfterTools forwardTo nodeFinish)
                onCondition { response -> !response.hasToolCall() }
                transformed { response -> response.visibleText() },
        )
    }
}

internal class ModelRequestLimitExceededException(limit: Int) :
    IllegalStateException("Agent model request limit reached: $limit")

private fun ReceivedToolResults.endsRun(terminalToolNames: Set<String>): Boolean =
    toolResults.any { result -> result.tool in terminalToolNames }

private fun ReceivedToolResults.visibleOutput(): String =
    toolResults.joinToString("\n") { result -> result.output }.ifBlank { "Tool execution completed" }

private fun Message.Assistant.hasToolCall(): Boolean = parts.any { part -> part is MessagePart.Tool.Call }

private fun Message.Assistant.visibleText(): String =
    parts.filterIsInstance<MessagePart.Text>().joinToString("") { part -> part.text }

@OptIn(ExperimentalUuidApi::class)
private fun Message.Assistant.withStableToolCallIds(): Message.Assistant = copy(
    parts = parts.map { part ->
        if (part is MessagePart.Tool.Call && part.id.isNullOrBlank()) {
            part.copy(id = Uuid.random().toString())
        } else {
            part
        }
    },
)

private fun String?.isTokenLimit(): Boolean =
    this?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_') in
        setOf("length", "max_tokens", "max_output_tokens")

/** Reassemble each logical call once: empty text deltas can prematurely flush Koog's pending call. */
internal fun List<StreamFrame>.withUsableToolCalls(
    toolsWithoutRequiredArguments: Set<String> = emptySet(),
): List<StreamFrame> {
    val indexesById = buildMap<String, Int> {
        for (frame in this@withUsableToolCalls) {
            val id = when (frame) {
                is StreamFrame.ToolCallDelta -> frame.id
                is StreamFrame.ToolCallComplete -> frame.id
                else -> null
            }
            val index = when (frame) {
                is StreamFrame.ToolCallDelta -> frame.index
                is StreamFrame.ToolCallComplete -> frame.index
                else -> null
            }
            if (!id.isNullOrBlank() && index != null) put(id, index)
        }
    }
    fun key(frame: StreamFrame, position: Int): String {
        val (id, index) = when (frame) {
            is StreamFrame.ToolCallDelta -> frame.id to frame.index
            is StreamFrame.ToolCallComplete -> frame.id to frame.index
            else -> error("Not a tool frame")
        }
        return (index ?: id?.let(indexesById::get))?.let { "index:$it" }
            ?: id?.takeIf(String::isNotBlank)?.let { "id:$it" } ?: "anonymous:$position"
    }
    val groups = withIndex().filter { it.value is StreamFrame.ToolCallDelta || it.value is StreamFrame.ToolCallComplete }
        .groupBy { key(it.value, it.index) }
    val emitted = mutableSetOf<String>()
    return mapIndexedNotNull { position, frame ->
        if (frame !is StreamFrame.ToolCallDelta && frame !is StreamFrame.ToolCallComplete) return@mapIndexedNotNull frame
        val key = key(frame, position)
        if (!emitted.add(key)) return@mapIndexedNotNull null
        val group = groups.getValue(key).map { it.value }
        val deltas = group.filterIsInstance<StreamFrame.ToolCallDelta>()
        val completions = group.filterIsInstance<StreamFrame.ToolCallComplete>()
        val names = (deltas.map { it.name } + completions.map { it.name }).filterNotNull().filter(String::isNotBlank).distinct()
        if (names.isEmpty()) {
            strategyLogger.warn { "Dropped a tool call with no name" }
            return@mapIndexedNotNull null
        }
        check(names.size == 1) { "Conflicting tool names in one streamed call" }
        val name = names.single()
        val fragments = deltas.joinToString("") { it.content.orEmpty() }
        val completed = completions.singleOrNull()?.content.orEmpty()
        val arguments = when {
            fragments.isArgumentObject() -> fragments
            completed.isArgumentObject() -> completed
            fragments.isBlank() && completions.all { it.content.isBlank() } && name in toolsWithoutRequiredArguments -> "{}"
            else -> error("Incomplete or invalid arguments for tool '$name'; tool was not executed")
        }
        StreamFrame.ToolCallComplete(
            id = (deltas.map { it.id } + completions.map { it.id }).firstOrNull { !it.isNullOrBlank() },
            name = name,
            content = arguments,
            index = deltas.firstOrNull { it.index != null }?.index ?: completions.firstOrNull { it.index != null }?.index,
        )
    }
}

private fun String.isArgumentObject(): Boolean =
    runCatching { Json.parseToJsonElement(this) is JsonObject }.getOrDefault(false)

private val strategyLogger = KotlinLogging.logger {}
