package cn.elonzh.hanppie.robot.extensions.arm

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlin.test.*

class ArmProtocolTest {
    @Test fun nativeControlIsTenBytesWithSeparateValidityModeAndLittleEndianChannels() {
        assertEquals("0f0094066c010004b602", armControlPayload(1.0, -1.0, -0.5, true, true).hex())
        assertEquals("00000004000400040004", armControlPayload(0.0, 0.0, 0.0, false, false).hex())
        assertEquals(8, armControlPayload(0.0, 0.0, 1.0, false, true).u8(0))
        assertEquals(1024, armControlPayload(0.001, 0.0, 0.0, true, false).u16(2))
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, 1.001, -1.001))
            assertFailsWith<IllegalArgumentException> { armControlPayload(bad, 0.0, 0.0, true, false) }
    }
    @Test fun bindingPreservesUnknownReadbackButOnlyValidPairsOrExplicitUninstallCanBeWritten() {
        assertEquals("020100", armBindingPayload(ArmServoBinding(2, 1)).hex())
        assertEquals("ffff00", armBindingPayload(ArmServoBinding.Uninstalled).hex())
        assertEquals(ArmServoBinding(255, 0), decodeArmBinding("00ff00".hexBytes()))
        assertFalse(decodeArmBinding("000001".hexBytes()).installed)
        for (pair in listOf(ArmServoBinding(1, 1), ArmServoBinding(0, 2), ArmServoBinding(4, 1), ArmServoBinding(255, 1)))
            assertFailsWith<IllegalArgumentException> { armBindingPayload(pair) }
        assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { decodeArmBinding(byteArrayOf(0, 1)) }.problem)
    }
    @Test fun topicsUseTheNativeUidsFrequenciesAndDistinctHostOwnedIds() {
        assertEquals("020f0000014dd6ab26090002000a00", ArmTopic.ARM.add().hex())
        assertEquals("02100000016a154d12090002000500", ArmTopic.CLAW.add().hex())
        assertEquals("00020f", ArmTopic.ARM.remove().hex()); assertEquals("000210", ArmTopic.CLAW.remove().hex())
    }
    @Test fun armPositionUsesSignedCoordinatesAndNativeLimitAndBlockedMasks() {
        val report = armPosition(armFrame(9, 0x48, 8, "000f0dfeffffff7b000000".hexBytes()))!!
        assertEquals(-2, report.xRaw); assertEquals(123, report.yRaw)
        assertTrue(report.limitX); assertFalse(report.limitY); assertTrue(report.blocked)
        for (payload in listOf("010f0dfeffffff7b000000", "000e0dfeffffff7b000000", "000f0dfeffffff7b0000", "000f0dfeffffff7b00000000"))
            assertNull(armPosition(armFrame(9, 0x48, 8, payload.hexBytes())))
        assertNull(armPosition(armFrame(3, 0x48, 8, "000f0dfeffffff7b000000".hexBytes())))
        assertNull(armPosition(armFrame(9, 0x48, 8, "000f0dfeffffff7b000000".hexBytes(), attr = 0xc0)))
        assertNull(armPosition(armFrame(9, 0x48, 8, "000f0dfeffffff7b000000".hexBytes(), receiver = 3)))
    }
    @Test fun nativePushObserversKeepTheirSourceAndDoNotDecodeAcksAsReports() {
        for (source in listOf(3, 0xc3, 0x3b)) assertEquals(255, armNativePush(armFrame(source, 0x33, 0x22, byteArrayOf(-1)), 0x22))
        assertNull(armNativePush(armFrame(3, 0x33, 0x22, byteArrayOf()), 0x22))
        assertNull(armNativePush(armFrame(3, 0x33, 0x22, byteArrayOf(1), attr = 0xc0), 0x22))
        assertNull(armNativePush(armFrame(3, 0x33, 0x21, byteArrayOf(1)), 0x22))
        val warning = ClawWarnings(0x19, 0x3b)
        assertTrue(warning.openBlocked); assertFalse(warning.closeBlocked)
        assertFalse(warning.moveUpBlocked); assertTrue(warning.moveDownBlocked)
        assertTrue(warning.moveUpLimit); assertFalse(warning.moveDownLimit)
        val other = ClawWarnings(0x26, 3)
        assertTrue(other.closeBlocked); assertTrue(other.moveUpBlocked); assertTrue(other.moveDownLimit)
        assertEquals(255, ClawWarnings(255, 3).raw)
        assertFalse(ClawWarnings(255, 3).openBlocked)
    }
}

internal fun armFrame(sender: Int, set: Int, id: Int, payload: ByteArray, attr: Int = 0, receiver: Int = 2): DussFrame =
    Protocol.frames(Protocol.duss(sender, receiver, attr, set, id, payload, 1)).single()
