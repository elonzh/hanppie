package cn.elonzh.hanppie.ui.i18n

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleState
import kotlin.test.*
import kotlinx.coroutines.runBlocking
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
        assertFailsWith<IllegalArgumentException> { Localization.initialize("de","invalid") }
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
    @Test fun resourceParametersMatchAcrossLanguages() = runBlocking {
        suspend fun templates(language: String): Map<String, String> {
            Localization.initialize(language, language)
            val environment = getSystemResourceEnvironment()
            return Res.allStringResources.mapValues { getString(environment, it.value) }
        }
        val english = templates("en")
        val chinese = templates("zh")
        val parameter = Regex("%[1-9][0-9]*\\\$s")
        for ((name, template) in english) {
            assertEquals(parameter.findAll(template).map { it.value }.toSet(),
                parameter.findAll(chinese.getValue(name)).map { it.value }.toSet(), name)
            val arguments = Array<Any?>(9) { "argument-$it" }
            for (language in listOf("en", "zh")) {
                Localization.select(language)
                assertTrue(tr(Res.allStringResources.getValue(name), *arguments).isNotBlank(), name)
            }
        }
    }
}
