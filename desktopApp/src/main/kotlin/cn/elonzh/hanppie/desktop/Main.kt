package cn.elonzh.hanppie.desktop

import androidx.compose.ui.window.application
import cn.elonzh.hanppie.ui.app.DesktopWorkbench
import cn.elonzh.hanppie.ui.app.initializeDesktopPlatform
import java.awt.Taskbar
import javax.imageio.ImageIO

fun main() {
    initializeDesktopPlatform()
    // Gradle/IDE launches do not have a native bundle to supply the Dock icon.
    if (Taskbar.isTaskbarSupported()) {
        val taskbar = Taskbar.getTaskbar()
        if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
            val icon = checkNotNull(Thread.currentThread().contextClassLoader.getResource("icons/hanppie.png"))
            taskbar.iconImage = ImageIO.read(icon)
        }
    }
    application { DesktopWorkbench(onExit = ::exitApplication) }
}
