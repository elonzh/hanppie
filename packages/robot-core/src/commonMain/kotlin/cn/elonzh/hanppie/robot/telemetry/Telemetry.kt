package cn.elonzh.hanppie.robot.telemetry

import cn.elonzh.hanppie.robot.lab.LabScriptStatus
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.put16
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.protocol.u16

/** Field offsets follow lab/direct.py; uncalibrated fields deliberately retain raw names. */
data class MotionTelemetry(val batteryPercent: Int?, val headingLike: Float, val raw: List<Float>)
data class LabMessage(val type: Int, val level: Int, val text: String)

data class ChassisAttitude(val yawDegrees: Float, val pitchDegrees: Float, val rollDegrees: Float)
/** Native ESC order: front right, front left, rear left, rear right; raw motor signs are preserved. */
data class WheelTelemetry(val rpm: List<Int>, val anglesDegrees: List<Float>)

/** Read-only topics verified on the native App session; see the dated homepage validation record. */
internal object ChassisSubscriptions {
    val topics = listOf(13 to 0x000200096b986306L, 14 to 0x00020009c14cb7c5L)
    fun addPayload(messageId: Int, uid: Long) = ByteArray(15).apply {
        this[0] = 2; this[1] = messageId.toByte(); this[4] = 1
        repeat(8) { this[5 + it] = (uid ushr (it * 8)).toByte() }
        put16(13, 10)
    }
}

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
    private fun topic(frame: DussFrame, message: Int, length: Int): ByteArray? = frame.payload.takeIf {
        frame.valid && frame.set == Protocol.CMDSET_VIRTUAL_BUS && frame.id == Protocol.CMD_VBUS_DATA_ANALYSIS &&
            it.size == length && it.u8(0) == 0 && it.u8(1) == message
    }

    fun chassisAttitude(frame: DussFrame): ChassisAttitude? {
        val p = topic(frame, 13, 14) ?: return null
        fun float(offset: Int) = Float.fromBits(p.u8(offset) or (p.u8(offset + 1) shl 8) or
            (p.u8(offset + 2) shl 16) or (p.u8(offset + 3) shl 24))
        val angles = listOf(float(2), float(6), float(10))
        if (angles.any { !it.isFinite() || it !in -180f..180f }) return null
        return ChassisAttitude(angles[0], angles[1], angles[2])
    }

    fun wheels(frame: DussFrame): WheelTelemetry? {
        val p = topic(frame, 14, 38) ?: return null
        val rpm = List(4) { p.u16(2 + it * 2).toShort().toInt() }
        val angles = List(4) { p.u16(10 + it * 2) }
        if (rpm.any { it !in -8192..8191 } || angles.any { it > 32767 }) return null
        return WheelTelemetry(rpm, angles.map { it * 360f / 32768f })
    }
    /** RoboMaster App Wi-Fi quality push (cmdset 0x07, cmdid 0x09); the value is not dBm. */
    fun wifiSignalQuality(frame: DussFrame): Int? {
        if (!frame.valid || frame.set != Protocol.CMDSET_WIFI || frame.id != Protocol.CMD_WIFI_AP_PUSH_RSSI || frame.payload.isEmpty()) return null
        return frame.payload.u8(0)
    }

    /** DDS subscription 0x0a: UID 0x00020009f79b3c97, SDK GimbalPosSubject. */
    fun gimbalYaw(frame: DussFrame): Double? {
        return gimbal(frame)?.yawDegrees?.takeIf { it in -360.0..360.0 }
    }

    fun gimbal(frame: DussFrame): GimbalTelemetry? {
        val p = frame.payload
        if (!frame.valid || frame.set != Protocol.CMDSET_VIRTUAL_BUS || frame.id != Protocol.CMD_VBUS_DATA_ANALYSIS || p.size != 11 ||
            p.u8(0) != 0 || p.u8(1) != GimbalSubscription.messageId) return null
        fun angle(offset: Int) = p.u16(offset).toShort().toDouble() / 10
        return GimbalTelemetry(angle(2), angle(4), angle(6), angle(8), p.u8(10))
    }
    fun motion(frame: DussFrame): MotionTelemetry? {
        if (!frame.valid || frame.set != Protocol.CMDSET_VIRTUAL_BUS || frame.id != Protocol.CMD_VBUS_DATA_ANALYSIS || frame.payload.size != 62) return null
        val payload = frame.payload
        fun float(offset: Int): Float = Float.fromBits(
            payload.u8(offset) or (payload.u8(offset + 1) shl 8) or
                (payload.u8(offset + 2) shl 16) or (payload.u8(offset + 3) shl 24))
        return MotionTelemetry(payload.u8(10).takeIf { it <= 100 }, float(12),
            (26..58 step 4).map(::float))
    }

    /** rm_module.Mobile.custom_msg_send: type:u8, level:u8, length:u16, message bytes. */
    fun labMessage(frame: DussFrame): LabMessage? {
        if (!frame.valid || frame.set != Protocol.CMDSET_RM || frame.id != Protocol.CMD_RM_SCRIPT_CUSTOM_INFO_PUSH || frame.payload.size < 4) return null
        val payload = frame.payload
        val length = payload.u16(2)
        if (length > payload.size - 4) return null
        return LabMessage(payload.u8(0), payload.u8(1),
            payload.copyOfRange(4, 4 + length).decodeToString())
    }

    /**
     * DUSS 0x3F / 0xA5 (DUSS_MB_CMD_RM_SCRIPT_BLOCK_STATUS_PUSH):
     * payload[0]: status (0: IDLE/STOPPED, 1: PREPARING, 2: RUNNING, 5: FAILED)
     * payload[1..32]: 32-char ASCII GUID of the script (zeros when idle)
     * payload[33..]: block data; when status == 5, payload[62..63] is u16 length of traceback string,
     * followed by the UTF-8 traceback string.
     */
    fun labScriptStatus(frame: DussFrame): LabScriptStatus? {
        if (!frame.valid || frame.set != Protocol.CMDSET_RM || frame.id != Protocol.CMD_RM_SCRIPT_BLOCK_STATUS_PUSH || frame.payload.size < 33) return null
        val payload = frame.payload
        val status = payload.u8(0)
        val guid = payload.copyOfRange(1, 33).decodeToString()
        val traceback = if (status == 5 && payload.size >= 64) {
            val length = payload.u16(62)
            val end = (64 + length).coerceAtMost(payload.size)
            if (end > 64) payload.copyOfRange(64, end).decodeToString() else null
        } else null
        return LabScriptStatus(status, guid, traceback)
    }
}
