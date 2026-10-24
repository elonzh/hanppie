@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.agent.provider

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
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
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val modelTesterLogger = KotlinLogging.logger {}

internal data class ModelTestState(
    val running: Boolean = false,
    val success: Boolean? = null,
    val message: UiText? = null,
    val checkedAtEpochMillis: Long? = null,
    private val configuration: ModelSettings? = null,
    val stages: List<ModelTestStageResult> = emptyList(),
) {
    fun matches(config: ModelSettings): Boolean = configuration?.let {
        it.sameServiceAs(config) && it.model == config.model
    } ?: false
}

internal data class ModelCatalogState(
    val loading: Boolean = false,
    val models: List<String> = emptyList(),
    val message: UiText? = null,
    val failed: Boolean = false,
    private val configuration: ModelSettings? = null,
) {
    fun matches(config: ModelSettings): Boolean = configuration?.sameServiceAs(config) == true
}

private fun ModelSettings.sameServiceAs(other: ModelSettings): Boolean =
    provider == other.provider && endpoint == other.endpoint && apiKey == other.apiKey

internal enum class ModelTestStage { LOCAL, CATALOG }

internal data class ModelTestStageResult(
    val stage: ModelTestStage,
    val passed: Boolean,
    val message: String,
)

/** Discovers model IDs and verifies that the endpoint authenticates and answers normally. */
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
    private val catalogRequest = AtomicReference<Any?>(null)

    fun loadCatalog(config: ModelSettings) {
        if (catalogJob?.isActive == true && catalogConfig == config) return
        val request = Any()
        catalogRequest.store(request)
        catalogJob?.cancel()
        catalogConfig = config
        val initial = ModelCatalogState(configuration = config)
        if (config.apiKey.isBlank()) {
            catalogState.value = initial.copy(
                message = uiText(Res.string.configure_api_key_to_load_model_catalog),
            )
            return
        }
        config.validationError(requireModel = false)?.let { feedback ->
            catalogState.value = initial.copy(message = feedback, failed = true)
            return
        }
        catalogState.value = initial.copy(loading = true)
        catalogJob = scope.launch {
            var http: HttpClient? = null
            try {
                http = createHttpClient()
                val models = discover(http, config)
                updateCatalog(request) { initial.copy(models = models) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (catalogRequest.load() === request) {
                    val detail = diagnostic(error, config)
                    modelTesterLogger.error {
                        "Model catalog request failed provider=${config.provider.name} endpoint=${config.endpoint} detail=$detail"
                    }
                    updateCatalog(request) { initial.copy(
                        message = (error as? ProbeFailure)?.feedback ?: uiText(Res.string.model_catalog_load_failed_value, detail),
                        failed = true,
                    ) }
                }
            } finally {
                http?.close()
                updateCatalog(request) { it.copy(loading = false) }
            }
        }
    }

    fun test(config: ModelSettings) {
        if (job?.isActive == true) return
        state.value = ModelTestState(running = true, configuration = config)
        job = scope.launch {
            var http: HttpClient? = null
            val stages = mutableListOf<ModelTestStageResult>()
            try {
                config.validationError()?.let { throw ProbeFailure(ModelTestStage.LOCAL, it) }
                stages += passed(ModelTestStage.LOCAL)
                publish(config, stages)

                http = createHttpClient()
                val models = discover(http, config)
                publish(config, stages)
                if (config.model !in models) {
                    throw ProbeFailure(ModelTestStage.CATALOG,
                        uiText(Res.string.model_test_model_missing_value, redact(config.model, config)))
                }
                stages += passed(ModelTestStage.CATALOG)
                modelTesterLogger.info {
                    "Model configuration test passed stages=${stages.joinToString { it.stage.name }} " +
                        "provider=${config.provider.name} endpoint=${config.endpoint} models=${models.size}"
                }
                state.value = ModelTestState(
                    success = true,
                    message = uiText(Res.string.model_test_complete),
                    checkedAtEpochMillis = clock.now().toEpochMilliseconds(),
                    configuration = config,
                    stages = stages,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failedStage = (error as? ProbeFailure)?.stage ?: when {
                    stages.none { it.stage == ModelTestStage.LOCAL } -> ModelTestStage.LOCAL
                    else -> ModelTestStage.CATALOG
                }
                val detail = diagnostic(error, config)
                modelTesterLogger.error {
                    "Model configuration test failed stage=${failedStage.name} " +
                        "provider=${config.provider.name} endpoint=${if (failedStage == ModelTestStage.LOCAL) "[local validation]" else config.endpoint} detail=$detail"
                }
                if (stages.none { it.stage == failedStage }) {
                    stages += ModelTestStageResult(failedStage, false, detail)
                }
                state.value = ModelTestState(
                    success = false,
                    message = (error as? ProbeFailure)?.feedback ?: uiText(Res.string.model_test_catalog_failed_value, detail),
                    checkedAtEpochMillis = clock.now().toEpochMilliseconds(),
                    configuration = config,
                    stages = stages,
                )
            } finally {
                http?.close()
                state.value = state.value.copy(running = false)
            }
        }
    }

    fun close() {
        catalogRequest.store(null)
        job?.cancel()
        catalogJob?.cancel()
        catalogState.update { it.copy(loading = false) }
    }

    private suspend fun discover(http: HttpClient, config: ModelSettings): List<String> {
        val response = http.get(modelCatalogUrl(config)) {
            bearerAuth(config.apiKey)
            if (config.provider == ModelProviderPreset.DASHSCOPE) parameter("page_size", 100)
        }
        if (response.status.value !in 200..299) {
            throw ProbeFailure(ModelTestStage.CATALOG, uiText(Res.string.model_catalog_load_failed_value, "HTTP ${response.status.value}"))
        }
        val models = parseDiscoveredModelIds(response.body(), config.provider)
        return models.distinct().sorted().also {
            if (it.isEmpty()) throw ProbeFailure(ModelTestStage.CATALOG,
                uiText(Res.string.model_test_catalog_failed_value, "EmptyCatalog"))
        }
    }

    private fun publish(
        config: ModelSettings,
        stages: List<ModelTestStageResult>,
    ) {
        state.value = ModelTestState(
            running = true,
            configuration = config,
            stages = stages.toList(),
        )
    }

    // Stage messages are diagnostic snapshots; the page resolves its UiText feedback separately.
    private fun passed(stage: ModelTestStage) = ModelTestStageResult(stage, true, stage.name)

    private class ProbeFailure(
        val stage: ModelTestStage,
        val feedback: UiText,
    ) : IllegalStateException(feedback.resolve())

    private fun diagnostic(error: Exception, config: ModelSettings): String =
        redact((error as? ProbeFailure)?.message ?: error.toString(), config)

    private fun redact(detail: String, config: ModelSettings): String =
        if (config.apiKey.isBlank()) detail else detail.replace(config.apiKey, "[redacted]")

    private fun updateCatalog(request: Any, transform: (ModelCatalogState) -> ModelCatalogState) {
        catalogState.update { if (catalogRequest.load() === request) transform(it) else it }
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
