package cn.elonzh.hanppie.ui

import org.junit.Test
import kotlin.test.*

class RecognitionServiceSelectionTest {
    @Test fun missingDefaultUsesOnlyUnambiguousService() {
        assertEquals("xiaomi", recognitionServiceSelection(null, "", listOf("xiaomi")))
        assertNull(recognitionServiceSelection(null, "", listOf("a", "b")))
        assertNull(recognitionServiceSelection(null, null, emptyList()))
    }
    @Test fun explicitSelectionAndValidSystemDefaultAreRespected() {
        assertEquals("a", recognitionServiceSelection("a", "b", listOf("a", "b")))
        assertEquals("b", recognitionServiceSelection(null, "b", listOf("a", "b")))
        assertNull(recognitionServiceSelection("removed", "b", listOf("b")))
    }
}
