package cn.elonzh.hanppie.robot.tracking

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.protocol.u16

/** Normalized video coordinates, measured from the top left, with a center-based rectangle. */
data class TrackingRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val valid: Boolean get() = listOf(x, y, width, height).all { it.isFinite() } &&
        x in 0f..1f && y in 0f..1f && width > 0f && width <= 1f && height > 0f && height <= 1f
}

data class TrackingPush(val type: Int, val status: Int, val error: Int, val rectangles: List<TrackingRect>)

/** RoboMaster 1.1.5 native packet constructors; see docs/architecture-robomaster.md. */
object TrackingProtocol {
    const val ENABLE = 0xa3
    const val PUSH = 0xa4
    const val CONFIRM = 0xa7
    const val SELECT = 0xa8
    const val TRACKING_TYPE = 6
    const val HUMAN_TYPE = 1

    fun enable(enabled: Boolean): ByteArray = byteArrayOf(if (enabled) 0x40 else 0, 0)
    fun confirm(start: Boolean): ByteArray = byteArrayOf(0x40, 0, if (start) 1 else 0, 0, 0, 0, 0)
    fun select(x: Float, y: Float): ByteArray {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        return byteArrayOf(0x40, 0, 0) + listOf(x, y, 0f, 0f).flatMap { value ->
            List(4) { (value.toBits() ushr (it * 8)).toByte() }
        }.toByteArray()
    }

    fun push(frame: DussFrame): TrackingPush? {
        if (!frame.valid || frame.sender != Protocol.HOST_VISION || frame.receiver != Protocol.HOST_MOBILE ||
            frame.attr != Protocol.ATTR_NO_ACK || frame.set != Protocol.CMDSET_VISION || frame.id != PUSH) return null
        val p = frame.payload
        if (p.size < 9 || p.u8(0) !in listOf(TRACKING_TYPE, HUMAN_TYPE) || p.u8(1) !in 0..3 ||
            p.size != 9 + p.u8(8) * 20) return null
        val rectangles = List(p.u8(8)) { index ->
            val offset = 9 + index * 20
            fun float(at: Int) = Float.fromBits((0..3).fold(0) { bits, i -> bits or (p.u8(at + i) shl (8 * i)) })
            TrackingRect(float(offset), float(offset + 4), float(offset + 8), float(offset + 12))
        }
        if (rectangles.any { rect -> listOf(rect.x, rect.y, rect.width, rect.height).any { !it.isFinite() || it !in 0f..1f } }) return null
        return TrackingPush(p.u8(0), p.u8(1), p.u16(6), rectangles.filter { it.valid })
    }
}
