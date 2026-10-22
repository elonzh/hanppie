package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.protocol.u16

/** Native camera storage values; negative SDK int32 counters remain unknown. */
data class CameraStorageStatus(
    val inserted: Boolean,
    val stateCode: Int,
    val totalSpaceMb: Int?,
    val remainingSpaceMb: Int?,
    val availablePhotos: Int?,
    val remainingRecordingSeconds: Int?,
    val recording: Boolean,
    val shooting: Boolean,
    val recordingSeconds: Int,
) {
    val formatting: Boolean get() = stateCode == 5
    val full: Boolean get() = stateCode == 8
    val hasError: Boolean get() = when (stateCode) { 2, 3, 4, 6, 10, 11 -> true; else -> false }
    val canFormat: Boolean get() = inserted && !formatting && !recording && !shooting

    companion object {
        internal fun decode(p: ByteArray): CameraStorageStatus {
            require(p.size >= 26)
            fun count(offset: Int): Int? = (0..3).fold(0) { bits, index ->
                bits or (p.u8(offset + index) shl (index * 8))
            }.takeIf { it >= 0 }
            return CameraStorageStatus(p.u8(2) and 0x10 != 0, p.u8(2) and 0xf,
                count(10), count(14), count(18), count(22),
                p.u16(5) and 0x3000 != 0, p.u8(7) and 0x30 != 0, p.u16(3))
        }
    }
}
