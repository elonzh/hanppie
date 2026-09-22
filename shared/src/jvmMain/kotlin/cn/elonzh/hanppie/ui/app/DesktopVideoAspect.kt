package cn.elonzh.hanppie.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import java.awt.Dimension
import java.awt.Insets
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlin.math.max
import kotlin.math.roundToInt

/** Native frame decorations are excluded from the video aspect ratio. */
internal fun videoWindowSize(size: Dimension, insets: Insets, aspect: Double, useHeight: Boolean): Dimension {
    val horizontal = insets.left + insets.right
    val vertical = insets.top + insets.bottom
    val desiredWidth = if (useHeight) (size.height - vertical) * aspect else (size.width - horizontal).toDouble()
    val width = max(desiredWidth, max(740.0 - horizontal, (480.0 - vertical) * aspect)).roundToInt()
    return Dimension(width + horizontal, (width / aspect).roundToInt() + vertical)
}

@Composable
internal fun DesktopVideoAspect(window: ComposeWindow, aspect: Double) {
    DisposableEffect(window, aspect) {
        var lastSize = window.size
        var adjusting = false
        fun constrain() {
            if (adjusting || window.isMinimized) return
            adjusting = true
            try {
                if (window.placement != WindowPlacement.Floating) window.placement = WindowPlacement.Floating
                val requested = window.size
                val useHeight = requested.width == lastSize.width && requested.height != lastSize.height
                val target = videoWindowSize(requested, window.insets, aspect, useHeight)
                lastSize = target
                if (requested != target) window.size = target
            } finally { adjusting = false }
        }
        val listener = object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = constrain()
            override fun componentShown(event: ComponentEvent) = constrain()
        }
        window.addComponentListener(listener)
        constrain()
        onDispose { window.removeComponentListener(listener) }
    }
}
