@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.elonzh.hanppie.ui.robot.album

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.coroutines.delay
import platform.AVFoundation.*
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL

@Composable
internal actual fun LocalVideoSurface(path: String, playing: Boolean, startAt: Double, modifier: Modifier,
    onPosition: (Double) -> Unit, onDuration: (Double) -> Unit, onEnded: () -> Unit, onError: () -> Unit) {
    val player = remember(path) { AVPlayer.playerWithURL(NSURL.fileURLWithPath(path)) }
    val controller = remember(player) { AVPlayerViewController().apply { this.player = player; showsPlaybackControls = false } }
    val position by rememberUpdatedState(onPosition)
    val duration by rememberUpdatedState(onDuration)
    val ended by rememberUpdatedState(onEnded)
    val error by rememberUpdatedState(onError)
    DisposableEffect(player) { onDispose { player.pause(); player.replaceCurrentItemWithPlayerItem(null) } }
    LaunchedEffect(player, playing, startAt) {
        player.seekToTime(CMTimeMakeWithSeconds(startAt, 600))
        if (playing) player.play() else player.pause()
        while (true) {
            val time = CMTimeGetSeconds(player.currentTime())
            val total = player.currentItem?.let { CMTimeGetSeconds(it.duration) } ?: 0.0
            if (time.isFinite()) position(time)
            if (total.isFinite() && total > 0) { duration(total); if (playing && time >= total - .05) { ended(); break } }
            if (player.currentItem?.status == AVPlayerItemStatusFailed) { error(); break }
            delay(100)
        }
    }
    UIKitView(factory = { controller.view }, modifier = modifier)
}
