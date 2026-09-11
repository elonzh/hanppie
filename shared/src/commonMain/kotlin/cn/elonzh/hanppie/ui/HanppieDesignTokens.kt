package cn.elonzh.hanppie.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Graphite Orange is the branded surface layer over Miuix, not a replacement component system. */
internal object HanppieDesignTokens {
    object Light {
        val Background = Color(0xfff3f5f7)
        val Surface = Color(0xffffffff)
        val SurfaceMuted = Color(0xffe9edf1)
        val Ink = Color(0xff20242b)
        val MutedInk = Color(0xff5c6670)
        val Accent = Color(0xffdf6b38)
        val Information = Color(0xff688b99)
        val Outline = Color(0xffb9c1c9)
        val Divider = Color(0xffd8dde3)
    }

    object Dark {
        val Background = Color(0xff11161c)
        val Surface = Color(0xff1c232b)
        val SurfaceRaised = Color(0xff27313a)
        val Ink = Color(0xffe7ecf1)
        val MutedInk = Color(0xffa8b1ba)
        val Accent = Color(0xffe0713c)
        val Information = Color(0xff6f929d)
        val Outline = Color(0xff46515c)
        val Divider = Color(0xff303944)
    }

    val CompactBreakpoint = 720.dp
    val PageMaxWidth = 1080.dp
    val PagePaddingCompact = 20.dp
    val PagePaddingExpanded = 28.dp
    val CardRadius = 20.dp
    val ControlRadius = 12.dp
    val TouchTarget = 48.dp
    val RemoteStick = 132.dp
    val RemoteEdgePadding = 16.dp
    const val RemoteHudAlpha = 0.90f
}

internal val GraphiteOrangeLightColors = lightColorScheme(
    primary = HanppieDesignTokens.Light.Accent,
    onPrimary = Color(0xff2b1308),
    primaryVariant = Color(0xfff2d8cc),
    onPrimaryVariant = Color(0xff713016),
    primaryContainer = HanppieDesignTokens.Light.Accent,
    onPrimaryContainer = Color(0xff2b1308),
    disabledPrimary = Color(0xffead0c4),
    disabledOnPrimary = Color(0xff86695c),
    disabledPrimaryButton = Color(0xffead0c4),
    disabledOnPrimaryButton = Color(0xff86695c),
    secondary = Color(0xffdde5e8),
    onSecondary = Color(0xff223038),
    secondaryVariant = Color(0xffe8eef0),
    onSecondaryVariant = Color(0xff364850),
    secondaryContainer = Color(0xffdde5e8),
    onSecondaryContainer = Color(0xff364850),
    secondaryContainerVariant = Color(0xffe8eef0),
    onSecondaryContainerVariant = Color(0xff4b5d65),
    disabledSecondary = Color(0xffeef1f4),
    disabledOnSecondary = Color(0xff89939d),
    disabledSecondaryVariant = Color(0xffeef1f4),
    disabledOnSecondaryVariant = Color(0xff89939d),
    tertiaryContainer = Color(0xffdce9ed),
    onTertiaryContainer = Color(0xff315763),
    background = HanppieDesignTokens.Light.Background,
    onBackground = HanppieDesignTokens.Light.Ink,
    onBackgroundVariant = HanppieDesignTokens.Light.MutedInk,
    surface = HanppieDesignTokens.Light.Background,
    onSurface = HanppieDesignTokens.Light.Ink,
    surfaceVariant = HanppieDesignTokens.Light.SurfaceMuted,
    onSurfaceSecondary = HanppieDesignTokens.Light.MutedInk,
    onSurfaceVariantSummary = HanppieDesignTokens.Light.MutedInk,
    onSurfaceVariantActions = Color(0xff46515b),
    surfaceContainer = HanppieDesignTokens.Light.Surface,
    onSurfaceContainer = HanppieDesignTokens.Light.Ink,
    onSurfaceContainerVariant = HanppieDesignTokens.Light.MutedInk,
    surfaceContainerHigh = Color(0xffeef1f4),
    onSurfaceContainerHigh = Color(0xff46515b),
    surfaceContainerHighest = Color(0xffe2e7eb),
    onSurfaceContainerHighest = HanppieDesignTokens.Light.Ink,
    outline = HanppieDesignTokens.Light.Outline,
    dividerLine = HanppieDesignTokens.Light.Divider,
    error = Color(0xffb3261e),
    onError = Color.White,
    errorContainer = Color(0xfff9dedc),
    onErrorContainer = Color(0xff410e0b),
)

internal val GraphiteOrangeDarkColors = darkColorScheme(
    primary = HanppieDesignTokens.Dark.Accent,
    onPrimary = Color(0xff2a1408),
    primaryVariant = Color(0xff4a2d20),
    onPrimaryVariant = Color(0xffffb995),
    primaryContainer = HanppieDesignTokens.Dark.Accent,
    onPrimaryContainer = Color(0xff2a1408),
    disabledPrimary = Color(0xff4a2d20),
    disabledOnPrimary = Color(0xffa98370),
    disabledPrimaryButton = Color(0xff4a2d20),
    disabledOnPrimaryButton = Color(0xffa98370),
    secondary = Color(0xff29343c),
    onSecondary = HanppieDesignTokens.Dark.Ink,
    secondaryVariant = Color(0xff243039),
    onSecondaryVariant = HanppieDesignTokens.Dark.Ink,
    secondaryContainer = Color(0xff29343c),
    onSecondaryContainer = HanppieDesignTokens.Dark.MutedInk,
    secondaryContainerVariant = Color(0xff243039),
    onSecondaryContainerVariant = HanppieDesignTokens.Dark.MutedInk,
    disabledSecondary = Color(0xff1a2128),
    disabledOnSecondary = Color(0xff77818a),
    disabledSecondaryVariant = Color(0xff1a2128),
    disabledOnSecondaryVariant = Color(0xff77818a),
    tertiaryContainer = Color(0xff24383f),
    onTertiaryContainer = Color(0xffa9c5ce),
    background = HanppieDesignTokens.Dark.Background,
    onBackground = HanppieDesignTokens.Dark.Ink,
    onBackgroundVariant = HanppieDesignTokens.Dark.MutedInk,
    surface = HanppieDesignTokens.Dark.Background,
    onSurface = HanppieDesignTokens.Dark.Ink,
    surfaceVariant = Color(0xff171d24),
    onSurfaceSecondary = HanppieDesignTokens.Dark.MutedInk,
    onSurfaceVariantSummary = HanppieDesignTokens.Dark.MutedInk,
    onSurfaceVariantActions = Color(0xffbdc7d0),
    surfaceContainer = HanppieDesignTokens.Dark.Surface,
    onSurfaceContainer = HanppieDesignTokens.Dark.Ink,
    onSurfaceContainerVariant = HanppieDesignTokens.Dark.MutedInk,
    surfaceContainerHigh = HanppieDesignTokens.Dark.SurfaceRaised,
    onSurfaceContainerHigh = HanppieDesignTokens.Dark.MutedInk,
    surfaceContainerHighest = Color(0xff303b45),
    onSurfaceContainerHighest = HanppieDesignTokens.Dark.Ink,
    outline = HanppieDesignTokens.Dark.Outline,
    dividerLine = HanppieDesignTokens.Dark.Divider,
    error = Color(0xffffb4ab),
    onError = Color(0xff690005),
    errorContainer = Color(0xff73322c),
    onErrorContainer = Color(0xffffdad6),
)
