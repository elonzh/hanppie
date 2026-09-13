package cn.elonzh.hanppie.agent.provider

import cn.elonzh.hanppie.ui.settings.ModelProviderPreset
import cn.elonzh.hanppie.ui.settings.ModelSettings
import cn.elonzh.hanppie.ui.settings.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelDiscoveryTest {
    @Test
    fun providerCatalogUsesItsDocumentedPathOnTheConfiguredOrigin() {
        assertEquals(
            "https://workspace.cn-beijing.maas.aliyuncs.com/api/v1/models",
            modelCatalogUrl(ModelSettings(
                provider = ModelProviderPreset.DASHSCOPE,
                endpoint = "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            )),
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            modelCatalogUrl(ModelSettings(
                provider = ModelProviderPreset.DEEPSEEK,
                endpoint = ModelProviderPreset.DEEPSEEK.defaultEndpoint,
            )),
        )
        assertEquals("https://api.openai.com/v1/models",
            modelCatalogUrl(ModelCatalog.defaults(ModelProviderPreset.OPENAI)))
        assertEquals("https://api.xiaomimimo.com/v1/models",
            modelCatalogUrl(ModelCatalog.defaults(ModelProviderPreset.MIMO)))
        assertEquals("https://api.openai.com/v1/models",
            modelCatalogUrl(ModelCatalog.defaults(ModelProviderPreset.CUSTOM)))
    }

    @Test
    fun providerSpecificCatalogShapesProduceStableModelIds() {
        assertEquals(
            listOf("qwen-a", "qwen-b"),
            parseDiscoveredModelIds(
                """{"output":{"models":[{"model":"qwen-b"},{"model":"qwen-a"},{"model":"qwen-a"}]}}""",
                ModelProviderPreset.DASHSCOPE,
            ),
        )
        assertEquals(
            listOf("deepseek-a", "deepseek-b"),
            parseDiscoveredModelIds(
                """{"object":"list","data":[{"id":"deepseek-b"},{"id":"deepseek-a"}]}""",
                ModelProviderPreset.DEEPSEEK,
            ),
        )
        assertEquals(
            listOf("mimo-a", "mimo-b"),
            parseDiscoveredModelIds(
                """{"data":[{"id":"mimo-b"},{"id":"mimo-a"}]}""",
                ModelProviderPreset.MIMO,
            ),
        )
    }

    @Test
    fun everyNamedProviderHasAUsablePresetAndCustomKeepsLimitsUnknown() {
        ModelProviderPreset.entries.filterNot { it == ModelProviderPreset.CUSTOM }.forEach { provider ->
            val defaults = ModelCatalog.defaults(provider)
            assertEquals(provider, defaults.provider)
            assertEquals(provider.defaultEndpoint, defaults.endpoint)
            assertTrue(defaults.model.isNotBlank())
            if (provider != ModelProviderPreset.DEEPSEEK) assertNotNull(defaults.llModel.contextLength)
        }
        val custom = ModelCatalog.resolve(ModelProviderPreset.CUSTOM, "private-deployment")
        assertEquals("private-deployment", custom.id)
        assertEquals(null, custom.contextLength)
        assertEquals(null, custom.maxOutputTokens)
    }
}
