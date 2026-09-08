package cn.elonzh.hanppie.ui

import androidx.compose.ui.graphics.luminance
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class AppearanceTest {
    @Test fun defaultsUseUnmodifiedNativeColorsAndFollowSystem() {
        val settings = AppearanceSettings.decode(null)
        assertEquals(ThemePreset.NATIVE, settings.preset)
        assertEquals(NightMode.SYSTEM, settings.nightMode)
        for (dark in listOf(false, true)) {
            val expected = if (dark) darkColorScheme() else lightColorScheme()
            val actual = appearanceColors(settings, dark)
            assertEquals(expected.primary, actual.primary)
            assertEquals(expected.background, actual.background)
            assertEquals(expected.surfaceContainer, actual.surfaceContainer)
            assertEquals(expected.onSurface, actual.onSurface)
        }
        assertFalse(NightMode.SYSTEM.isDark(false))
        assertTrue(NightMode.SYSTEM.isDark(true))
        assertFalse(NightMode.LIGHT.isDark(true))
        assertTrue(NightMode.DARK.isDark(false))
    }

    @Test fun changesRestorePresetModeAndBothCustomPalettes() {
        var saved: String? = null
        val controller = AppearanceController(persist = { saved = it })
        val custom = CustomPalette("#345678", "#FAFBFC", "#BADA55", "#111822")
        controller.update(AppearanceSettings(ThemePreset.CUSTOM, NightMode.DARK, custom))
        val restored = AppearanceController(AppearanceSettings.decode(saved))
        assertEquals(controller.settings, restored.settings)
        restored.update(restored.settings.copy(preset = ThemePreset.NATIVE))
        assertEquals(custom, restored.settings.custom)
        assertEquals(rgbColor(custom.lightAccent), appearanceColors(controller.settings, false).primary)
        assertEquals(rgbColor(custom.darkBackground), appearanceColors(controller.settings, true).background)
    }

    @Test fun invalidSavedSettingsRecoverAndInvalidEditsCannotBeApplied() {
        for (saved in listOf("", "9|CUSTOM|DARK", "1|UNKNOWN|DARK|#112233|#FFFFFF|#123456|#000000",
            "1|CUSTOM|DARK|oops|#FFFFFF|#123456|#000000")) {
            assertEquals(AppearanceSettings(), AppearanceSettings.decode(saved))
        }
        val controller = AppearanceController()
        assertFailsWith<IllegalArgumentException> {
            controller.update(AppearanceSettings(custom = CustomPalette(lightAccent = "#ZZZZZZ")))
        }
        assertEquals(AppearanceSettings(), controller.settings)
    }

    @Test fun customTextContrastsWithExtremeAndMidtoneColors() {
        for (hex in listOf("#000000", "#FFFFFF", "#808080", "#ED7B43", "#103F65")) {
            val color = rgbColor(hex)
            val text = contrastingText(color)
            val contrast = (maxOf(color.luminance(), text.luminance()) + .05f) /
                (minOf(color.luminance(), text.luminance()) + .05f)
            assertTrue(contrast >= 4.5f, "$hex contrast was $contrast")
        }
    }
}
