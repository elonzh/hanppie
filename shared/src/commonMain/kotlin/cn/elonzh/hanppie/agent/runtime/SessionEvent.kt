package cn.elonzh.hanppie.agent.runtime

import ai.koog.agents.core.agent.execution.AgentExecutionInfo
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

internal const val RUN_INTERRUPTED_NOTICE = "hanppie://recovery/run-interrupted"
internal const val PROCESS_RESTART_NOTICE = "hanppie://recovery/process-restart"
internal const val TOOL_OUTCOME_UNKNOWN_NOTICE_PREFIX = "hanppie://recovery/tool-outcome-unknown/"

/** Durable Session fact. Streaming frames are Koog runtime signals and never become Session events. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("eventType")
internal sealed interface SessionEvent {
    val eventId: String
    val timestamp: Long
}

/** Event emitted within a Koog agent run. */
@Serializable
internal sealed interface AgentRunEvent : SessionEvent {
    val runId: String
    val executionInfo: AgentExecutionInfo
}

@Serializable
@SerialName("SessionCreated")
internal data class SessionCreatedEvent(
    override val eventId: String,
    override val timestamp: Long,
    val title: String,
) : SessionEvent

@Serializable
@SerialName("SessionRenamed")
internal data class SessionRenamedEvent(
    override val eventId: String,
    override val timestamp: Long,
    val title: String,
) : SessionEvent

@Serializable
@SerialName("SessionArchived")
internal data class SessionArchivedEvent(
    override val eventId: String,
    override val timestamp: Long,
    val archived: Boolean,
) : SessionEvent

@Serializable
@SerialName("AgentStarting")
internal data class AgentStartingEvent(
    override val eventId: String,
    override val runId: String,
    override val timestamp: Long,
    override val executionInfo: AgentExecutionInfo,
    val message: Message.User,
    val model: LLModel,
) : AgentRunEvent

@Serializable
@SerialName("Message")
internal data class MessageEvent(
    override val eventId: String,
    override val runId: String,
    override val timestamp: Long,
    override val executionInfo: AgentExecutionInfo,
    val message: Message,
) : AgentRunEvent

/** Hanppie policy fact that has no Koog equivalent. */
@Serializable
@SerialName("ToolApprovalRequested")
internal data class ToolApprovalRequestedEvent(
    override val eventId: String,
    override val runId: String,
    override val timestamp: Long,
    override val executionInfo: AgentExecutionInfo,
    val toolCall: MessagePart.Tool.Call,
) : AgentRunEvent

/** Hanppie policy fact that has no Koog equivalent. */
@Serializable
@SerialName("ToolApprovalResolved")
internal data class ToolApprovalResolvedEvent(
    override val eventId: String,
    override val runId: String,
    override val timestamp: Long,
    override val executionInfo: AgentExecutionInfo,
    val toolCallId: String,
    val accepted: Boolean,
) : AgentRunEvent

/** Durable boundary immediately before a tool side effect. */
@Serializable
@SerialName("ToolCallStarting")
internal data class ToolCallStartingEvent(
    override val eventId: String,
    override val runId: String,
    override val timestamp: Long,
    override val executionInfo: AgentExecutionInfo,
    val toolCall: MessagePart.Tool.Call,
) : AgentRunEvent

@Serializable
@SerialName("AgentCompleted")
internal data class AgentCompletedEvent(
    override val eventId: String,
    override val runId: String,
    override val timestamp: Long,
    override val executionInfo: AgentExecutionInfo,
    val result: String? = null,
) : AgentRunEvent

@Serializable
@SerialName("AgentExecutionFailed")
internal data class AgentExecutionFailedEvent(
    override val eventId: String,
    override val runId: String,
    override val timestamp: Long,
    override val executionInfo: AgentExecutionInfo,
    val errorType: String? = null,
    val errorMessage: String? = null,
) : AgentRunEvent

@Serializable
@SerialName("AgentExecutionCancelled")
internal data class AgentExecutionCancelledEvent(
    override val eventId: String,
    override val runId: String,
    override val timestamp: Long,
    override val executionInfo: AgentExecutionInfo,
    val reason: String? = null,
) : AgentRunEvent

internal fun toolOutcomeUnknownNotice(toolName: String) = TOOL_OUTCOME_UNKNOWN_NOTICE_PREFIX + toolName
internal fun toolNameFromUnknownNotice(text: String): String? =
    text.takeIf { it.startsWith(TOOL_OUTCOME_UNKNOWN_NOTICE_PREFIX) }
        ?.removePrefix(TOOL_OUTCOME_UNKNOWN_NOTICE_PREFIX)
        ?.takeIf(String::isNotBlank)
