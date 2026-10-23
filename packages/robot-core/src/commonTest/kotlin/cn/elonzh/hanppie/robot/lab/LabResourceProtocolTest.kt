package cn.elonzh.hanppie.robot.lab

import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.protocol.hexBytes
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlin.test.*

class LabResourceProtocolTest {
    private val guid = "0123456789abcdef0123456789abcdef"
    private val guidBytes = "3031323334353637383961626364656630313233343536373839616263646566"

    @Test fun requestsMatchNativeByteVectorsAndResourceDigestByteOrder() {
        val program = LabProgram("pass", guid, "0123456789abcdef")
        assertEquals("01" + guidBytes + "30313233343536373839616263646566", LabResourceProtocol.programQuery(program).hex())
        assertEquals("0d" + guidBytes + "000201001122330980ff0102",
            LabResourceProtocol.audioQuery(guid, mapOf(9 to "80ff0102", 1 to "00112233")).hex())
        assertEquals("0d" + guidBytes + "0000", LabResourceProtocol.audioQuery(guid, emptyMap()).hex())
        assertEquals(49, LabResourceProtocol.programQuery(program).size)
    }

    @Test fun programResponseIsAnInvertedMatchByteRatherThanRetcodePlusMatch() {
        assertTrue(LabResourceProtocol.programMatches(byteArrayOf(0)))
        assertFalse(LabResourceProtocol.programMatches(byteArrayOf(1)))
        assertFalse(LabResourceProtocol.programMatches(byteArrayOf(255.toByte())))
        for (p in listOf(byteArrayOf(), byteArrayOf(0, 1))) {
            assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { LabResourceProtocol.programMatches(p) }.problem)
        }
    }

    @Test fun audioResponsesUseIdMatchPairsWithoutTypeEchoOrOrderAssumptions() {
        assertEquals(setOf(9), LabResourceProtocol.audioMatches("000209010100".hexBytes(), setOf(1, 9)))
        assertEquals(setOf(9), LabResourceProtocol.audioMatches("00010901".hexBytes(), setOf(1, 9)))
        assertTrue(LabResourceProtocol.audioMatches("00010902".hexBytes(), setOf(9)).isEmpty())
        assertTrue(LabResourceProtocol.audioMatches("0000".hexBytes(), setOf(9)).isEmpty())
        val rejected = assertFailsWith<RobotRequestException> { LabResourceProtocol.audioMatches(byteArrayOf(7), setOf(9)) }
        assertEquals(RequestProblem.REJECTED, rejected.problem)
        assertEquals(7, rejected.responseCode)
    }

    @Test fun malformedOrUnrequestedEntriesCannotAuthorizeBodyOmission() {
        for (hex in listOf("", "00", "00020901", "00000901", "0001090100", "000209010901", "00010801")) {
            assertEquals(RequestProblem.INVALID_RESPONSE,
                assertFailsWith<RobotRequestException>(hex) { LabResourceProtocol.audioMatches(hex.hexBytes(), setOf(9)) }.problem)
        }
        for (entries in listOf(mapOf(10 to "00112233"), mapOf(0 to "0011223344"), mapOf(0 to "not-hex!"))) {
            assertFailsWith<IllegalArgumentException> { LabResourceProtocol.audioQuery(guid, entries) }
        }
    }
}
