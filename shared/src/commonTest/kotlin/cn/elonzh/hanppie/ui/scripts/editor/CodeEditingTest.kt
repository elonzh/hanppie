package cn.elonzh.hanppie.ui.scripts.editor

import cn.elonzh.hanppie.ui.code.pythonTokens
import cn.elonzh.hanppie.ui.code.PythonTokenKind
import cn.elonzh.hanppie.ui.code.matchingBrackets
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.*

class CodeEditingTest {
    @Test fun indentationPreservesReversedSelectionAndExcludesFollowingLine() {
        val original = TextFieldValue("one\ntwo\nthree", TextRange(8, 0))
        val indented = indentCode(original, false)
        assertEquals("    one\n    two\nthree", indented.text)
        assertEquals(TextRange(16, 4), indented.selection)
        assertEquals(original, indentCode(indented, true))
        assertEquals("one", indentCode(TextFieldValue("  one", TextRange(1)), true).text)
        assertEquals(TextRange(0), indentCode(TextFieldValue("  one", TextRange(1)), true).selection)
    }

    @Test fun newlineIndentsBlocksButDoesNotRewritePasteOrComposition() {
        val before = TextFieldValue("    if True:", TextRange(12))
        assertEquals("    if True:\n        ", autoIndent(before, TextFieldValue(before.text + "\n", TextRange(13))).text)
        val composing = TextFieldValue(before.text + "\n", TextRange(13), TextRange(12, 13))
        assertEquals(composing, autoIndent(before, composing))
        val paste = TextFieldValue("    if True:\n    hello\n", TextRange(23))
        assertEquals(paste, autoIndent(before, paste))
        val comment = TextFieldValue("    # note:", TextRange(11))
        assertEquals("    # note:\n    ", autoIndent(comment, TextFieldValue(comment.text + "\n", TextRange(12))).text)
    }

    @Test fun undoGroupsComposingTextAndPreservesSelection() {
        val base = TextFieldValue("", TextRange.Zero)
        val history = CodeHistory(base)
        history.record(TextFieldValue("n", TextRange(1), TextRange(0, 1)))
        history.record(TextFieldValue("ni", TextRange(2), TextRange(0, 2)))
        history.record(TextFieldValue("你", TextRange(1)))
        assertEquals(base, history.undo())
        assertEquals("你", history.redo().text)
        history.record(TextFieldValue("你好", TextRange(2)))
        assertFalse(history.canRedo)
        assertEquals("你", history.undo().text)
    }

    @Test fun bracketMatchingSkipsStringsAndComments() {
        assertEquals(0 to 6, matchingBrackets("([')'])", 0))
        assertNull(matchingBrackets("([')'])", 3))
        assertNull(matchingBrackets("([)]", 0))
        assertNull(matchingBrackets("# (ignored)", 2))
    }

    @Test fun undoAlsoHandlesCompositionStartingWithoutTextChange() {
        val initial = TextFieldValue("abc", TextRange(3))
        val history = CodeHistory(initial)
        history.record(initial.copy(composition = TextRange(2, 3)))
        history.record(TextFieldValue("ab字", TextRange(3), TextRange(2, 3)))
        history.record(TextFieldValue("ab字", TextRange(3)))
        assertEquals(initial, history.undo())
    }

    @Test fun searchWrapsAndTreatsQueryLiterally() {
        assertEquals(TextRange(0, 3), findCode("a.b aXb a.b", "a.b", 11))
        assertEquals(TextRange(8, 11), findCode("a.b aXb a.b", "a.b", 0, true))
        assertNull(findCode("abc", "", 0))
        assertNull(findCode("abc", "missing", 0))
    }

    @Test fun replacingDocumentDiscardsBothUndoAndRedo() {
        val history = CodeHistory(TextFieldValue("first"))
        history.record(TextFieldValue("edited"))
        history.undo()
        history.reset(TextFieldValue("imported"))
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals("imported", history.undo().text)
    }

    @Test fun tokenizerKeepsCommentsAndKeywordsInsideStrings() {
        val source = "def start():\n    text = '''# if\nelse'''\n    # return\n    x = 12\n"
        val tokens = pythonTokens(source)
        assertEquals(listOf(PythonTokenKind.KEYWORD, PythonTokenKind.STRING, PythonTokenKind.COMMENT, PythonTokenKind.NUMBER), tokens.map { it.kind })
        assertEquals("'''# if\nelse'''", source.substring(tokens[1].start, tokens[1].end))
        assertEquals(PythonTokenKind.STRING, pythonTokens("'unfinished").single().kind)
        assertEquals(1, pythonTokens("'it\\'s # fine'").size)
    }
}
