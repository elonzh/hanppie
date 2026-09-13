package cn.elonzh.hanppie.agent.provider

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.settings.ModelProviderPreset
import cn.elonzh.hanppie.ui.settings.ModelSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val modelTesterLogger = KotlinLogging.logger {}

internal data class ModelTestState(
    val running: Boolean = false,
    val success: Boolean? = null,
    val message: String? = null,
    val checkedAtEpochMillis: Long? = null,
    val provider: ModelProviderPreset? = null,
    val endpoint: String? = null,
    val stages: List<ModelTestStageResult> = emptyList(),
)

internal data class ModelCatalogState(
    val loading: Boolean = false,
    val models: List<String> = emptyList(),
    val message: String? = null,
    val failed: Boolean = false,
    val provider: ModelProviderPreset? = null,
    val endpoint: String? = null,
)

internal enum class ModelTestStage { LOCAL, CATALOG, STREAMING, TOOL_CALL }

internal data class ModelTestStageResult(
    val stage: ModelTestStage,
    val passed: Boolean,
    val message: String,
)

/** Discovers model IDs and verifies text streaming plus a side-effect-free forced tool call. */
internal class ModelConfigurationTester(
    private val scope: CoroutineScope,
    private val createHttpClient: () -> HttpClient,
    private val clock: Clock = Clock.System,
) {
    val state = MutableStateFlow(ModelTestState())
    val catalogState = MutableStateFlow(ModelCatalogState())
    private var job: Job? = null
    private var catalogJob: Job? = null
    private var catalogConfig: ModelSettings? = null

    fun loadCatalog(config: ModelSettings) {
        if (catalogJob?.isActive == true && catalogConfig == config) return
        catalogJob?.cancel()
        catalogConfig = config
        val initial = ModelCatalogState(provider = config.provider, endpoint = config.endpoint)
        if (config.apiKey.isBlank()) {
            catalogState.value = initial.copy(
                message = tr(Res.string.configure_api_key_to_load_model_catalog),
            )
            return
        }
        catalogState.value = initial.copy(loading = true)
        catalogJob = scope.launch {
            var http: HttpClient? = null
            try {
                http = createHttpClient()
                val models = discover(http, config)
                if (catalogConfig == config) {
                    catalogState.value = initial.copy(models = models)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (catalogConfig == config) {
                    modelTesterLogger.error(error) {
                        "Model catalog request failed provider=${config.provider.name} endpoint=${config.endpoint}"
                    }
                    val detail = if (error is ProbeFailure) error.detail else error.toString()
                    catalogState.value = initial.copy(
                        message = tr(Res.string.model_catalog_load_failed_value, detail),
                        failed = true,
                    )
                }
            } finally {
                http?.close()
                if (catalogConfig == config) {
                    catalogState.value = catalogState.value.copy(loading = false)
                }
            }
        }
    }

    fun test(config: ModelSettings) {
        if (job?.isActive == true) return
        state.value = ModelTestState(running = true, provider = config.provider, endpoint = config.endpoint)
        job = scope.launch {
            var http: HttpClient? = null
            var executor: MultiLLMPromptExecutor? = null
            val stages = mutableListOf<ModelTestStageResult>()
            try {
                config.validate()
                stages += passed(ModelTestStage.LOCAL)
                publish(config, stages)

                http = createHttpClient()
                val models = discover(http, config)
                publish(config, stages)
                if (config.model !in models) {
                    throw ProbeFailure(ModelTestStage.CATALOG,
                        tr(Res.string.model_test_model_missing_value, config.model))
                }
                stages += passed(ModelTestStage.CATALOG)
                publish(config, stages)

                val client = OpenAILLMClient(
                    config.apiKey,
                    OpenAIClientSettings(
                        baseUrl = config.endpoint.trimEnd('/') + "/",
                        chatCompletionsPath = "chat/completions",
                        modelsPath = "models",
                    ),
                    KtorKoogHttpClient.Factory(http),
                )
                executor = MultiLLMPromptExecutor(config.llModel.provider to client)
                val textFrames = executor.executeStreaming(
                    prompt("hanppie-model-text-test", params = OpenAIChatParams(
                        maxTokens = 16,
                        toolChoice = LLMParams.ToolChoice.None,
                    )) {
                        system("This is a configuration test. Reply with exactly OK.")
                        user("OK")
                    },
                    config.llModel,
                ).toList()
                checkCompleteStream(textFrames, ModelTestStage.STREAMING)
                if (textFrames.none { frame ->
                        (frame is StreamFrame.TextDelta && frame.text.isNotBlank()) ||
                            (frame is StreamFrame.TextComplete && frame.text.isNotBlank())
                    }) {
                    throw ProbeFailure(ModelTestStage.STREAMING,
                        tr(Res.string.the_model_returned_no_visible_reply))
                }
                stages += passed(ModelTestStage.STREAMING)
                publish(config, stages)

                val toolFrames = executor.executeStreaming(
                    prompt("hanppie-model-tool-test", params = OpenAIChatParams(
                        maxTokens = 64,
                        toolChoice = LLMParams.ToolChoice.Named(PROBE_TOOL),
                        parallelToolCalls = false,
                    )) {
                        system("This is a configuration test. Call the supplied tool once with no arguments.")
                        user("Run the configuration probe.")
                    },
                    config.llModel,
                    listOf(ToolDescriptor(PROBE_TOOL, "A side-effect-free configuration compatibility probe")),
                ).toList()
                checkCompleteStream(toolFrames, ModelTestStage.TOOL_CALL)
                val call = toolFrames.filterIsInstance<StreamFrame.ToolCallComplete>()
                    .singleOrNull { it.name == PROBE_TOOL }
                    ?: throw ProbeFailure(ModelTestStage.TOOL_CALL, tr(Res.string.model_test_tool_not_returned))
                if (call.contentJsonResult.isFailure) {
                    throw ProbeFailure(ModelTestStage.TOOL_CALL, tr(Res.string.model_test_tool_not_returned))
                }
                stages += passed(ModelTestStage.TOOL_CALL)
                state.value = ModelTestState(
                    success = true,
                    message = tr(Res.string.model_test_complete),
                    checkedAtEpochMillis = clock.now().toEpochMilliseconds(),
                    provider = config.provider,
                    endpoint = config.endpoint,
                    stages = stages,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failedStage = (error as? ProbeFailure)?.stage ?: when {
                    stages.none { it.stage == ModelTestStage.LOCAL } -> ModelTestStage.LOCAL
                    stages.none { it.stage == ModelTestStage.CATALOG } -> ModelTestStage.CATALOG
                    stages.none { it.stage == ModelTestStage.STREAMING } -> ModelTestStage.STREAMING
                    else -> ModelTestStage.TOOL_CALL
                }
                modelTesterLogger.error(error) {
                    "Model configuration test failed stage=${failedStage.name} " +
                        "provider=${config.provider.name} endpoint=${config.endpoint}"
                }
                val detail = if (error is ProbeFailure) error.detail else error.toString()
                if (stages.none { it.stage == failedStage }) {
                    stages += ModelTestStageResult(failedStage, false, detail)
                }
                state.value = ModelTestState(
                    success = false,
                    message = detail,
                    checkedAtEpochMillis = clock.now().toEpochMilliseconds(),
                    provider = config.provider,
                    endpoint = config.endpoint,
                    stages = stages,
                )
            } finally {
                executor?.close()
                http?.close()
                state.value = state.value.copy(running = false)
            }
        }
    }

    fun close() {
        job?.cancel()
        catalogJob?.cancel()
    }

    private suspend fun discover(http: HttpClient, config: ModelSettings): List<String> {
        val response = http.get(modelCatalogUrl(config)) {
            bearerAuth(config.apiKey)
            if (config.provider == ModelProviderPreset.DASHSCOPE) parameter("page_size", 100)
        }
        if (response.status.value !in 200..299) {
            throw ProbeFailure(ModelTestStage.CATALOG, "HTTP ${response.status.value}")
        }
        val models = parseDiscoveredModelIds(response.body(), config.provider)
        return models.distinct().sorted().also {
            if (it.isEmpty()) throw ProbeFailure(ModelTestStage.CATALOG,
                tr(Res.string.model_test_catalog_failed_value, "EmptyCatalog"))
        }
    }

    private fun checkCompleteStream(frames: List<StreamFrame>, stage: ModelTestStage) {
        if (frames.none { it is StreamFrame.End }) {
            throw ProbeFailure(stage, tr(Res.string.model_test_transport_failed_value, "IncompleteStream"))
        }
    }

    private fun publish(
        config: ModelSettings,
        stages: List<ModelTestStageResult>,
    ) {
        state.value = ModelTestState(
            running = true,
            provider = config.provider,
            endpoint = config.endpoint,
            stages = stages.toList(),
        )
    }

    private fun passed(stage: ModelTestStage) =
        ModelTestStageResult(stage, true, tr(Res.string.model_test_passed))

    private class ProbeFailure(
        val stage: ModelTestStage,
        val detail: String,
    ) : IllegalStateException(detail)

    private companion object {
        const val PROBE_TOOL = "hanppie_configuration_probe"
    }
}

internal fun modelCatalogUrl(config: ModelSettings): String = URLBuilder(config.endpoint).apply {
    encodedPath = config.provider.catalogPath
}.buildString()

internal fun parseDiscoveredModelIds(body: String, provider: ModelProviderPreset): List<String> {
    val root = Json.parseToJsonElement(body).jsonObject
    return when (provider) {
        ModelProviderPreset.DASHSCOPE -> root["output"]?.jsonObject?.get("models")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["model"]?.jsonPrimitive?.content }
        else -> root["data"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
    }.distinct().sorted()
}
