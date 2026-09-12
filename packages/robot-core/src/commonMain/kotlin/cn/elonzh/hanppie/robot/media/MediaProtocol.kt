package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.hexBytes
import cn.elonzh.hanppie.robot.protocol.put16

object SpeakerAudio {
    const val chunkBytes = 960
    val playPayload = "0b040000010001000001".hexBytes()
    private val transferId = "00000100".hexBytes()

    fun start(packetCount: Int, totalBytes: Int): ByteArray {
        require(packetCount in 1..0xffff && totalBytes in 1..0xffff)
        return ByteArray(17).apply {
            this[0] = 0
            transferId.copyInto(this, 1)
            put16(5, packetCount)
            put16(7, totalBytes)
        }
    }

    fun block(payload: ByteArray, index: Int): ByteArray {
        require(payload.size <= chunkBytes && index in 0..0xffff)
        return ByteArray(7 + payload.size).apply {
            this[0] = (index ushr 8).toByte()
            this[1] = index.toByte()
            put16(5, payload.size)
            payload.copyInto(this, 7)
        }
    }
}

/** Incremental Annex-B NAL splitter; retains split start codes and bounds corrupt streams. */
class AnnexB {
    private var pending = byteArrayOf()

    fun reset() {
        pending = byteArrayOf()
    }

    fun accept(chunk: ByteArray): List<ByteArray> {
        if (pending.size + chunk.size > 2_000_000) reset()
        pending += chunk
        val starts = mutableListOf<Int>()
        var index = 0
        while (index + 3 <= pending.size) {
            if (pending[index] == 0.toByte() && pending[index + 1] == 0.toByte()) {
                if (pending[index + 2] == 1.toByte()) {
                    starts.add(index)
                    index += 3
                    continue
                }
                if (index + 3 < pending.size && pending[index + 2] == 0.toByte() && pending[index + 3] == 1.toByte()) {
                    starts.add(index)
                    index += 4
                    continue
                }
            }
            index++
        }
        val result = starts.zipWithNext { start, end -> pending.copyOfRange(start, end) }
        if (starts.isNotEmpty()) pending = pending.copyOfRange(starts.last(), pending.size)
        return result
    }
}
