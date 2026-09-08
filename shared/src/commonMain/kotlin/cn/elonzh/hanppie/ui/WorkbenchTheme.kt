package cn.elonzh.hanppie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme

private val ChappieColors = darkColorScheme(
    primary = Color(0xffed7b43), onPrimary = Color(0xff27150b),
    primaryVariant = Color(0xff493025), onPrimaryVariant = Color(0xffffb58a),
    primaryContainer = Color(0xffed7b43), onPrimaryContainer = Color(0xff27150b),
    disabledPrimary = Color(0xff493025), disabledOnPrimary = Color(0xffa98470),
    disabledPrimaryButton = Color(0xff493025), disabledOnPrimaryButton = Color(0xffa98470),
    secondary = Color(0xff344159), onSecondary = Color(0xffe6ebf2),
    secondaryVariant = Color(0xff29334a), onSecondaryVariant = Color(0xffe6ebf2),
    secondaryContainer = Color(0xff344159), onSecondaryContainer = Color(0xffa9b3c4),
    secondaryContainerVariant = Color(0xff29334a), onSecondaryContainerVariant = Color(0xffa9b3c4),
    disabledSecondary = Color(0xff1b2230), disabledOnSecondary = Color(0xff7c8ba5),
    disabledSecondaryVariant = Color(0xff1b2230), disabledOnSecondaryVariant = Color(0xff7c8ba5),
    tertiaryContainer = Color(0xff173b4a), onTertiaryContainer = Color(0xff83d5f3),
    background = Color(0xff11151d), onBackground = Color(0xffe6ebf2),
    onBackgroundVariant = Color(0xffa9b3c4),
    surface = Color(0xff11151d), onSurface = Color(0xffe6ebf2),
    surfaceVariant = Color(0xff161c27), onSurfaceSecondary = Color(0xffa9b3c4),
    onSurfaceVariantSummary = Color(0xffa9b3c4), onSurfaceVariantActions = Color(0xffacb9d2),
    surfaceContainer = Color(0xff1b2230), onSurfaceContainer = Color(0xffe6ebf2),
    onSurfaceContainerVariant = Color(0xffa9b3c4),
    surfaceContainerHigh = Color(0xff29334a), onSurfaceContainerHigh = Color(0xffa9b3c4),
    surfaceContainerHighest = Color(0xff344159), onSurfaceContainerHighest = Color(0xffe6ebf2),
    outline = Color(0xff46536c), dividerLine = Color(0xff46536c),
    error = Color(0xffffb4ab), onError = Color(0xff690005),
    errorContainer = Color(0xff73322c), onErrorContainer = Color(0xffffdad6),
)

internal fun appearanceColors(settings: AppearanceSettings, dark: Boolean): Colors {
    if (settings.preset == ThemePreset.NATIVE) return if (dark) darkColorScheme() else lightColorScheme()
    if (settings.preset == ThemePreset.CHAPPIE && dark) return ChappieColors
    val palette = when (settings.preset) {
        ThemePreset.CHAPPIE -> CustomPalette()
        ThemePreset.OCEAN -> CustomPalette("#286EA8", "#F2F7FC", "#72BCF5", "#111923")
        ThemePreset.FOREST -> CustomPalette("#32744A", "#F3F7F2", "#85C99E", "#131B16")
        else -> settings.custom
    }
    val accent = rgbColor(if (dark) palette.darkAccent else palette.lightAccent)
    val background = rgbColor(if (dark) palette.darkBackground else palette.lightBackground)
    val text = contrastingText(background)
    val surface = lerp(background, text, .045f)
    val raised = lerp(background, text, .09f)
    val muted = lerp(background, text, .72f)
    val disabled = lerp(background, text, .42f)
    val primaryText = contrastingText(accent)
    val primaryDim = lerp(background, accent, .24f)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = primaryText,
        primaryVariant = primaryDim, onPrimaryVariant = text,
        primaryContainer = accent, onPrimaryContainer = primaryText,
        disabledPrimary = primaryDim, disabledOnPrimary = disabled,
        disabledPrimaryButton = primaryDim, disabledOnPrimaryButton = disabled,
        secondary = raised, onSecondary = text,
        secondaryVariant = raised, onSecondaryVariant = text,
        secondaryContainer = raised, onSecondaryContainer = muted,
        secondaryContainerVariant = raised, onSecondaryContainerVariant = muted,
        disabledSecondary = surface, disabledOnSecondary = disabled,
        disabledSecondaryVariant = surface, disabledOnSecondaryVariant = disabled,
        tertiaryContainer = primaryDim, onTertiaryContainer = text,
        background = background, onBackground = text, onBackgroundVariant = muted,
        surface = background, onSurface = text, surfaceVariant = surface,
        onSurfaceSecondary = muted, onSurfaceVariantSummary = muted, onSurfaceVariantActions = muted,
        surfaceContainer = surface, onSurfaceContainer = text, onSurfaceContainerVariant = muted,
        surfaceContainerHigh = raised, onSurfaceContainerHigh = muted,
        surfaceContainerHighest = raised, onSurfaceContainerHighest = text,
        outline = lerp(background, text, .32f), dividerLine = lerp(background, text, .16f),
    )
}

internal fun rgbColor(hex: String): Color = Color(0xff000000L or hex.removePrefix("#").toLong(16))
internal fun contrastingText(color: Color): Color =
    if (color.luminance() > .179f) Color.Black else Color.White

@Composable internal expect fun ApplyPlatformAppearance(dark: Boolean)

@Composable
internal fun WorkbenchTheme(
    appearance: AppearanceController = remember { AppearanceController() },
    content: @Composable () -> Unit,
) {
    val settings = appearance.settings
    val dark = settings.nightMode.isDark(isSystemInDarkTheme())
    val colors = remember(settings, dark) { appearanceColors(settings, dark) }
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
