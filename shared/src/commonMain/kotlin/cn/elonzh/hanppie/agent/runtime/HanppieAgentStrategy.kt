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

/** Explicit LLM -> tools -> LLM graph used for every Hanppie agent run. */
@OptIn(ExperimentalUuidApi::class)
internal fun hanppieAgentStrategy(
    onStreamFrame: suspend (StreamFrame) -> Unit,
    onMessage: suspend (Message) -> Unit,
    terminalToolNames: Set<String> = emptySet(),
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
            val response = frames.withUsableToolCalls().toMessageResponse().withStableToolCallIds()
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

/**
 * Repairs tool calls whose frames arrive in shapes Koog cannot use as-is. The OpenAI-compatible clients
 * (DashScope among them) attach the tool name to the opening delta and send later deltas without it, while
 * Koog keys a call by its `index` and rarely repeats the `id`, so a complete frame can end up with neither a
 * name nor a parsable argument document. Every repair keeps the call instead of failing the turn:
 *
 * - the name is looked up by `index` first and falls back to the `id`, because those two fields are not sent
 *   consistently across deltas;
 * - argument content that is empty or not parsable JSON becomes `{}` rather than aborting materialization;
 * - a call that never named a tool anywhere is a phantom frame from the stream and is dropped with its
 *   deltas, so the turn keeps whatever text the model produced.
 */
internal fun List<StreamFrame>.withUsableToolCalls(): List<StreamFrame> {
    val namesByIndex = mutableMapOf<Int, String>()
    val namesById = mutableMapOf<String, String>()
    for (frame in this) {
        if (frame is StreamFrame.ToolCallDelta && frame.name.orEmpty().isNotBlank()) {
            val name = frame.name.orEmpty()
            frame.index?.let { index -> namesByIndex.putIfAbsent(index, name) }
            frame.id?.let { id -> namesById.putIfAbsent(id, name) }
        }
    }
    fun nameOf(frame: StreamFrame.ToolCallComplete): String = frame.name.orEmpty().ifBlank {
        frame.index?.let(namesByIndex::get) ?: frame.id?.let(namesById::get).orEmpty()
    }
    val phantoms = filterIsInstance<StreamFrame.ToolCallComplete>()
        .filter { complete -> nameOf(complete).isBlank() }
        .flatMap { complete -> listOfNotNull(complete.index, complete.id?.let { null }) }
        .toSet()
    val phantomIds = filterIsInstance<StreamFrame.ToolCallComplete>()
        .filter { complete -> nameOf(complete).isBlank() }
        .mapNotNull { complete -> complete.id }
        .toSet()
    return mapNotNull { frame ->
        when {
            frame is StreamFrame.ToolCallComplete -> {
                val name = nameOf(frame)
                if (name.isBlank()) {
                    strategyLogger.warn {
                        "Dropped a tool call frame that named no tool id=${frame.id ?: "none"} index=${frame.index ?: "none"}"
                    }
                    null
                } else {
                    if (name != frame.name.orEmpty()) {
                        strategyLogger.info { "Restored tool name '$name' from an earlier delta id=${frame.id ?: "none"}" }
                    }
                    if (frame.content != frame.content.usableArguments()) {
                        strategyLogger.info {
                            "Replaced unparsable tool arguments with {} tool=$name id=${frame.id ?: "none"}"
                        }
                    }
                    frame.copy(name = name, content = frame.content.usableArguments())
                }
            }
            frame is StreamFrame.ToolCallDelta &&
                (frame.index in phantoms || frame.id in phantomIds) -> null
            else -> frame
        }
    }
}

/** Argument content must be a JSON document, otherwise materializing the response aborts the whole run. */
private fun String.usableArguments(): String =
    if (isNotBlank() && runCatching { Json.parseToJsonElement(this) }.isSuccess) this else "{}"

private val strategyLogger = KotlinLogging.logger {}
