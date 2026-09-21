package cn.elonzh.hanppie.robot.lab

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.telemetry.Telemetry
import kotlin.test.*

class LabScriptStatusTest {
    private fun statusFrame(status: Int, guid: String, traceback: String? = null): DussFrame {
        val guidBytes = guid.encodeToByteArray()
        val base = byteArrayOf(status.toByte()) + guidBytes
        val payload = if (status == 5 && traceback != null) {
            val tbBytes = traceback.encodeToByteArray()
            val prefix = ByteArray(29) { 0 }
            val lenBytes = byteArrayOf((tbBytes.size and 0xFF).toByte(), ((tbBytes.size ushr 8) and 0xFF).toByte())
            base + prefix + lenBytes + tbBytes
        } else {
            base + ByteArray(26) { 0 }
        }
        return DussFrame(20, 9, 2, 1, 0, 0x3f, 0xa5, payload, true)
    }

    @Test fun decodesIdleStatus() {
        val frame = statusFrame(0, "00000000000000000000000000000000")
        val status = assertNotNull(Telemetry.labScriptStatus(frame))
        assertEquals(0, status.status)
        assertEquals("00000000000000000000000000000000", status.guid)
        assertTrue(status.isIdle)
        assertFalse(status.isRunning)
        assertFalse(status.isFailed)
        assertNull(status.traceback)
    }

    @Test fun decodesPreparingStatus() {
        val frame = statusFrame(1, "00000000000000000000000000000000")
        val status = assertNotNull(Telemetry.labScriptStatus(frame))
        assertEquals(1, status.status)
        assertTrue(status.isPreparing)
    }

    @Test fun decodesRunningStatusWithMatchingGuid() {
        val guid = "0123456789abcdef0123456789abcdef"
        val frame = statusFrame(2, guid)
        val status = assertNotNull(Telemetry.labScriptStatus(frame))
        assertEquals(2, status.status)
        assertEquals(guid, status.guid)
        assertTrue(status.isRunning)
        assertFalse(status.isIdle)
        assertNull(status.traceback)
    }

    @Test fun decodesFailedStatusWithNativeTraceback() {
        val guid = "0123456789abcdef0123456789abcdef"
        val tb = "Traceback (most recent call last):\n  File \"<CurFile>\", line 3, in start\nZeroDivisionError: division by zero\n"
        val frame = statusFrame(5, guid, traceback = tb)
        val status = assertNotNull(Telemetry.labScriptStatus(frame))
        assertEquals(5, status.status)
        assertEquals(guid, status.guid)
        assertTrue(status.isFailed)
        assertEquals(tb, status.traceback)
    }

    @Test fun rejectsInvalidOrTruncatedFrames() {
        assertNull(Telemetry.labScriptStatus(DussFrame(20, 9, 2, 1, 0, 0x3f, 0xa5, ByteArray(32), true)))
        assertNull(Telemetry.labScriptStatus(DussFrame(20, 9, 2, 1, 0, 0x3f, 0xa4, ByteArray(40), true)))
        assertNull(Telemetry.labScriptStatus(DussFrame(20, 9, 2, 1, 0, 0x48, 0xa5, ByteArray(40), true)))
        val invalidFrame = DussFrame(20, 9, 2, 1, 0, 0x3f, 0xa5, ByteArray(40), false)
        assertNull(Telemetry.labScriptStatus(invalidFrame))
    }
}
