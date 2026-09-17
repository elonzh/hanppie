package cn.elonzh.hanppie.ui.settings

import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelCatalogTest {
    @Test fun dashscopeIsTheDefaultProviderAndQwen38FlashTheDefaultModel() {
        val defaults = ModelSettings()
        assertEquals(ModelProviderPreset.DASHSCOPE, defaults.provider)
        assertEquals("qwen3.8-flash", defaults.model)
        assertEquals(defaults.provider.defaultEndpoint, defaults.endpoint)
    }

    @Test fun theFirstMaintainedDashscopeModelMatchesTheDefaultId() {
        val catalog = ModelCatalog.models.getValue(ModelProviderPreset.DASHSCOPE)
        assertEquals("qwen3.8-flash", catalog.first().id)
    }

    @Test fun knownModelsResolveToTheirMaintainedCapabilities() {
        val model = ModelCatalog.resolve(ModelProviderPreset.DASHSCOPE, "qwen3.8-flash")
        assertEquals("qwen3.8-flash", model.id)
        assertEquals(1_000_000, model.contextLength)
    }

    @Test fun theConfiguredThinkingDepthMapsToReasoningEffortAndDefaultsToUnset() {
        assertEquals(null, ThinkingDepth.MODEL_DEFAULT.effort)
        assertEquals(ReasoningEffort.NONE, ThinkingDepth.OFF.effort)
        assertEquals(ReasoningEffort.LOW, ThinkingDepth.LOW.effort)
        assertEquals(ReasoningEffort.MEDIUM, ThinkingDepth.MEDIUM.effort)
        assertEquals(ReasoningEffort.HIGH, ThinkingDepth.HIGH.effort)
        assertEquals(ThinkingDepth.MODEL_DEFAULT, ModelSettings().thinkingDepth)
    }

    @Test fun thinkingDepthRoundTripsAndOldRecordsFallBackToTheModelDefault() {
        val saved = SavedSettings(model = ModelSettings(thinkingDepth = ThinkingDepth.HIGH))
        val restored = settingsJson.decodeFromString<SavedSettings>(settingsJson.encodeToString(saved))
        assertEquals(ThinkingDepth.HIGH, restored.model.thinkingDepth)

        val legacy = """{"model":{"provider":"DASHSCOPE","endpoint":"https://example.test/v1","model":"m","apiKey":"k"}}"""
        assertEquals(ThinkingDepth.MODEL_DEFAULT,
            settingsJson.decodeFromString<SavedSettings>(legacy).model.thinkingDepth)
    }

    @Test fun unknownModelIdsKeepUnknownLimitsInsteadOfFailing() {
        val model = ModelCatalog.resolve(ModelProviderPreset.DASHSCOPE, "not-a-catalog-model")
        assertEquals("not-a-catalog-model", model.id)
        assertEquals(null, model.contextLength)
    }
}
