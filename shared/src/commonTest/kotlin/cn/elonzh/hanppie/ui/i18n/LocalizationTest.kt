package cn.elonzh.hanppie.ui.i18n

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleState
import kotlin.test.*
import org.jetbrains.compose.resources.*

class LocalizationTest {
    @AfterTest fun reset() { Localization.initialize("zh",null) }
    @Test fun systemSelectionPersistenceAndPlaceholders() {
        var saved: String? = null
        Localization.initialize("en-GB",null) { saved=it }
        assertEquals("Settings",tr(Res.string.settings))
        Localization.select("zh")
        assertEquals("zh",saved)
        assertEquals("设置",tr(Res.string.settings))
        Localization.initialize("en-US",saved)
        assertFalse(Localization.english)
        Localization.select("system")
        assertTrue(Localization.english)
        Localization.initialize("zh-TW","system")
        assertFalse(Localization.english)
        assertEquals("zh-Hant-TW", Localization.languageTag)
        assertEquals("設定", tr(Res.string.settings))
        assertFailsWith<IllegalArgumentException> { Localization.initialize("de","invalid") }
    }
    @Test fun scriptOverridesRegionAndExplicitChoiceSurvivesSystemChanges() {
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-CN", "zh_HK", "zh-HK-x-hans")) {
            Localization.initialize(tag, "system")
            assertEquals("zh-Hant-TW", Localization.languageTag, tag)
        }
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-HK", "zh-CN-x-hant")) {
            Localization.initialize(tag, "system")
            assertEquals("zh-CN", Localization.languageTag, tag)
        }
        Localization.initialize("zh-TW", "zh")
        assertEquals("设置", tr(Res.string.settings))
        var saved: String? = null
        Localization.initialize("en-US", null) { saved = it }
        Localization.select("zh-Hant")
        assertEquals("zh-Hant", saved)
        Localization.initialize("zh-CN", saved)
        assertEquals("設定", tr(Res.string.settings))
        Localization.select("system")
        assertEquals("设置", tr(Res.string.settings))
    }

    @Test fun traditionalCatalogUpdatesExistingStateAndRejectsInvalidChoicesWithoutMutation() {
        Localization.initialize("en-US", "zh")
        val state = ConsoleState().lost("raw device error")
        Localization.select("zh-Hant")
        assertEquals("連線失效", state.status)
        assertEquals("raw device error", state.error)
        assertEquals("使用 {1}", tr(Res.string.use_value, "{1}"))
        assertFailsWith<IllegalArgumentException> { Localization.select("zh-Hans") }
        assertFailsWith<IllegalArgumentException> { Localization.initialize("en-US", "invalid") }
        assertEquals("zh-Hant", Localization.choice)
        assertEquals("en-US", Localization.systemLanguageTag)
    }
    @Test fun liveStateUsesResourceIdentityAcrossLanguageChanges() {
        Localization.initialize("zh",null)
        val state = ConsoleState().lost("raw device error")
        Localization.select("en")
        assertEquals("Connection lost", state.status)
        assertEquals(Res.string.connection_lost, state.statusMessage.resource)
        assertEquals("raw device error", state.error)
        assertEquals("Use {1}",tr(Res.string.use_value,"{1}"))
    }

    @OptIn(ExperimentalResourceApi::class)
    @Test fun resourceParametersMatchAcrossLanguages() {
        fun templates(language: String): Map<String, String> {
            Localization.initialize("en-US", language)
            // Numeric sentinels work with both %s and %d, through the real native formatter.
            val placeholders = Array<Any?>(9) { 12345001 + it }
            return Res.allStringResources.mapValues { (name, resource) ->
                tr(resource, *placeholders).also { assertTrue(it.isNotBlank(), "$language: $name") }
            }
        }
        val english = templates("en")
        val translated = listOf("zh", "zh-Hant").associateWith { templates(it) }
        val parameter = Regex("1234500[1-9]")
        for ((name, template) in english) {
            for ((language, catalog) in translated) assertEquals(parameter.findAll(template).map { it.value }.groupingBy { it }.eachCount(),
                parameter.findAll(catalog.getValue(name)).map { it.value }.groupingBy { it }.eachCount(), "$language: $name")
        }
    }
}
