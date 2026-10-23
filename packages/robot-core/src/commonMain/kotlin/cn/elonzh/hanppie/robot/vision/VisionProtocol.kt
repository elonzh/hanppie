package cn.elonzh.hanppie.robot.vision

import cn.elonzh.hanppie.robot.protocol.*

/** Center-based normalized video geometry. Info has a different meaning for each detector. */
data class VisionRect(val x: Float, val y: Float, val width: Float, val height: Float, val info: Int = 0) {
    val valid: Boolean get() = listOf(x, y, width, height).all { it.isFinite() } &&
        x in 0f..1f && y in 0f..1f && width > 0f && width <= 1f && height > 0f && height <= 1f
    fun contains(px: Float, py: Float): Boolean = valid && px in (x - width / 2)..(x + width / 2) &&
        py in (y - height / 2)..(y + height / 2)
    fun intersection(other: VisionRect): Float =
        (minOf(x + width / 2, other.x + other.width / 2) - maxOf(x - width / 2, other.x - other.width / 2)).coerceAtLeast(0f) *
        (minOf(y + height / 2, other.y + other.height / 2) - maxOf(y - height / 2, other.y - other.height / 2)).coerceAtLeast(0f)
}

enum class VisionDeviceStatus(val raw: Int) { EXIST(0), INITIALIZING(1), WAITING_CONFIRM(2), RUNNING(3) }
data class VisionReport(val type: Int, val status: Int, val error: Int, val rectangles: List<VisionRect>) {
    val deviceStatus: VisionDeviceStatus? get() = VisionDeviceStatus.entries.firstOrNull { it.raw == status }
}

/** Ordinary App marker display: low word ID, high word distance labelled in centimetres. */
val VisionRect.markerId: Int get() = info and 0xffff
val VisionRect.markerDistanceCentimetres: Int get() = info ushr 16

/** The fixed client's public marker enum defines digits 10–19 and letters 20–45. */
fun markerSymbol(id: Int): String? = when (id) {
    in 10..19 -> (id - 10).toString()
    in 20..45 -> ('A' + (id - 20)).toString()
    else -> null
}

/** Fixed RoboMaster App native constructors and ordinary rectangle decoder; advanced markers differ. */
object VisionProtocol {
    const val ENABLE = 0xa3
    const val PUSH = 0xa4
    const val GET_ENABLE = 0xa5
    fun enable(mask: Int): ByteArray {
        require(mask in 0..0xffff)
        return byteArrayOf(mask.toByte(), (mask ushr 8).toByte())
    }
    fun enabled(payload: ByteArray): Int? = if (payload.size == 3 && payload.u8(0) == 0) payload.u16(1) else null
    fun push(frame: DussFrame): VisionReport? {
        if (!frame.valid || frame.sender != Protocol.HOST_VISION || frame.receiver != Protocol.HOST_MOBILE ||
            frame.attr != Protocol.ATTR_NO_ACK || frame.set != Protocol.CMDSET_VISION || frame.id != PUSH) return null
        val p = frame.payload
        // Native publishes these ordinary types. Pose/line and advanced markers need separate contracts.
        if (p.size < 9 || p.u8(0) !in listOf(0, 1, 3, 5, 6) || p.size != 9 + p.u8(8) * 20) return null
        val rectangles = List(p.u8(8)) { index ->
            val offset = 9 + index * 20
            fun int(at: Int) = (0..3).fold(0) { bits, i -> bits or (p.u8(at + i) shl (8 * i)) }
            VisionRect(Float.fromBits(int(offset)), Float.fromBits(int(offset + 4)),
                Float.fromBits(int(offset + 8)), Float.fromBits(int(offset + 12)), int(offset + 16))
        }
        if (rectangles.any { rect -> listOf(rect.x, rect.y, rect.width, rect.height).any { !it.isFinite() || it !in 0f..1f } }) return null
        return VisionReport(p.u8(0), p.u8(1), p.u16(6), rectangles.filter { it.valid })
    }
}
