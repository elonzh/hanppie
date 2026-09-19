package cn.elonzh.hanppie.ui.scripts.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Complete snapshots keep selection and IME composition in the native text field. */
internal class CodeHistory(initial: TextFieldValue) {
    private val past = ArrayDeque<TextFieldValue>()
    private val future = ArrayDeque<TextFieldValue>()
    private var current = initial
    private var compositionBase: TextFieldValue? = null
    private var compositionRecorded = false
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    fun reset(value: TextFieldValue) {
        past.clear()
        future.clear()
        compositionBase = null
        compositionRecorded = false
        current = value
    }

    fun record(next: TextFieldValue) {
        if (next.composition != null && current.composition == null) {
            compositionBase = current.copy(composition = null)
            compositionRecorded = false
        }
        if (next.text != current.text) {
            // Composition updates form one undo step; never restore an active IME session.
            if (compositionBase == null || !compositionRecorded) {
                past.addLast(compositionBase ?: current.copy(composition = null))
                compositionRecorded = true
                while (past.size > 100 || past.sumOf { it.text.length.toLong() } > 2_000_000L) past.removeFirst()
            }
            future.clear()
        }
        current = next
        if (next.composition == null) compositionBase = null
    }

    fun undo(): TextFieldValue {
        if (past.isEmpty()) return current
        future.addLast(current.copy(composition = null))
        current = past.removeLast()
        return current
    }

    fun redo(): TextFieldValue {
        if (future.isEmpty()) return current
        past.addLast(current.copy(composition = null))
        current = future.removeLast()
        return current
    }
}

internal fun indentCode(value: TextFieldValue, unindent: Boolean): TextFieldValue {
    val source = value.text
    val start = source.lastIndexOf('\n', (value.selection.min - 1)).let { it + 1 }
    val last = if (!value.selection.collapsed && value.selection.max > start &&
        source.getOrNull(value.selection.max - 1) == '\n') value.selection.max - 1 else value.selection.max
    val end = source.indexOf('\n', last).let { if (it < 0) source.length else it }
    val changes = mutableListOf<Pair<Int, Int>>()
    var offset = start
    val replacement = source.substring(start, end).split('\n').joinToString("\n") { line ->
        val remove = if (line.startsWith('\t')) 1 else line.takeWhile { it == ' ' }.length.coerceAtMost(4)
        val delta = if (unindent) -remove else 4
        changes += offset to delta
        offset += line.length + 1
        if (unindent) line.drop(remove) else "    $line"
    }
    fun map(position: Int): Int {
        var result = position
        for ((at, delta) in changes) {
            if (position >= at) result += if (delta < 0) -minOf(-delta, position - at) else delta
        }
        return result
    }
    return TextFieldValue(source.replaceRange(start, end, replacement),
        TextRange(map(value.selection.start), map(value.selection.end)))
}

/** Only transform a committed, single newline insertion; paste and composing text remain untouched. */
internal fun autoIndent(previous: TextFieldValue, next: TextFieldValue): TextFieldValue {
    if (previous.composition != null || next.composition != null || !next.selection.collapsed) return next
    val from = previous.selection.min
    val to = previous.selection.max
    if (next.text != previous.text.replaceRange(from, to, "\n") || next.selection.end != from + 1) return next
    val line = previous.text.substring(0, from).substringAfterLast('\n')
    val leading = line.takeWhile { it == ' ' || it == '\t' }
    val prefix = previous.text.substring(0, from).trimEnd()
    val extra = if (prefix.endsWith(':') && pythonTokens(prefix).none {
        it.kind in setOf(PythonTokenKind.STRING, PythonTokenKind.COMMENT) && it.end == prefix.length
    }) "    " else ""
    val indent = leading + extra
    return TextFieldValue(next.text.substring(0, from + 1) + indent + next.text.substring(from + 1),
        TextRange(from + 1 + indent.length))
}

internal fun findCode(source: String, query: String, from: Int, backwards: Boolean = false): TextRange? {
    if (query.isEmpty()) return null
    val position = if (backwards) {
        source.lastIndexOf(query, from - 1).takeIf { it >= 0 } ?: source.lastIndexOf(query)
    } else source.indexOf(query, from).takeIf { it >= 0 } ?: source.indexOf(query)
    return position.takeIf { it >= 0 }?.let { TextRange(it, it + query.length) }
}
