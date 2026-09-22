package cn.elonzh.hanppie.robot.telemetry

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.protocol.hexBytes
import cn.elonzh.hanppie.robot.remote.RemoteControl
import cn.elonzh.hanppie.robot.remote.remoteSetup
import kotlin.test.*

class TelemetryTest {
    @Test fun capturedNativeChassisTopicsAndInvalidEnvelopes() {
        val attitude = frame(0x48, 8, "000dd8aad23edb7b063eb512d6bf".hexBytes())
        val decoded = Telemetry.chassisAttitude(attitude)!!
        assertEquals(.4114597f, decoded.yawDegrees, .00001f)
        val wheels = frame(0x48, 8, "000efdff0100fdff0000254dd90fc142b872869b1700879b1700899b1700849b170000000000".hexBytes())
        assertEquals(listOf(-3, 1, -3, 0), Telemetry.wheels(wheels)!!.rpm)
        assertEquals(19749 * 360f / 32768f, Telemetry.wheels(wheels)!!.anglesDegrees[0])
        for (source in listOf(attitude, wheels)) {
            for (bad in listOf(source.copy(valid = false), source.copy(id = 3),
                source.copy(payload = source.payload.dropLast(1).toByteArray()),
                source.copy(payload = source.payload.copyOf().apply { this[1] = 10 }))) {
                assertNull(Telemetry.chassisAttitude(bad)); assertNull(Telemetry.wheels(bad))
            }
        }
    }
    @Test fun wifiSignalQualityUsesTheNativePushValue() {
        assertEquals(37, Telemetry.wifiSignalQuality(frame(0x07, 0x09, byteArrayOf(37))))
        assertEquals(255, Telemetry.wifiSignalQuality(frame(0x07, 0x09, byteArrayOf(0xff.toByte(), 1))))
        assertNull(Telemetry.wifiSignalQuality(frame(0x07, 0x09, byteArrayOf())))
        assertNull(Telemetry.wifiSignalQuality(frame(0x07, 0x08, byteArrayOf(37))))
        assertNull(Telemetry.wifiSignalQuality(frame(0x07, 0x09, byteArrayOf(37)).copy(valid = false)))
    }

    @Test fun gimbalSubscriptionPreservesCapturedBytes() {
        assertEquals("00020a", GimbalSubscription.removePayload().hex())
        assertEquals("020a000001973c9bf7090002000a00", GimbalSubscription.addPayload().hex())
        assertEquals(listOf("00020a", "020a000001973c9bf7090002000a00"),
            remoteSetup.filter { it.set == 0x48 }.map { it.payload })
    }

    @Test fun allGimbalAnglesAndEnvelopeValidation() {
        // Same independent wire vector as Python; negative and >180-degree yaw.
        val payload = byteArrayOf(0,10,0xe2.toByte(),4,0x6f,0xff.toByte(),0x0a,0xf7.toByte(),0x85.toByte(),0,0x85.toByte())
        val frame = frame(0x48,8,payload)
        assertEquals(GimbalTelemetry(125.0,-14.5,-229.4,13.3,0x85), Telemetry.gimbal(frame))
        assertEquals(-229.4, Telemetry.gimbalYaw(frame))
        for (length in 0 until payload.size) assertNull(Telemetry.gimbal(frame.copy(payload=payload.copyOf(length))))
        for (invalid in listOf(
            frame.copy(valid=false), frame.copy(set=0x3f), frame.copy(id=4),
            frame.copy(payload=payload.copyOf().apply { this[0]=1 }),
            frame.copy(payload=payload.copyOf().apply { this[1]=9 }),
            frame.copy(payload=payload+byteArrayOf(0)),
        )) assertNull(Telemetry.gimbal(invalid))
        val outOfControlRange = frame.copy(payload=payload.copyOf().apply { this[6]=0x11; this[7]=0x0e })
        assertEquals(360.1, Telemetry.gimbal(outOfControlRange)!!.yawDegrees)
        assertNull(Telemetry.gimbalYaw(outOfControlRange)) // Preserve the control safety bound.
    }

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
