package cn.elonzh.hanppie.ui

import androidx.compose.material.MaterialTheme
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
internal fun WorkbenchTheme(content: @Composable () -> Unit) {
    // Miuix 0.9.3's shader path links an older Image.makeShader ABI.
    // Use its supported round-rect renderer with Compose 1.12 / Skia 150.
    CompositionLocalProvider(LocalSquircleEnabled provides false) {
        MiuixTheme(colors = lightColorScheme(primary = Color(0xff3868e8))) {
            MaterialTheme(colors = lightColors(primary = Color(0xff3868e8),
                background = Color(0xfff5f6f8), surface = Color.White), content = content)
        }
    }
}
