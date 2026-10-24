package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Host file/preferences feedback has one workbench owner; raw platform details remain unchanged. */
@Composable
internal fun HostFeedback(message: UiText, dismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("host-feedback"),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message.resolve(), Modifier.weight(1f).heightIn(max = 80.dp).verticalScroll(rememberScrollState())
            .testTag("host-feedback-message"), color = MiuixTheme.colorScheme.error, fontSize = 13.sp)
        Button(dismiss, Modifier.heightIn(min = 48.dp).widthIn(max = 180.dp).testTag("host-feedback-dismiss")) {
            Text(tr(Res.string.close))
        }
    }
}
