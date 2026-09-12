package cn.elonzh.hanppie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.Colors
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun appearanceColors(dark: Boolean): Colors =
    if (dark) GraphiteOrangeDarkColors else GraphiteOrangeLightColors

@Composable internal expect fun ApplyPlatformAppearance(dark: Boolean)

@Composable
internal fun WorkbenchTheme(
    appearance: AppearanceController = remember { AppearanceController() },
    content: @Composable () -> Unit,
) {
    val settings = appearance.settings
    val dark = settings.nightMode.isDark(isSystemInDarkTheme())
    val colors = remember(dark) { appearanceColors(dark) }
    ApplyPlatformAppearance(colors.background.luminance() < .179f)
    // Miuix 0.9.3's shader path links an older Skia ABI; use its round-rect renderer.
    CompositionLocalProvider(LocalSquircleEnabled provides false, LocalAppearance provides appearance) {
        MiuixTheme(colors = colors) {
            CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onBackground) {
                Box(Modifier.background(MiuixTheme.colorScheme.background)) { content() }
            }
        }
    }
}
