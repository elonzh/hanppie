package cn.elonzh.hanppie.ui

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun WorkbenchDialog(show: Boolean, onDismissRequest: () -> Unit, title: String,
                             summary: String? = null, content: @Composable () -> Unit) {
    WindowDialog(show = show, onDismissRequest = onDismissRequest, title = title,
        summary = summary, content = content)
}
