package cn.elonzh.hanppie.ui.robot.remote.input

import com.sun.jna.NativeLibrary
import java.awt.AWTEvent
import java.awt.Component
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Window-owned AWT relative capture. A successful cursor warp is required before accepting input. */
internal class DesktopRelativePointer(private val window: Window) : RelativePointer {
    override fun capture(onReady: () -> Unit, onMotion: (Double, Double) -> Unit,
        onButton: (Boolean, Boolean) -> Unit, onLost: () -> Unit): AutoCloseable {
        check(SwingUtilities.isEventDispatchThread())
        requirePointerEventAccess()
        check(window.isShowing && window.isFocused)
        val robot = Robot(window.graphicsConfiguration.device)
        val toolkit = Toolkit.getDefaultToolkit()
        val originalCursor = window.cursor
        val originalPosition = MouseInfo.getPointerInfo()?.location
        val location = window.locationOnScreen
        val centre = Point(location.x + window.width / 2, location.y + window.height / 2)
        var closed = false
        var ready = false
        var warping = true
        var warpStarted = System.nanoTime()
        lateinit var monitor: Timer
        lateinit var mouse: AWTEventListener
        lateinit var focus: WindowAdapter
        lateinit var geometry: ComponentAdapter
        fun close(lost: Boolean) {
            if (closed) return
            closed = true
            monitor.stop(); toolkit.removeAWTEventListener(mouse)
            window.removeWindowFocusListener(focus); window.removeComponentListener(geometry)
            window.cursor = originalCursor
            if (!lost && window.isFocused && originalPosition != null) runCatching { robot.mouseMove(originalPosition.x, originalPosition.y) }
            if (lost) onLost()
        }
        fun warp() {
            warping = true; warpStarted = System.nanoTime()
            robot.mouseMove(centre.x, centre.y)
        }
        mouse = AWTEventListener { event ->
            if (closed || event !is MouseEvent) return@AWTEventListener
            val component = event.source as? Component ?: return@AWTEventListener
            if (component !== window && !SwingUtilities.isDescendingFrom(component, window)) return@AWTEventListener
            if (!window.isFocused) { close(true); return@AWTEventListener }
            if (warping && event.xOnScreen == centre.x && event.yOnScreen == centre.y) {
                warping = false
                if (!ready) { ready = true; onReady() }
                event.consume(); return@AWTEventListener
            }
            if (!ready || warping) return@AWTEventListener
            when (event.id) {
                MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED -> {
                    val x = event.xOnScreen - centre.x; val y = event.yOnScreen - centre.y
                    if (x != 0 || y != 0) {
                        onMotion(x.toDouble(), y.toDouble())
                        runCatching { warp() }.onFailure { close(true) }
                    }
                }
                MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED -> {
                    if (event.button == MouseEvent.BUTTON1 || event.button == MouseEvent.BUTTON3)
                        onButton(event.button == MouseEvent.BUTTON1, event.id == MouseEvent.MOUSE_PRESSED)
                }
            }
            event.consume()
        }
        focus = object : WindowAdapter() { override fun windowLostFocus(event: WindowEvent) = close(true) }
        geometry = object : ComponentAdapter() {
            override fun componentMoved(event: ComponentEvent) = close(true)
            override fun componentResized(event: ComponentEvent) = close(true)
            override fun componentHidden(event: ComponentEvent) = close(true)
        }
        monitor = Timer(10) {
            if (closed) return@Timer
            if (!window.isFocused || !window.isShowing) { close(true); return@Timer }
            if (warping) {
                val current = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
                if (current == centre) {
                    warping = false
                    if (!ready) { ready = true; onReady() }
                } else if (System.nanoTime() - warpStarted > 250_000_000) close(true)
            }
        }
        try {
            toolkit.addAWTEventListener(mouse, AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK)
            window.addWindowFocusListener(focus); window.addComponentListener(geometry)
            window.cursor = toolkit.createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(), "hanppie-relative-pointer")
            warp(); monitor.start()
        } catch (error: Exception) { close(false); throw error }
        return AutoCloseable { close(false) }
    }
}

/** CoreGraphics' preflight only queries access; it never asks for or changes permission. */
private fun requirePointerEventAccess() {
    if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return
    val allowed = try {
        NativeLibrary.getInstance("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
            .getFunction("CGPreflightPostEventAccess").invoke(Byte::class.javaObjectType, emptyArray<Any>()) as Byte
    } catch (error: LinkageError) {
        throw IllegalStateException("Could not query pointer event access", error)
    }
    if (allowed.toInt() == 0) throw PointerAccessDenied()
}
