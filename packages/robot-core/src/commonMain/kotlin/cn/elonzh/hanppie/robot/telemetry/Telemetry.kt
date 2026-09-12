package cn.elonzh.hanppie.robot.telemetry

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.put16
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.protocol.u16

/** Field offsets follow lab/direct.py; uncalibrated fields deliberately retain raw names. */
data class MotionTelemetry(val batteryPercent: Int?, val headingLike: Float, val raw: List<Float>)
data class LabMessage(val type: Int, val level: Int, val text: String)

/** SDK GimbalPosSubject wire angles; ground reference is not a calibrated world frame. */
data class GimbalTelemetry(
    val groundYawDegrees: Double, val groundPitchDegrees: Double,
    val yawDegrees: Double, val pitchDegrees: Double, val status: Int,
)

/** Fixed, packet-tested App subscription; see docs/architecture-robomaster.md section 5.3.5. */
internal object GimbalSubscription {
    const val messageId = 0x0a
    const val uid = 0x00020009f79b3c97L
    const val frequencyHz = 10
    fun removePayload() = byteArrayOf(0, 2, messageId.toByte())
    fun addPayload() = ByteArray(15).apply {
        this[0] = 2 // subscriber node
        this[1] = messageId.toByte()
        // flags=0 (no timestamp), periodic mode=0, one UID
        this[4] = 1
        repeat(8) { this[5 + it] = (uid ushr (it * 8)).toByte() }
        put16(13, frequencyHz)
    }
}

object Telemetry {
    /** RoboMaster App Wi-Fi quality push (cmdset 0x07, cmdid 0x09); the value is not dBm. */
    fun wifiSignalQuality(frame: DussFrame): Int? {
        if (!frame.valid || frame.set != 0x07 || frame.id != 0x09 || frame.payload.isEmpty()) return null
        return frame.payload.u8(0)
    }

    /** DDS subscription 0x0a: UID 0x00020009f79b3c97, SDK GimbalPosSubject. */
    fun gimbalYaw(frame: DussFrame): Double? {
        return gimbal(frame)?.yawDegrees?.takeIf { it in -360.0..360.0 }
    }

    fun gimbal(frame: DussFrame): GimbalTelemetry? {
        val p = frame.payload
        if (!frame.valid || frame.set != 0x48 || frame.id != 8 || p.size != 11 ||
            p.u8(0) != 0 || p.u8(1) != GimbalSubscription.messageId) return null
        fun angle(offset: Int) = p.u16(offset).toShort().toDouble() / 10
        return GimbalTelemetry(angle(2), angle(4), angle(6), angle(8), p.u8(10))
    }
    fun motion(frame: DussFrame): MotionTelemetry? {
        if (!frame.valid || frame.set != 0x48 || frame.id != 8 || frame.payload.size != 62) return null
        val payload = frame.payload
        fun float(offset: Int): Float = Float.fromBits(
            payload.u8(offset) or (payload.u8(offset + 1) shl 8) or
                (payload.u8(offset + 2) shl 16) or (payload.u8(offset + 3) shl 24))
        return MotionTelemetry(payload.u8(10).takeIf { it <= 100 }, float(12),
            (26..58 step 4).map(::float))
    }

    /** rm_module.Mobile.custom_msg_send: type:u8, level:u8, length:u16, message bytes. */
    fun labMessage(frame: DussFrame): LabMessage? {
        if (!frame.valid || frame.set != 0x3f || frame.id != 0xa4 || frame.payload.size < 4) return null
        val payload = frame.payload
        val length = payload.u16(2)
        if (length > payload.size - 4) return null
        return LabMessage(payload.u8(0), payload.u8(1),
            payload.copyOfRange(4, 4 + length).decodeToString())
    }
}
