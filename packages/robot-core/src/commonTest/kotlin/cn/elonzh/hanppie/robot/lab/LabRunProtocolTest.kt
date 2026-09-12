package cn.elonzh.hanppie.robot.lab

import kotlin.test.*

class LabRunProtocolTest {
    @Test fun instrumentsAUniqueStartWithoutReplacingPythonBuiltins() {
        val source = "def start():\n    print('hello', 3)\n"
        val result = LabRunProtocol.instrument(source, "0123456789abcdef")
        assertFalse(result.contains("def start():\n    print('hello', 3)"))
        assertTrue(result.contains("def _hanppie_0123456789abcdef_user_start():"))
        assertTrue(result.contains("_hanppie_0123456789abcdef_user_start()"))
        assertFalse(result.contains("print ="))
        assertTrue(result.contains("__HANPPIE_RUN__|0123456789abcdef|"))
        assertFalse(result.contains("match "))
        assertFailsWith<IllegalArgumentException> {
            LabRunProtocol.instrument("print('missing entry')", "0123456789abcdef")
        }
    }

    @Test fun decodesOnlyVersionedRunMessages() {
        assertEquals(
            LabRunEvent("0123456789abcdef", LabRunEventType.COMPLETED),
            LabRunProtocol.decode("__HANPPIE_RUN__|0123456789abcdef|COMPLETED|"),
        )
        assertNull(LabRunProtocol.decode("ordinary message"))
        assertNull(LabRunProtocol.decode("__HANPPIE_RUN__|0123456789abcdef|LOG|ordinary log"))
        assertNull(LabRunProtocol.decode("__HANPPIE_RUN__|bad|COMPLETED|"))
        assertNull(LabRunProtocol.decode("__HANPPIE_RUN__|0123456789abcdef|UNKNOWN|"))
    }
}
