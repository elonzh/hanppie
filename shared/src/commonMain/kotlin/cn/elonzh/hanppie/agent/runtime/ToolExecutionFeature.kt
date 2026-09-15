@file:OptIn(ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class)

package cn.elonzh.hanppie.agent.runtime

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.environment.AIAgentEnvironment
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.ToolResultKind
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeout

private val toolExecutionLogger = KotlinLogging.logger {}

/** A class-based Koog tool whose call must be approved before its implementation runs. */
internal interface ApprovalRequiredTool<TArgs, TResult> {
    fun approvalPreview(args: TArgs): String
    fun rejectedResult(args: TArgs): TResult
    val displayPreviewAfterApproval: Boolean get() = false
}

/** A tool whose result completes the current agent run without another model request. */
internal interface TerminalTool

/**
 * Run-scoped policy around Koog's own [AIAgentEnvironment].
 *
 * The application injects a [ToolRegistry] once. This feature keeps approval, durable start events
 * and timeouts on the exact [MessagePart.Tool.Call] dispatched by Koog, without proxying every tool.
 */
internal class ToolExecutionFeature private constructor() {
    internal class Config(agentConfig: AIAgentConfig) : FeatureConfig() {
        internal val serializer: JSONSerializer = agentConfig.serializer
        var operationTimeoutMillis: Long = 120_000
        var onStarting: suspend (MessagePart.Tool.Call) -> Unit = {}
        var requestApproval: suspend (MessagePart.Tool.Call, String) -> Boolean = { _, _ -> true }
        var onApprovedPreview: suspend (MessagePart.Tool.Call, String) -> Unit = { _, _ -> }
    }

    internal companion object Feature : AIAgentGraphFeature<Config, ToolExecutionFeature> {
        override val key = createStorageKey<ToolExecutionFeature>("hanppie-tool-execution")

        override fun createInitialConfig(agentConfig: AIAgentConfig) = Config(agentConfig)

        override fun install(config: Config, pipeline: AIAgentGraphPipeline): ToolExecutionFeature {
            require(config.operationTimeoutMillis > 0) { "operationTimeoutMillis must be positive" }
            val feature = ToolExecutionFeature()
            pipeline.interceptEnvironmentCreated(this) { context, environment ->
                val toolRegistry = (context.agent as GraphAIAgent<*, *>).toolRegistry
                PolicyAgentEnvironment(environment, toolRegistry, config)
            }
            return feature
        }
    }
}

private class PolicyAgentEnvironment(
    private val delegate: AIAgentEnvironment,
    private val toolRegistry: ToolRegistry,
    private val config: ToolExecutionFeature.Config,
) : AIAgentEnvironment {
    override suspend fun executeTool(toolCall: MessagePart.Tool.Call): ReceivedToolResult =
        executeTool(toolCall, ToolCallMetadata.EMPTY)

    override suspend fun executeTool(
        toolCall: MessagePart.Tool.Call,
        metadata: ToolCallMetadata,
    ): ReceivedToolResult {
        val tool = toolRegistry.getToolOrNull(toolCall.tool)
            ?: return delegate.executeTool(toolCall, metadata)
        val approvalTool = tool as? ApprovalRequiredTool<*, *>
        if (approvalTool != null) {
            val prepared = try {
                prepareApproval(toolCall, tool, approvalTool)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ToolApprovalPreparationException) {
                return validationFailure(toolCall, tool, error.cause ?: error, error.args)
            }
            val accepted = config.requestApproval(toolCall, prepared.preview)
            if (!accepted) return rejected(toolCall, tool, prepared.args, prepared.rejectedResult)
            if (prepared.displayPreviewAfterApproval) {
                config.onApprovedPreview(toolCall, prepared.preview)
            }
        }

        config.onStarting(toolCall)
        return withTimeout(config.operationTimeoutMillis) {
            delegate.executeTool(toolCall, metadata)
        }
    }

    override suspend fun reportProblem(exception: Throwable) = delegate.reportProblem(exception)

    private fun prepareApproval(
        toolCall: MessagePart.Tool.Call,
        tool: ToolBase<*, *>,
        approvalTool: ApprovalRequiredTool<*, *>,
    ): PreparedApproval {
        val argsJson = try {
            toolCall.argsJson.toKoogJSONObject()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            toolExecutionLogger.error(error) {
                "Tool approval arguments could not be parsed tool=${toolCall.tool} toolCallId=${toolCall.id}"
            }
            throw ToolApprovalPreparationException(JSONObject(emptyMap()), error)
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val typedTool = tool as ToolBase<Any?, Any?>
            @Suppress("UNCHECKED_CAST")
            val typedApproval = approvalTool as ApprovalRequiredTool<Any?, Any?>
            val args = typedTool.decodeArgs(argsJson, config.serializer)
            PreparedApproval(
                args = args,
                preview = typedApproval.approvalPreview(args),
                rejectedResult = typedApproval.rejectedResult(args),
                displayPreviewAfterApproval = typedApproval.displayPreviewAfterApproval,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            toolExecutionLogger.error(error) {
                "Tool approval preparation failed tool=${toolCall.tool} toolCallId=${toolCall.id}"
            }
            throw ToolApprovalPreparationException(argsJson, error)
        }
    }

    private fun rejected(
        toolCall: MessagePart.Tool.Call,
        tool: ToolBase<*, *>,
        args: Any?,
        result: Any?,
    ): ReceivedToolResult {
        @Suppress("UNCHECKED_CAST")
        val typedTool = tool as ToolBase<Any?, Any?>
        val toolArgs = typedTool.encodeArgs(args, config.serializer)
        return ReceivedToolResult(
            id = toolCall.id,
            tool = toolCall.tool,
            toolArgs = toolArgs,
            toolDescription = tool.descriptor.description,
            output = typedTool.encodeResultToStringUnsafe(result, config.serializer),
            resultKind = ToolResultKind.Success,
            result = typedTool.encodeResult(result, config.serializer),
            parts = typedTool.encodeResultToPartsUnsafe(result, config.serializer),
        )
    }

    private fun validationFailure(
        toolCall: MessagePart.Tool.Call,
        tool: ToolBase<*, *>,
        error: Throwable,
        args: JSONObject = JSONObject(emptyMap()),
    ) = ReceivedToolResult(
        id = toolCall.id,
        tool = toolCall.tool,
        toolArgs = args,
        toolDescription = tool.descriptor.description,
        output = error.message ?: error.toString(),
        resultKind = ToolResultKind.ValidationError(error),
        result = null,
    )

    private data class PreparedApproval(
        val args: Any?,
        val preview: String,
        val rejectedResult: Any?,
        val displayPreviewAfterApproval: Boolean,
    )

    private class ToolApprovalPreparationException(
        val args: JSONObject,
        cause: Throwable,
    ) : RuntimeException(cause.message, cause)
}
