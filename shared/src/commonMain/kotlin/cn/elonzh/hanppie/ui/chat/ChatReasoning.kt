package cn.elonzh.hanppie.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Only readable reasoning supplied by the provider is passed to this view. */
@Composable
internal fun ChatReasoning(content: String, streaming: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().testTag("chat-reasoning")) {
        Row(Modifier.heightIn(min = 28.dp)
            .toggleable(expanded, role = Role.Button, onValueChange = { expanded = it })
            .semantics { stateDescription = tr(if (expanded) Res.string.chat_reasoning_expanded else Res.string.chat_reasoning_collapsed) }
            .testTag("chat-reasoning-toggle").padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(tr(if (streaming) Res.string.chat_reasoning_streaming else Res.string.chat_reasoning),
                Modifier.padding(end = 6.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, MiuixTheme.colorScheme.onSurfaceVariantSummary,
                Modifier.size(16.dp).rotate(if (expanded) 90f else 0f))
        }
        if (expanded) {
            SelectionContainer {
                Text(content, Modifier.fillMaxWidth().heightIn(max = 240.dp)
                    .verticalScroll(scroll).testTag("chat-reasoning-body")
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
