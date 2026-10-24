package cn.elonzh.hanppie.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun WorkbenchDialog(show: Boolean, onDismissRequest: () -> Unit, title: String,
                             summary: String? = null, onDismissFinished: (() -> Unit)? = null,
                             content: @Composable () -> Unit) {
    WindowDialog(show = show, onDismissRequest = onDismissRequest, title = title.takeIf { summary == null },
        onDismissFinished = onDismissFinished) {
        // Summary dialogs share one scrolling surface so context cannot compress their actions.
        // Form/list dialogs own their bounded scrolling content and keep the library header.
        if (summary == null) content() else Column(Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState()).testTag("workbench-dialog-body")) {
            Text(title, Modifier.fillMaxWidth().padding(bottom = 12.dp),
                fontSize = MiuixTheme.textStyles.title4.fontSize, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, color = DialogDefaults.titleColor())
            Text(summary, Modifier.fillMaxWidth().padding(bottom = 12.dp),
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                textAlign = TextAlign.Center, color = DialogDefaults.summaryColor())
            content()
        }
    }
}
