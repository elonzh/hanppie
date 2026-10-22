package cn.elonzh.hanppie.robot.network

import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.protocol.u16
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException

enum class WifiChannelMode(val code: Int) { AUTO(0), MANUAL(1) }

data class WifiRadioSettings(val modeCode: Int, val selectionCode: Int, val currentChannel: Int,
    val availableChannels: List<Int>) {
    /** Availability comes from this device; the native table only supplies a known wire band selector. */
    val selectableChannels: List<Int> get() = availableChannels.filter { WifiChannelProtocol.band(it) != null }
}

data class WifiRadioTarget(val mode: WifiChannelMode, val channel: Int? = null) {
    init { require(channel == null || mode == WifiChannelMode.MANUAL && channel in 1..65535) }
    fun matches(actual: WifiRadioSettings): Boolean = actual.modeCode == 0 && actual.selectionCode == mode.code &&
        (channel == null || actual.currentChannel == channel)
}

data class WifiChannelSignal(val channel: Int, val value: Int)
data class WifiSignals(val channels: List<WifiChannelSignal>? = null, val interferenceCode: Int? = null)

/** Fixed-client packet layouts. Group metadata is opaque, not an inferred frequency or signal unit. */
internal object WifiChannelProtocol {
    fun band(channel: Int): Int? = when (channel) {
        in 1..14 -> 0
        in 34..64 step 2, in 100..128 step 2, in 132..144 step 2, in 149..165 step 2 -> 1
        else -> null
    }

    fun channels(payload: ByteArray): Pair<Int, List<Int>> {
        if (payload.size < 10) invalid()
        val groups = payload.uint32(6)
        if (groups > (payload.size - 10) / 8) invalid()
        var cursor = 10
        val channels = mutableListOf<Int>()
        repeat(groups.toInt()) {
            if (cursor > payload.size - 8) invalid()
            val count = payload.uint32(cursor + 4)
            cursor += 8
            if (count > (payload.size - cursor) / 2) invalid()
            repeat(count.toInt()) { channels += payload.u16(cursor); cursor += 2 }
        }
        return payload.u8(5) to channels
    }

    fun signals(payload: ByteArray): List<WifiChannelSignal>? {
        if (payload.size < 18) return null
        val length = payload.uint32(0)
        if (length < 4 || length > payload.size) return null
        var cursor = 4
        val result = mutableListOf<WifiChannelSignal>()
        // The native loop accepts opaque tail bytes shorter than a group plus one entry.
        while (length - cursor >= 14) {
            val count = payload.u16(cursor + 4)
            cursor += 6
            if (count > (length - cursor) / 8) return null
            repeat(count) {
                result += WifiChannelSignal(payload.uint32(cursor).toInt(), payload.uint32(cursor + 4).toInt())
                cursor += 8
            }
        }
        return result
    }
}

private fun ByteArray.uint32(offset: Int): Long = (0..3).fold(0L) { value, index ->
    value or (u8(offset + index).toLong() shl (index * 8))
}
private fun invalid(): Nothing = throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
