package cn.elonzh.hanppie.ui.speech

import kotlin.test.*
import org.junit.Test

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
