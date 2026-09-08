package cn.elonzh.hanppie.desktop

import kotlin.test.*

class EditorDocumentTest {
    @Test fun editAndRevertTrackActualUnsavedContent() {
        val initial = EditorDocument("pass")
        assertFalse(initial.dirty)
        assertTrue(initial.copy(source = "pass\n").dirty)
        assertFalse(initial.copy(source = "pass\n").copy(source = "pass").dirty)
    }
    @Test fun saveOnlyMarksWrittenSnapshotAsSaved() {
        val edited = EditorDocument("old").copy(source = "newer")
        val saved = edited.saved("old", "/script.py")
        assertTrue(saved.dirty)
        assertEquals("newer", saved.source)
        assertEquals("/script.py", saved.path)
        assertFalse(saved.saved("newer", "/script.py").dirty)
    }
}
