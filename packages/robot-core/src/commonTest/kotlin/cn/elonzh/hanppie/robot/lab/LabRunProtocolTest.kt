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
        assertTrue(result.contains("log_ctrl.print_msg(\"__HANPPIE_RUN__|0123456789abcdef|"))
        assertFalse(result.contains("__import__"))
        assertFalse(result.contains("rm_module"))
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
        assertEquals(
            LabRunEvent("0123456789abcdef", LabRunEventType.STARTED),
            LabRunProtocol.decode("[Sun Sep 13 23:24:35 2026]: __HANPPIE_RUN__|0123456789abcdef|STARTED|"),
        )
    }

    @Test fun rejectsImportsDuringInstrumentation() {
        val error = assertFailsWith<IllegalArgumentException> {
            LabRunProtocol.instrument(
                "import time\n\ndef start():\n    time.sleep(1)\n",
                "0123456789abcdef",
            )
        }
        assertContains(error.message.orEmpty(), "不能使用 import")
        assertFailsWith<IllegalArgumentException> {
            LabRunProtocol.instrument(
                "def start():\n    from time import sleep\n    sleep(1)\n",
                "0123456789abcdef",
            )
        }
    }
}
