package cn.elonzh.hanppie.ui.robot.remote

internal fun ByteArray.hasNalType(expected: Int): Boolean {
    var index = 0
    while (index + 4 < size) {
        val start = when {
            this[index] == 0.toByte() && this[index + 1] == 0.toByte() && this[index + 2] == 1.toByte() -> index + 3
            this[index] == 0.toByte() && this[index + 1] == 0.toByte() && this[index + 2] == 0.toByte() && this[index + 3] == 1.toByte() -> index + 4
            else -> { index++; continue }
        }
        if ((this[start].toInt() and 31) == expected) return true
        index = start + 1
    }
    return false
}

/** MediaMuxer receives SPS/PPS through csd-0/csd-1, so samples must omit those NAL units. */
internal fun ByteArray.withoutNalTypes(excluded: Set<Int>): ByteArray {
    val starts = mutableListOf<Int>()
    var index = 0
    while (index + 3 < size) {
        if (this[index] == 0.toByte() && this[index + 1] == 0.toByte() &&
            (this[index + 2] == 1.toByte() || (this[index + 2] == 0.toByte() && this[index + 3] == 1.toByte()))) {
            starts += index
            index += if (this[index + 2] == 1.toByte()) 3 else 4
        } else index++
    }
    if (starts.isEmpty()) return this
    starts += size
    val kept = starts.zipWithNext().filter { (start, _) ->
        val header = start + if (this[start + 2] == 1.toByte()) 3 else 4
        header < size && (this[header].toInt() and 31) !in excluded
    }
    val result = ByteArray(kept.sumOf { it.second - it.first })
    var offset = 0
    kept.forEach { (start, end) ->
        copyInto(result, offset, start, end)
        offset += end - start
    }
    return result
}
