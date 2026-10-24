package cn.elonzh.hanppie.ui.i18n

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleState
import kotlin.test.*
import org.jetbrains.compose.resources.*

class LocalizationTest {
    @AfterTest fun reset() { Localization.initialize("zh", null) }

    @Test fun systemSelectionPersistenceAndPlaceholders() {
        var saved: String? = null
        Localization.initialize("en-GB", null) { saved = it }
        assertEquals("Settings", tr(Res.string.settings))
        Localization.select("zh")
        assertEquals("zh", saved)
        assertEquals("设置", tr(Res.string.settings))
        Localization.initialize("en-US", saved)
        assertFalse(Localization.english)
        Localization.select("system")
        assertTrue(Localization.english)
        assertEquals("Use {1}", tr(Res.string.use_value, "{1}"))
    }

    @Test fun allChineseSystemRegionsUseSimplifiedChinese() {
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-CN", "zh_HK", "zh-Hans-HK")) {
            Localization.initialize(tag, "system")
            assertEquals("zh-CN", Localization.languageTag, tag)
            assertEquals("设置", tr(Res.string.settings), tag)
        }
    }

    @Test fun otherSystemLanguagesUseEnglishAndExplicitChoiceSurvivesSystemChanges() {
        for (tag in listOf("en", "en-GB", "ja-JP", "de_AT", "es-MX", "fr-CA", "ko-KR", "ru-RU", "it-IT")) {
            Localization.initialize(tag, "system")
            assertEquals("en-US", Localization.languageTag, tag)
            assertEquals("Settings", tr(Res.string.settings), tag)
        }
        Localization.initialize("fr-CA", "zh")
        assertEquals("设置", tr(Res.string.settings))
        Localization.initialize("zh-TW", "en")
        assertEquals("Settings", tr(Res.string.settings))
    }

    @Test fun removedAndInvalidChoicesAreRejectedWithoutMutation() {
        var saved: String? = null
        Localization.initialize("en-US", "zh") { saved = it }
        for (choice in listOf("zh-Hant", "ja", "de", "es", "fr", "ko", "ru", "zh-Hans", "invalid")) {
            assertFailsWith<IllegalArgumentException> { Localization.select(choice) }
            assertFailsWith<IllegalArgumentException> { Localization.initialize("zh-CN", choice) }
            assertEquals("zh", Localization.choice)
            assertEquals("en-US", Localization.systemLanguageTag)
            assertNull(saved)
        }
    }

    @Test fun liveStateUsesResourceIdentityAcrossLanguageChanges() {
        Localization.initialize("zh", null)
        val state = ConsoleState().lost("raw device error")
        Localization.select("en")
        assertEquals("Connection lost", state.status)
        assertEquals(Res.string.connection_lost, state.statusMessage.resource)
        assertEquals(listOf("raw device error"), state.error?.arguments)
        assertEquals(tr(Res.string.error_value, "raw device error"), state.error?.resolve())
        Localization.select("zh")
        assertEquals("连接失效", state.status)
        assertEquals("使用 {1}", tr(Res.string.use_value, "{1}"))
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
        val translated = AppLanguage.entries.filter { it != AppLanguage.ENGLISH }.associate { it.preference to templates(it.preference) }
        val parameter = Regex("1234500[1-9]")
        for ((name, template) in english) {
            for ((language, catalog) in translated) assertEquals(parameter.findAll(template).map { it.value }.groupingBy { it }.eachCount(),
                parameter.findAll(catalog.getValue(name)).map { it.value }.groupingBy { it }.eachCount(), "$language: $name")
        }
    }
}
