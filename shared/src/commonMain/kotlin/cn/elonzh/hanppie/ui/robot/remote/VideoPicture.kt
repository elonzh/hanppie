package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity

/** Native surfaces keep the source aspect; host framing crops only presentation, never saved media. */
@Composable
internal fun VideoPicture(videoWidth: Int, videoHeight: Int, fill: Boolean,
    modifier: Modifier = Modifier.fillMaxSize(), content: @Composable BoxScope.() -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val viewport = VideoViewport.forImage(with(density) { maxWidth.toPx() },
            with(density) { maxHeight.toPx() }, videoWidth, videoHeight, fill)
        Box(Modifier.requiredSize(with(density) { viewport.width.toDp() }, with(density) { viewport.height.toDp() }),
            contentAlignment = Alignment.Center, content = content)
    }
}
