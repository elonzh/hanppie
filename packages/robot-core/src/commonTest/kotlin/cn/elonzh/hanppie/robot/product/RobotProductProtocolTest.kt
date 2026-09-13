package cn.elonzh.hanppie.robot.product

import cn.elonzh.hanppie.robot.protocol.DussFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RobotProductProtocolTest {
    @Test fun decodesExplicitProductTypesWithoutModelFallback() {
        assertEquals(RobotModel.ROBOMASTER_S1, RobotProductProtocol.model(frame(0xfe, byteArrayOf(0, 1))))
        assertEquals(RobotModel.ROBOMASTER_EP, RobotProductProtocol.model(frame(0xfe, byteArrayOf(0, 2))))
        assertEquals(RobotModel.UNKNOWN, RobotProductProtocol.model(frame(0xfe, byteArrayOf(0, 7))))
        assertNull(RobotProductProtocol.model(frame(0xfe, byteArrayOf(0))))
        assertNull(RobotProductProtocol.model(frame(0x12, byteArrayOf(0, 1))))
        assertNull(RobotProductProtocol.model(frame(0xfe, byteArrayOf(0, 1), valid = false)))
        assertNull(RobotProductProtocol.model(frame(0xfe, byteArrayOf(0, 1), attr = 0x80)))
    }

    @Test fun decodesWorkingDevicesAndPreservesUnknownDeviceIds() {
        val capabilities = RobotProductProtocol.capabilities(frame(0x12, byteArrayOf(
            3,
            0x00, 0x03, 1, 0x34, 0x12,
            0x1e, 0x03, 2, 0, 0, 0xfe.toByte(), 0,
            0x77, 0x77, 0,
        ), attr = 0x00))!!

        assertEquals(3, capabilities.workingDevices.size)
        assertTrue(RobotComponent.CHASSIS in capabilities)
        assertTrue(RobotComponent.ARM in capabilities)
        assertEquals(listOf(0x1234), capabilities.workingDevices[0].details)
        assertEquals(listOf(0, 0xfe), capabilities.workingDevices[1].details)
        assertEquals(setOf(0x7777), capabilities.unknownDeviceIds)
    }

    @Test fun rejectsTruncatedWorkingDevicePush() {
        assertNull(RobotProductProtocol.capabilities(frame(0x12, byteArrayOf(1, 0, 3, 1, 0), attr = 0x00)))
        assertNull(RobotProductProtocol.capabilities(frame(0x12, byteArrayOf(), attr = 0x00)))
        assertNull(RobotProductProtocol.capabilities(frame(0x12, byteArrayOf(0), attr = 0x40)))
    }

    private fun frame(id: Int, payload: ByteArray, valid: Boolean = true, attr: Int = 0xc0) =
        DussFrame(0, 0x28, 1, 1, attr, 0x3f, id, payload, valid)
}
