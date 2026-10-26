package cn.elonzh.hanppie.ui.robot.album

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun LocalVideoSurface(path: String, playing: Boolean, startAt: Double, modifier: Modifier,
    onPosition: (Double) -> Unit, onDuration: (Double) -> Unit, onEnded: () -> Unit, onError: () -> Unit) {
    var image by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    val position by rememberUpdatedState(onPosition)
    val duration by rememberUpdatedState(onDuration)
    val ended by rememberUpdatedState(onEnded)
    val error by rememberUpdatedState(onError)
    LaunchedEffect(path, playing, startAt) {
        var active = true
        var player: DesktopFilePlayback? = null
        val frames = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.CONFLATED)
        try {
            val scope = this
            launch { for (bytes in frames) image = org.jetbrains.skia.Image.makeRaster(
                org.jetbrains.skia.ImageInfo(640, 360, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE), bytes, 640 * 4).toComposeImageBitmap() }
            withContext(Dispatchers.IO) { player = DesktopFilePlayback(path, startAt, playing,
                { bytes -> if (active) frames.trySend(bytes) },
                { scope.launch { if (active) position(it) } }, { scope.launch { if (active) duration(it) } },
                { scope.launch { if (active) ended() } }, { scope.launch { if (active) error() } }) }
            kotlinx.coroutines.awaitCancellation()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error() }
        finally { active = false; frames.close(); player?.close() }
    }
    Box(modifier.background(Color.Black)) { image?.let { Image(it, null, Modifier.fillMaxSize().testTag("local-media-video-frame"), contentScale = ContentScale.Fit) } }
}
