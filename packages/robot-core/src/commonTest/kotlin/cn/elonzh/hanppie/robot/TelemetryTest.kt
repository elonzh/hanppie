package cn.elonzh.hanppie.robot

import kotlin.test.*

class TelemetryTest {
    @Test fun relativeYawAndCameraCoordinates() {
        val p = byteArrayOf(0,10,0,0,0,0,0x84.toByte(),3,0,0,0)
        assertEquals(90.0,Telemetry.gimbalYaw(frame(0x48,8,p)))
        p[6]=0x7c; p[7]=0xfc.toByte()
        assertEquals(-90.0,Telemetry.gimbalYaw(frame(0x48,8,p)))
        p[1]=9
        assertNull(Telemetry.gimbalYaw(frame(0x48,8,p)))
        for (yaw in listOf(-180.0,-90.0,0.0,90.0,180.0)) {
            val body = RemoteControl.cameraVelocity(.3,.1,yaw)
            val restored = RemoteControl.cameraVelocity(body.first,body.second,-yaw)
            assertEquals(.3,restored.first,1e-9); assertEquals(.1,restored.second,1e-9)
        }
        val right = RemoteControl.cameraVelocity(.3,0.0,90.0)
        assertEquals(0.0,right.first,1e-9); assertEquals(.3,right.second,1e-9)
    }
    private fun frame(set: Int, id: Int, payload: ByteArray) =
        DussFrame(20, 9, 2, 1, 0, set, id, payload, true)

    @Test fun motionFieldsAndInvalidBattery() {
        val payload = ByteArray(62)
        payload[10] = 95
        fun putFloat(offset: Int, value: Float) {
            val bits = value.toBits()
            repeat(4) { payload[offset + it] = (bits ushr (it * 8)).toByte() }
        }
        putFloat(12, -1.25f); putFloat(26, 42f); putFloat(58, Float.NaN)
        val decoded = assertNotNull(Telemetry.motion(frame(0x48, 8, payload)))
        assertEquals(95, decoded.batteryPercent)
        assertEquals(-1.25f, decoded.headingLike)
        assertEquals(42f, decoded.raw.first())
        assertTrue(decoded.raw.last().isNaN())
        assertEquals(9, decoded.raw.size)
        payload[10] = 255.toByte()
        assertNull(Telemetry.motion(frame(0x48, 8, payload))!!.batteryPercent)
        assertNull(Telemetry.motion(frame(0x48, 8, payload.copyOf(61))))
    }

    @Test fun labMessageBoundsAndUnicode() {
        val message = "机器人回报".encodeToByteArray()
        val payload = byteArrayOf(1, 2, message.size.toByte(), 0) + message
        val decoded = assertNotNull(Telemetry.labMessage(frame(0x3f, 0xa4, payload)))
        assertEquals("机器人回报", decoded.text)
        assertEquals(2, decoded.level)
        assertNull(Telemetry.labMessage(frame(0x3f, 0xa4, payload.copyOf(payload.size - 1))))
        assertNull(Telemetry.labMessage(frame(0x3f, 0xa4, payload).copy(valid = false)))
    }
}
