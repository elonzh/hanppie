package cn.elonzh.hanppie.ui.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import cn.elonzh.hanppie.ui.settings.SavedWindowPosition
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

/** Native screen coordinates and screen insets are platform capabilities, not shared UI policy. */
internal fun restoreDesktopWindowPosition(saved: SavedWindowPosition?): WindowPosition {
    val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val configuration = device.defaultConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        Rectangle(configuration.bounds).apply {
            x += insets.left; y += insets.top
            width -= insets.left + insets.right; height -= insets.top + insets.bottom
        }
    }
    return visibleWindowPosition(saved, screens)?.let { WindowPosition(it.x.dp, it.y.dp) }
        ?: WindowPosition.PlatformDefault
}

/** Preserve valid positions, including negative coordinates on secondary displays. */
internal fun visibleWindowPosition(saved: SavedWindowPosition?, screens: List<Rectangle>): SavedWindowPosition? {
    if (saved == null || !saved.x.isFinite() || !saved.y.isFinite()) return null
    val screen = screens.firstOrNull { it.contains(saved.x.toDouble(), saved.y.toDouble()) }
        ?: return null // The previous display disappeared: let the OS choose a visible location.
    return SavedWindowPosition(
        saved.x.coerceIn(screen.x.toFloat(), (screen.x + screen.width - 1120).coerceAtLeast(screen.x).toFloat()),
        saved.y.coerceIn(screen.y.toFloat(), (screen.y + screen.height - 658).coerceAtLeast(screen.y).toFloat()),
    )
}
