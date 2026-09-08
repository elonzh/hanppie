package cn.elonzh.hanppie.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState

@Composable
internal fun ChatMarkdown(content: String, modifier: Modifier = Modifier) {
    val state = rememberMarkdownState(content)
    SelectionContainer {
        Markdown(markdownState = state, modifier = modifier.fillMaxWidth(),
            imageTransformer = Coil3ImageTransformerImpl, typography = chatTypography(), colors = chatColors())
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
            imageTransformer = Coil3ImageTransformerImpl, typography = chatTypography(), colors = chatColors())
    }
}

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
