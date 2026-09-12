package cn.elonzh.hanppie.ui.robot.remote

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Packetized S1 Opus -> streaming Ogg pages. No audio is written to disk. */
internal class OpusOgg {
    private var sequence = 0
    private var granule = 0L
    fun headers(): ByteArray {
        check(sequence == 0)
        val head = "OpusHead".toByteArray() + byteArrayOf(1,1,0,0,0x80.toByte(),0xbb.toByte(),0,0,0,0,0)
        return page(head, 2) + page("OpusTags".toByteArray() + ByteArray(8), 0)
    }
    fun packet(packet: ByteArray): ByteArray {
        require(sequence >= 2 && packet.isNotEmpty() && packet.size < 65025)
        val toc = packet[0].toInt() and 255
        val config = toc ushr 3
        val samples = when {
            config >= 16 -> 120 shl (config and 3)
            config >= 12 -> 480 shl (config and 1)
            config and 3 == 3 -> 2880
            else -> 480 shl (config and 3)
        }
        val count = when (toc and 3) { 0 -> 1; 1, 2 -> 2; else -> {
            require(packet.size >= 2); packet[1].toInt() and 63
        } }
        require(count > 0 && samples * count <= 5760)
        granule += samples * count
        return page(packet, 0)
    }
    private fun page(packet: ByteArray, flags: Int): ByteArray {
        val laces = packet.size / 255 + 1
        val result = ByteBuffer.allocate(27 + laces + packet.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OggS".toByteArray()).put(0).put(flags.toByte()).putLong(granule)
            .putInt(1).putInt(sequence++).putInt(0).put(laces.toByte())
        repeat(laces) { result.put(if (it == laces - 1) (packet.size % 255).toByte() else 255.toByte()) }
        result.put(packet)
        val bytes = result.array()
        var crc = 0
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 255) shl 24)
            repeat(8) { crc = (crc shl 1) xor (if (crc < 0) 0x04c11db7 else 0) }
        }
        result.putInt(22, crc)
        return bytes
    }
}
