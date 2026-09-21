package cn.elonzh.hanppie.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.model.State
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.AnnotatedString
import cn.elonzh.hanppie.ui.code.rememberPythonHighlight
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChatMarkdown(content: String, modifier: Modifier = Modifier) {
    val state = rememberMarkdownState(content)
    val parsed by state.state.collectAsState()
    val density = LocalDensity.current
    var measuredHeight by rememberSaveable(content, density.density, density.fontScale) { mutableIntStateOf(0) }
    // Lazy recycling recreates the asynchronous parser. Keep the previous geometry until
    // parsing completes, including when restoring a partially scrolled message after navigation.
    val minimumHeight = if (parsed is State.Loading) with(density) { measuredHeight.toDp() } else 0.dp
    SelectionContainer {
        Markdown(markdownState = state, modifier = modifier.heightIn(min = minimumHeight).onSizeChanged {
            if (parsed is State.Success) measuredHeight = it.height
        },
            imageTransformer = Coil3ImageTransformerImpl, components = chatComponents(),
            typography = chatTypography(), colors = chatColors())
    }
}

/** One composition per reply; the agent exposes cumulative text, the renderer consumes only its suffix. */
@Composable
internal fun StreamingReply(content: String, modifier: Modifier = Modifier) {
    val state = rememberStreamingMarkdownState()
    val latest by rememberUpdatedState(content)
    LaunchedEffect(state) {
        snapshotFlow { latest }.collect { full ->
            val consumed = state.content.length
            if (full.length > consumed) state.append(full.substring(consumed))
        }
    }
    SelectionContainer {
        Markdown(streamingMarkdownState = state, modifier = modifier.fillMaxWidth(),
            imageTransformer = Coil3ImageTransformerImpl, components = chatComponents(),
            typography = chatTypography(), colors = chatColors())
    }
}

private fun chatComponents() = markdownComponents(
    codeBlock = highlightedCodeBlock,
    codeFence = { model ->
        MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style ->
            if (language?.lowercase() in setOf("python", "py", "python3")) {
                val highlighter = rememberPythonHighlight()
                val highlighted = remember(code, highlighter) { highlighter.filter(AnnotatedString(code)).text }
                MarkdownCodeBackground(
                    color = LocalMarkdownColors.current.codeBackground,
                    shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    MarkdownBasicText(text = highlighted, modifier = Modifier.horizontalScroll(rememberScrollState())
                        .padding(LocalMarkdownPadding.current.codeBlock), style = style)
                }
            } else {
                MarkdownHighlightedCode(code, language, style)
            }
        }
    },
    table = { model ->
        MarkdownTable(model.content, model.node, model.typography.table,
            headerBlock = { content, node, width, style ->
                MarkdownTableHeader(content, node, width, style, maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip, verticalAlignment = Alignment.Top)
            },
            rowBlock = { content, node, width, style ->
                MarkdownTableRow(content, node, width, style, maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip, verticalAlignment = Alignment.Top)
            })
    },
)

@Composable
private fun chatTypography() = with(MiuixTheme.textStyles) {
    val text = main
    val heading = subtitle.copy(fontWeight = FontWeight.SemiBold)
    DefaultMarkdownTypography(h1 = title3, h2 = title4, h3 = heading,
        h4 = heading, h5 = heading, h6 = heading, text = text,
        code = body2.copy(fontFamily = FontFamily.Monospace),
        inlineCode = text.copy(fontFamily = FontFamily.Monospace),
        quote = text, paragraph = text, ordered = text, bullet = text, list = text,
        textLink = TextLinkStyles(style = text.copy(color = MiuixTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline).toSpanStyle()), table = text)
}

@Composable
private fun chatColors() = with(MiuixTheme.colorScheme) {
    DefaultMarkdownColors(text = onSurface, codeBackground = surfaceContainerHigh,
        inlineCodeBackground = surfaceContainerHigh, dividerColor = dividerLine,
        tableBackground = surfaceContainer)
}
