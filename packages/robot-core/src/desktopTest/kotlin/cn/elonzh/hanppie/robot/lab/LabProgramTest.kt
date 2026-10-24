package cn.elonzh.hanppie.robot.lab

import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.session.RobotTarget
import cn.elonzh.hanppie.robot.session.RobotTargetValidationException
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
        for (ip in listOf("", "robot.example", "127.0.0", "127.0.0.256", "-1.0.0.1")) {
            assertEquals(RobotTargetValidationException.Field.IPV4,
                assertFailsWith<RobotTargetValidationException> { RobotTarget(ip, "12345678") }.field)
        }
        for (appId in listOf("bad", "1234567g", "１２３４５６７８")) {
            assertEquals(RobotTargetValidationException.Field.APP_ID,
                assertFailsWith<RobotTargetValidationException> { RobotTarget("127.0.0.1", appId) }.field)
        }
        assertEquals("AbCd1234", RobotTarget("127.0.0.1", "AbCd1234").appId)
        assertFailsWith<IllegalArgumentException> { RobotTarget("127.0.0.1", "12345678", remotePort = 0) }
    }
}
