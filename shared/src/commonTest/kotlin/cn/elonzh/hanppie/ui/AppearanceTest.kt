package cn.elonzh.hanppie.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
        assertEquals("2|DARK", saved)
        assertEquals(controller.settings, AppearanceSettings.decode(saved))
    }

    @Test fun legacyAppearanceKeepsOnlyItsNightMode() {
        val legacy = "1|OCEAN|LIGHT|#286EA8|#F2F7FC|#72BCF5|#111923"
        assertEquals(AppearanceSettings(NightMode.LIGHT), AppearanceSettings.decode(legacy))
        assertEquals(HanppieDesignTokens.Light.Accent, appearanceColors(dark = false).primary)
        assertEquals(HanppieDesignTokens.Light.Background, appearanceColors(dark = false).background)
    }

    @Test fun invalidSavedSettingsRecoverToSystemMode() {
        for (saved in listOf("", "2", "2|UNKNOWN", "9|DARK", "1|CUSTOM|UNKNOWN|#112233|#FFFFFF|#123456|#000000")) {
            assertEquals(AppearanceSettings(), AppearanceSettings.decode(saved))
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
