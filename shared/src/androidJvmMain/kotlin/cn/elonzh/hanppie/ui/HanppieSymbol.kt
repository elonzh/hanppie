package cn.elonzh.hanppie.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Exported from the single SVG masters in assets/brand/graphite-orange/source/icons. */
internal enum class HanppieSymbol(val resource: DrawableResource) {
    Video(Res.drawable.hanppie_video), VideoOff(Res.drawable.hanppie_video_off),
    Speaker(Res.drawable.hanppie_speaker), Muted(Res.drawable.hanppie_muted),
    Camera(Res.drawable.hanppie_camera), Record(Res.drawable.hanppie_record), Stop(Res.drawable.hanppie_stop),
    Crosshair(Res.drawable.hanppie_crosshair), Back(Res.drawable.hanppie_back),
    Search(Res.drawable.hanppie_search), Connect(Res.drawable.hanppie_connect), File(Res.drawable.hanppie_file),
    Battery(Res.drawable.hanppie_battery), Signal(Res.drawable.hanppie_signal), Packets(Res.drawable.hanppie_packets),
}

@Composable
internal fun HanppieIcon(symbol: HanppieSymbol, tint: Color = MiuixTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier) {
    Icon(painterResource(symbol.resource), contentDescription = null, modifier = modifier.size(24.dp), tint = tint)
}
