package cn.elonzh.hanppie.ui.settings

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

    @Test fun unknownModelIdsKeepUnknownLimitsInsteadOfFailing() {
        val model = ModelCatalog.resolve(ModelProviderPreset.DASHSCOPE, "not-a-catalog-model")
        assertEquals("not-a-catalog-model", model.id)
        assertEquals(null, model.contextLength)
    }
}
