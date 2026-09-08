package cn.elonzh.hanppie.desktop

import kotlin.test.*

class LocalizationTest {
    @AfterTest fun reset() { Localization.initialize("zh",null) }
    @Test fun systemSelectionPersistenceAndPlaceholders() {
        var saved: String? = null
        Localization.initialize("en-GB",null) { saved=it }
        assertEquals("Settings",tr("设置"))
        assertEquals("Gear 3 · Creep",tr("{0} 档 · 缓行",3))
        Localization.select("zh")
        assertEquals("zh",saved)
        assertEquals("设置",tr("设置"))
        Localization.initialize("en-US",saved)
        assertFalse(Localization.english)
        Localization.select("system")
        assertTrue(Localization.english)
        Localization.initialize("zh-TW","system")
        assertFalse(Localization.english)
        Localization.initialize("de","invalid")
        assertTrue(Localization.english)
    }
    @Test fun catalogHasMatchingParametersAndDoesNotRewriteUserText() {
        val parameters=Regex("\\{\\d+\\}")
        for ((zh,en) in Localization.messages) {
            assertEquals(parameters.findAll(zh).map { it.value }.toSet(),parameters.findAll(en).map { it.value }.toSet(),zh)
            assertTrue(en.isNotBlank(),zh)
        }
        Localization.select("en")
        assertEquals("print('用户源码')",tr("print('用户源码')"))
        assertEquals("Use {1}",tr("使用 {0}","{1}"))
    }
}
