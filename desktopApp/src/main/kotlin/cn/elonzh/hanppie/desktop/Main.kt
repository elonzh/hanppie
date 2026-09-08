package cn.elonzh.hanppie.desktop

import androidx.compose.ui.window.application
import cn.elonzh.hanppie.ui.DesktopWorkbench

fun main() = application {
    DesktopWorkbench(onExit = ::exitApplication)
}
