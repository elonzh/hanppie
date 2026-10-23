package cn.elonzh.hanppie.robot.gamepad

import cn.elonzh.hanppie.robot.protocol.*

/** Fixed official macOS 1.1.5 / Android 1.2.0 native consumers; no motion command is emitted here. */
data class GamepadReport(val mouseConnected: Boolean, val keyboardConnected: Boolean,
    val batteryPercent: Int?, val rawFlags: Int)

internal object GamepadProtocol {
    const val NODE = 0x06
    const val INPUT_SET = 0x3f
    const val INPUT_ID = 0x90

    fun report(frame: DussFrame): GamepadReport? {
        if (!frame.valid || frame.sender != NODE || frame.receiver != Protocol.HOST_MOBILE ||
            frame.attr != Protocol.ATTR_NO_ACK || frame.set != INPUT_SET || frame.id != INPUT_ID || frame.payload.size != 19) return null
        val flags = frame.payload.u8(1)
        return GamepadReport(flags and 0x10 != 0, flags and 8 != 0,
            frame.payload.u8(18).takeIf { it <= 100 }, flags)
    }

    fun version(payload: ByteArray): String {
        require(payload.size >= 26)
        return listOf(25, 24, 23, 22).joinToString(".") { payload.u8(it).toString().padStart(2, '0') }
    }

    /** Native activation uses the low TWO bits: only 1 is activated; 2/3 remain unknown. */
    fun activated(payload: ByteArray): Boolean? {
        require(payload.size >= 10 && payload.size >= 10 + payload.u8(9))
        return when (payload.u8(1) and 3) { 0 -> false; 1 -> true; else -> null }
    }
}

/** Bounded V1 DUML stream framing. USB reads may split or coalesce frames. */
internal class GamepadFrameStream {
    private var pending = byteArrayOf()
    fun add(chunk: ByteArray): List<DussFrame> = buildList {
        for (byte in chunk) {
            pending += byte
            while (pending.isNotEmpty()) {
                if (pending.u8(0) != Protocol.DUSS_MAGIC) { pending = pending.drop(1).toByteArray(); continue }
                if (pending.size < 4) break
                val length = pending.u8(1) or ((pending.u8(2) and 3) shl 8)
                if (pending.u8(2) and 0xfc != 4 || length < 13 ||
                    Protocol.crc(pending.copyOfRange(0, 3), Protocol.CRC8_INIT, Protocol.CRC8_POLY) != pending.u8(3)) {
                    pending = pending.drop(1).toByteArray(); continue
                }
                if (pending.size < length) break
                val frame = Protocol.frames(pending).singleOrNull()
                if (frame?.valid == true) { add(frame); pending = byteArrayOf() }
                else pending = pending.drop(1).toByteArray()
            }
        }
    }
}
