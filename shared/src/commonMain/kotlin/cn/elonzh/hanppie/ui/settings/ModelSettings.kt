package cn.elonzh.hanppie.ui.settings

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.enter_a_model_and_api_key
import cn.elonzh.hanppie.resources.enter_an_https_api_endpoint
import cn.elonzh.hanppie.ui.i18n.tr
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.Serializable

@Serializable
internal data class ModelSettings(
    val provider: ModelProviderPreset = ModelProviderPreset.DASHSCOPE,
    val endpoint: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    val model: String = "qwen3.8-flash",
    val apiKey: String = "",
) {
    val llModel: LLModel get() = ModelCatalog.resolve(provider, model)

    fun validate() {
        val validEndpoint = runCatching { Url(endpoint) }.getOrNull()?.let { url ->
            url.protocolOrNull == URLProtocol.HTTPS &&
                url.host.isNotBlank() &&
                url.user == null &&
                url.password == null &&
                url.parameters.isEmpty() &&
                url.fragment.isEmpty() &&
                !url.trailingQuery
        } == true
        require(validEndpoint) { tr(Res.string.enter_an_https_api_endpoint) }
        require(model.isNotBlank() && apiKey.isNotBlank()) { tr(Res.string.enter_a_model_and_api_key) }
    }

    override fun toString() = "ModelSettings(provider=$provider, endpoint=$endpoint, model=$model, apiKey=[redacted])"
}

@Serializable
internal enum class ModelProviderPreset(
    val displayName: String,
    val defaultEndpoint: String,
    val catalogPath: String,
) {
    DASHSCOPE("DashScope", "https://dashscope.aliyuncs.com/compatible-mode/v1", "/api/v1/models"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "/v1/models"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "/v1/models"),
    MIMO("MiMo", "https://api.xiaomimimo.com/v1", "/v1/models"),
    CUSTOM("Custom", "https://api.openai.com/v1", "/v1/models"),
}

/** Maintained model evidence. Unknown model IDs keep safe baseline capabilities and unknown limits. */
internal object ModelCatalog {
    private val textTools = listOf(
        LLMCapability.Temperature,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Completion,
        LLMCapability.OpenAIEndpoint.Completions,
    )
    private val visionTools = textTools + LLMCapability.Vision.Image
    private val structuredVisionTools = visionTools + LLMCapability.Schema.JSON.Standard

    val providers: List<ModelProviderPreset> = ModelProviderPreset.entries
    val models: Map<ModelProviderPreset, List<LLModel>> = mapOf(
        ModelProviderPreset.DASHSCOPE to listOf(
            LLModel(LLMProvider.Alibaba, "qwen3.8-flash", structuredVisionTools + LLMCapability.Thinking, 1_000_000, null),
            LLModel(LLMProvider.Alibaba, "qwen3.8-max", structuredVisionTools + LLMCapability.Thinking, 1_000_000, null),
            LLModel(LLMProvider.Alibaba, "qwen3.5-plus", structuredVisionTools + LLMCapability.Thinking, 1_000_000, null),
            LLModel(LLMProvider.Alibaba, "deepseek-v4-flash", textTools + LLMCapability.Thinking, null, null),
        ),
        ModelProviderPreset.OPENAI to listOf(
            LLModel(LLMProvider.OpenAI, "gpt-5.4-mini", structuredVisionTools + LLMCapability.Thinking, 400_000, 128_000),
            LLModel(LLMProvider.OpenAI, "gpt-5.4", structuredVisionTools + LLMCapability.Thinking, 1_050_000, 128_000),
        ),
        ModelProviderPreset.DEEPSEEK to listOf(
            LLModel(LLMProvider.DeepSeek, "deepseek-v4-flash", textTools + LLMCapability.Thinking, null, null),
            LLModel(LLMProvider.DeepSeek, "deepseek-v4-pro", textTools + LLMCapability.Thinking, null, null),
        ),
        ModelProviderPreset.MIMO to listOf(
            LLModel(LLMProvider("mimo", "MiMo"), "mimo-v2.5", structuredVisionTools + LLMCapability.Thinking, 1_000_000, null),
            LLModel(LLMProvider("mimo", "MiMo"), "mimo-v2.5-pro", textTools + LLMCapability.Thinking, null, null),
        ),
    )

    fun defaults(provider: ModelProviderPreset): ModelSettings = ModelSettings(
        provider = provider,
        endpoint = provider.defaultEndpoint,
        model = models[provider]?.firstOrNull()?.id.orEmpty(),
    )

    fun resolve(provider: ModelProviderPreset, id: String): LLModel = models[provider]
        ?.firstOrNull { it.id == id }
        ?: LLModel(koogProvider(provider), id, textTools, null, null)

    private fun koogProvider(provider: ModelProviderPreset): LLMProvider = when (provider) {
        ModelProviderPreset.DASHSCOPE -> LLMProvider.Alibaba
        ModelProviderPreset.OPENAI -> LLMProvider.OpenAI
        ModelProviderPreset.DEEPSEEK -> LLMProvider.DeepSeek
        ModelProviderPreset.MIMO -> LLMProvider("mimo", "MiMo")
        ModelProviderPreset.CUSTOM -> LLMProvider("custom", "Custom")
    }
}
