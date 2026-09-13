package cn.elonzh.hanppie.robot.media

/** S1-verified Annex-B: group slices into complete access units for Android MediaCodec. */
class H264AccessUnits {
    private var bytes = byteArrayOf()
    private var hasSlice = false
    fun reset() { bytes = byteArrayOf(); hasSlice = false }
    fun accept(nal: ByteArray): ByteArray? {
        val offset = if (nal.size > 2 && nal[2] == 1.toByte()) 3 else 4
        if (nal.size <= offset) return null
        val type = nal[offset].toInt() and 31
        val slice = type in 1..5
        // first_mb_in_slice == 0 has Exp-Golomb code '1', including before emulation prevention.
        val firstSlice = slice && nal.size > offset+1 && (nal[offset+1].toInt() and 128) != 0
        val boundary = hasSlice && (firstSlice || type in listOf(7,8,9))
        val completed = if (boundary) bytes else null
        if (boundary || bytes.size + nal.size > 2_000_000) reset()
        bytes += nal
        hasSlice = hasSlice || slice
        return completed
    }
}
