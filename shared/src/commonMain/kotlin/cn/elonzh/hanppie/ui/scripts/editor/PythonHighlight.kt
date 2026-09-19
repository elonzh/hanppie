package cn.elonzh.hanppie.ui.scripts.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

internal enum class PythonTokenKind { KEYWORD, STRING, COMMENT, NUMBER }
internal data class PythonToken(val start: Int, val end: Int, val kind: PythonTokenKind)
private val keywords = setOf("False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
    "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import",
    "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield")

/** Lexical coloring only: no claim of Python validation or device API checking. */
internal fun pythonTokens(source: String): List<PythonToken> = buildList {
    var i = 0
    while (i < source.length) {
        val start = i
        val char = source[i]
        when {
            char == '#' -> {
                while (i < source.length && source[i] != '\n') i++
                add(PythonToken(start, i, PythonTokenKind.COMMENT))
            }
            char == '\'' || char == '"' -> {
                val delimiter = if (source.startsWith("$char$char$char", i)) "$char$char$char" else "$char"
                i += delimiter.length
                while (i < source.length) {
                    if (source[i] == '\\') { i = (i + 2).coerceAtMost(source.length); continue }
                    if (source.startsWith(delimiter, i)) { i += delimiter.length; break }
                    if (delimiter.length == 1 && source[i] == '\n') break
                    i++
                }
                add(PythonToken(start, i, PythonTokenKind.STRING))
            }
            char.isLetter() || char == '_' -> {
                while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                if (source.substring(start, i) in keywords) add(PythonToken(start, i, PythonTokenKind.KEYWORD))
            }
            char.isDigit() -> {
                while (i < source.length && (source[i].isLetterOrDigit() || source[i] in "._")) i++
                add(PythonToken(start, i, PythonTokenKind.NUMBER))
            }
            else -> i++
        }
    }
}

internal class PythonHighlight(private val keyword: Color, private val string: Color,
    private val comment: Color, private val number: Color,
    private val cursor: Int, private val bracketBackground: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val result = AnnotatedString.Builder(text)
        val tokens = pythonTokens(text.text)
        for (token in tokens) result.addStyle(SpanStyle(color = when (token.kind) {
            PythonTokenKind.KEYWORD -> keyword
            PythonTokenKind.STRING -> string
            PythonTokenKind.COMMENT -> comment
            PythonTokenKind.NUMBER -> number
        }), token.start, token.end)
        matchingBrackets(text.text, cursor, tokens)?.let { (first, second) ->
            val style = SpanStyle(background = bracketBackground)
            result.addStyle(style, first, first + 1)
            result.addStyle(style, second, second + 1)
        }
        return TransformedText(result.toAnnotatedString(), OffsetMapping.Identity)
    }
}

internal fun matchingBrackets(source: String, cursor: Int, tokens: List<PythonToken> = pythonTokens(source)): Pair<Int, Int>? {
    val target = when {
        source.getOrNull(cursor) in listOf('(', ')', '[', ']', '{', '}') -> cursor
        source.getOrNull(cursor - 1) in listOf('(', ')', '[', ']', '{', '}') -> cursor - 1
        else -> return null
    }
    val stack = ArrayDeque<Int>()
    var tokenIndex = 0
    for (i in source.indices) {
        while (tokenIndex < tokens.size && tokens[tokenIndex].end <= i) tokenIndex++
        val token = tokens.getOrNull(tokenIndex)
        if (token != null && i >= token.start && token.kind in listOf(PythonTokenKind.STRING, PythonTokenKind.COMMENT)) continue
        val char = source[i]
        if (char in "([{") stack.addLast(i)
        else if (char in ")]}") {
            val opening = stack.removeLastOrNull() ?: continue
            if ("([{".indexOf(source[opening]) != ")]}".indexOf(char)) { stack.clear(); continue }
            if (target == opening || target == i) return opening to i
        }
    }
    return null
}
