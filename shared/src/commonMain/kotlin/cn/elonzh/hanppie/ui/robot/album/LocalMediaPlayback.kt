package cn.elonzh.hanppie.ui.robot.album

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import io.github.vinceglb.filekit.path
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.*
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun LocalMediaPlayback(file: LocalRobotMedia, modifier: Modifier = Modifier) = key(file.file.path) {
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0.0) }
    var startAt by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    var failed by remember { mutableStateOf(false) }
    Column(modifier.testTag("local-media-player")) {
        LocalVideoSurface(file.file.path, playing, startAt, Modifier.weight(1f).fillMaxWidth(),
            { position = it }, { duration = it }, { playing = false; startAt = 0.0 }, { failed = true; playing = false })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchIconButton(tr(if (playing) Res.string.media_preview_pause else Res.string.media_preview_play),
                if (playing) WorkbenchGlyph.PAUSE else WorkbenchGlyph.PLAY, {
                    startAt = if (duration > 0 && position >= duration - .1) 0.0 else position
                    failed = false; playing = !playing
                }, tag = "local-media-play")
            WorkbenchIconButton(tr(Res.string.media_preview_restart), WorkbenchGlyph.REFRESH,
                { position = 0.0; startAt = 0.0; failed = false; playing = true }, tag = "local-media-restart")
            Text(if (failed) tr(Res.string.media_preview_failed) else "${playbackTime(position)} / ${playbackTime(duration)}")
        }
    }
}

private fun playbackTime(seconds: Double): String = seconds.toInt().coerceAtLeast(0).let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }

@Composable
internal expect fun LocalVideoSurface(path: String, playing: Boolean, startAt: Double, modifier: Modifier,
    onPosition: (Double) -> Unit, onDuration: (Double) -> Unit, onEnded: () -> Unit, onError: () -> Unit)
