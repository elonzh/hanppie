package cn.elonzh.hanppie.ui

import kotlin.test.*

class EditorDocumentTest {
    @Test fun editAndRevertTrackActualUnsavedContent() {
        val initial = EditorDocument("pass")
        assertFalse(initial.dirty)
        assertTrue(initial.copy(source = "pass\n").dirty)
        assertFalse(initial.copy(source = "pass\n").copy(source = "pass").dirty)
    }
    @Test fun storingTheReturnedLibrarySnapshotClearsDirtyState() {
        val original = StoredScript("id", "Demo", "old", 1, 1)
        val edited = EditorDocument.from(original).copy(source = "newer")
        assertTrue(edited.dirty)
        val stored = edited.stored(original.copy(source = "newer", updatedAtEpochMillis = 2))
        assertFalse(stored.dirty)
        assertEquals("newer", stored.source)
        assertEquals("Demo", stored.displayName)
    }
}
