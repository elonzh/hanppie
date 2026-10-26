package cn.elonzh.hanppie.ui.robot.album

import android.widget.VideoView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

@Composable
internal actual fun LocalVideoSurface(path: String, playing: Boolean, startAt: Double, modifier: Modifier,
    onPosition: (Double) -> Unit, onDuration: (Double) -> Unit, onEnded: () -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val position by rememberUpdatedState(onPosition)
    val duration by rememberUpdatedState(onDuration)
    val ended by rememberUpdatedState(onEnded)
    val error by rememberUpdatedState(onError)
    var prepared by remember(path) { mutableStateOf(false) }
    val view = remember(path) { VideoView(context).apply {
        setOnPreparedListener { prepared = true; duration(it.duration / 1000.0) }
        setOnCompletionListener { ended() }
        setOnErrorListener { _, _, _ -> error(); true }
        setVideoPath(path)
    } }
    DisposableEffect(view) { onDispose { view.stopPlayback() } }
    LaunchedEffect(view, prepared, playing, startAt) {
        if (prepared) { view.seekTo((startAt * 1000).toInt()); if (playing) view.start() else view.pause() }
    }
    LaunchedEffect(view, prepared) { while (prepared) { position(view.currentPosition / 1000.0); delay(100) } }
    AndroidView(factory = { view }, modifier = modifier)
}
