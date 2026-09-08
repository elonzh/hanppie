package cn.elonzh.hanppie.robot

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class LabProgramTest {
    @Test fun xmlRoundTripPreservesPythonAndEscapesTitle() {
        val source = "def start():\n    print('你好 ]]> & <')\n"
        val program = LabProgram(source, "a".repeat(32), "b".repeat(16), "<&标题>")
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(program.dsp("2026/09/07")))
        assertEquals(source, document.getElementsByTagName("python_code").item(0).textContent)
        assertEquals("<&标题>", document.getElementsByTagName("title").item(0).textContent)
        assertEquals("21" + "a".repeat(32).encodeToByteArray().hex() + "b".repeat(16).encodeToByteArray().hex(),
            program.metadata(0x21).hex())
        assertEquals(35, program.guidMetadata().size)
    }
    @Test fun rejectsInvalidIdentityAndTarget() {
        assertFailsWith<IllegalArgumentException> { LabProgram("pass", "bad", "bad") }
        assertFailsWith<IllegalArgumentException> { RobotTarget("robot.example", "12345678") }
        assertFailsWith<IllegalArgumentException> { RobotTarget("127.0.0.1", "bad") }
    }
}
