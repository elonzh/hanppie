package cn.elonzh.hanppie.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import cn.elonzh.hanppie.ui.design.GraphiteOrangeDarkColors
import cn.elonzh.hanppie.ui.design.GraphiteOrangeLightColors
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.appearanceColors
import kotlin.test.*

class AppearanceTest {
    @Test fun defaultsUseFixedGraphiteOrangeAndFollowSystem() {
        val settings = AppearanceSettings.decode(null)
        assertEquals(NightMode.SYSTEM, settings.nightMode)
        assertEquals(GraphiteOrangeLightColors, appearanceColors(dark = false))
        assertEquals(GraphiteOrangeDarkColors, appearanceColors(dark = true))
        assertFalse(NightMode.SYSTEM.isDark(false))
        assertTrue(NightMode.SYSTEM.isDark(true))
        assertFalse(NightMode.LIGHT.isDark(true))
        assertTrue(NightMode.DARK.isDark(false))
    }

    @Test fun nightModePersistsWithTheCurrentFormat() {
        var saved: String? = null
        val controller = AppearanceController(persist = { saved = it })
        controller.update(AppearanceSettings(NightMode.DARK))
        assertEquals("DARK", saved)
        assertEquals(controller.settings, AppearanceSettings.decode(saved))
    }

    @Test fun invalidSavedSettingsAreRejected() {
        for (saved in listOf("", "2|DARK", "UNKNOWN")) {
            assertFailsWith<IllegalArgumentException> { AppearanceSettings.decode(saved) }
        }
    }

    @Test fun fixedTextPairsMeetWcagAa() {
        val pairs = listOf(
            GraphiteOrangeLightColors.background to GraphiteOrangeLightColors.onBackground,
            GraphiteOrangeLightColors.background to GraphiteOrangeLightColors.onBackgroundVariant,
            GraphiteOrangeLightColors.primary to GraphiteOrangeLightColors.onPrimary,
            GraphiteOrangeDarkColors.background to GraphiteOrangeDarkColors.onBackground,
            GraphiteOrangeDarkColors.surfaceContainer to GraphiteOrangeDarkColors.onSurfaceContainerVariant,
            GraphiteOrangeDarkColors.primary to GraphiteOrangeDarkColors.onPrimary,
            HanppieDesignTokens.RemoteHudSurface to HanppieDesignTokens.RemoteHudContent,
            HanppieDesignTokens.RemoteHudSurface to HanppieDesignTokens.RemoteHudMuted,
        )
        pairs.forEach { (background, foreground) ->
            assertTrue(contrast(background, foreground) >= 4.5f, "$background / $foreground")
        }
    }

    private fun contrast(first: Color, second: Color): Float =
        (maxOf(first.luminance(), second.luminance()) + .05f) /
            (minOf(first.luminance(), second.luminance()) + .05f)
}
