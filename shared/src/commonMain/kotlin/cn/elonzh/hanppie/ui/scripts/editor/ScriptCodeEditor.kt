package cn.elonzh.hanppie.ui.scripts.editor

import cn.elonzh.hanppie.ui.code.rememberPythonHighlight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ScriptCodeEditor(
    field: MutableState<TextFieldValue>,
    enabled: Boolean,
    onChange: (TextFieldValue) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    history: CodeHistory = remember { CodeHistory(field.value) },
) {
    val value = field.value
    val focus = remember { FocusRequester() }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    // Audio insertion and other external edits join the same history as keyboard input.
    LaunchedEffect(value) { history.record(value) }
    fun update(next: TextFieldValue) {
        if (!enabled) return
        history.record(field.value)
        history.record(next)
        onChange(next)
    }
    fun undo() { if (enabled) { history.record(field.value); onChange(history.undo()); focus.requestFocus() } }
    fun redo() { if (enabled) { history.record(field.value); onChange(history.redo()); focus.requestFocus() } }
    fun find(backwards: Boolean = false) {
        val current = field.value
        findCode(current.text, query, if (backwards) current.selection.min else current.selection.max, backwards)
            ?.let { update(current.copy(selection = it, composition = null)) }
    }
    val colors = MiuixTheme.colorScheme
    val highlight = rememberPythonHighlight(value.selection.end)
    val style = TextStyle(color = colors.onSurface, fontFamily = FontFamily.Monospace,
        fontSize = 14.sp, lineHeight = 23.sp)
    Column(modifier) {
        WorkbenchDialog(
            show = searchOpen,
            onDismissRequest = { searchOpen = false; focus.requestFocus() },
            title = tr(Res.string.editor_find),
        ) {
            Column(Modifier.fillMaxWidth().testTag("editor-search"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextField(query, { query = it }, label = tr(Res.string.editor_find), singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("editor-query"))
                TextField(replacement, { replacement = it }, label = tr(Res.string.editor_replace), singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("editor-replacement"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button({ find(true) }, enabled = enabled && query.isNotEmpty()) { Text(tr(Res.string.editor_previous)) }
                    Button({ find() }, enabled = enabled && query.isNotEmpty()) { Text(tr(Res.string.editor_next)) }
                    Button({
                        val current = field.value
                        if (query.isNotEmpty() && current.text.substring(current.selection.min, current.selection.max) == query) {
                            val start = current.selection.min
                            update(TextFieldValue(current.text.replaceRange(start, current.selection.max, replacement),
                                androidx.compose.ui.text.TextRange(start + replacement.length)))
                        } else find()
                    }, enabled = enabled && query.isNotEmpty(), modifier = Modifier.testTag("editor-replace")) {
                        Text(tr(Res.string.editor_replace))
                    }
                    Button({ searchOpen = false; focus.requestFocus() }) { Text(tr(Res.string.close)) }
                }
                if (query.isNotEmpty() && !value.text.contains(query)) {
                    Text(tr(Res.string.editor_no_matches), color = colors.onSurfaceVariantSummary)
                } else if (query.isNotEmpty() && value.text.substring(value.selection.min, value.selection.max) == query) {
                    val position = value.selection.min
                    val lineStart = value.text.lastIndexOf('\n', position - 1) + 1
                    val lineEnd = value.text.indexOf('\n', position).let { if (it < 0) value.text.length else it }
                    Text(tr(Res.string.editor_position, value.text.take(position).count { it == '\n' } + 1,
                        position - lineStart + 1), color = colors.onSurfaceVariantSummary, fontSize = 12.sp)
                    Text(value.text.substring(lineStart, lineEnd), fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport = maxWidth
            val viewportHeight = maxHeight
            val vertical = rememberScrollState()
            val horizontal = rememberScrollState()
            val lines = remember(value.text) { value.text.count { it == '\n' } + 1 }
            val gutterWidth = (lines.toString().length * 10 * LocalDensity.current.fontScale + 20).dp
            Row(Modifier.fillMaxSize().verticalScroll(vertical)) {
                Text((1..lines).joinToString("\n"),
                    Modifier.width(gutterWidth).padding(end = 10.dp).testTag("editor-line-numbers"),
                    style = style.copy(color = colors.onSurfaceVariantSummary, textAlign = androidx.compose.ui.text.style.TextAlign.End))
                BasicTextField(
                    value = value,
                    onValueChange = { update(autoIndent(field.value, it)) },
                    enabled = enabled,
                    textStyle = style,
                    cursorBrush = SolidColor(colors.primary),
                    visualTransformation = highlight,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.widthIn(min = (viewport - gutterWidth).coerceAtLeast(48.dp))
                        .heightIn(min = viewportHeight).horizontalScroll(horizontal).testTag("script-editor").focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (!enabled || event.type != KeyEventType.KeyDown) false else {
                                val command = event.isCtrlPressed || event.isMetaPressed
                                when {
                                    command && event.key == Key.Z -> { if (event.isShiftPressed) redo() else undo(); true }
                                    command && event.key == Key.Y -> { redo(); true }
                                    command && event.key == Key.F -> { searchOpen = true; true }
                                    command && event.key == Key.S -> { onSave(); true }
                                    event.key == Key.Tab && !command && !event.isAltPressed -> {
                                        update(indentCode(field.value, event.isShiftPressed)); true
                                    }
                                    event.key == Key.Escape && searchOpen -> { searchOpen = false; true }
                                    else -> false
                                }
                            }
                        },
                )
            }
        }
        val cursor = value.selection.end
        val line = value.text.take(cursor).count { it == '\n' } + 1
        val column = cursor - value.text.lastIndexOf('\n', cursor - 1)
        Text(tr(Res.string.editor_position, line, column), Modifier.padding(vertical = 4.dp),
            color = colors.onSurfaceVariantSummary, fontSize = 12.sp)
    }
}
