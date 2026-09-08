package cn.elonzh.hanppie.robot

/** Field offsets follow lab/direct.py; uncalibrated fields deliberately retain raw names. */
data class MotionTelemetry(val batteryPercent: Int?, val headingLike: Float, val raw: List<Float>)
data class LabMessage(val type: Int, val level: Int, val text: String)

object Telemetry {
    /** DDS subscription 0x0a: UID 0x00020009f79b3c97, SDK GimbalPosSubject. */
    fun gimbalYaw(frame: DussFrame): Double? {
        val p = frame.payload
        if (!frame.valid || frame.set != 0x48 || frame.id != 8 || p.size != 11 ||
            p.u8(0) != 0 || p.u8(1) != 10) return null
        return (p.u16(6).toShort().toDouble() / 10).takeIf { it in -360.0..360.0 }
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
