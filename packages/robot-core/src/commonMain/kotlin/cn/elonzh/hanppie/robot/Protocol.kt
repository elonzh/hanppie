package cn.elonzh.hanppie.robot

fun String.hexBytes(): ByteArray {
    require(length % 2 == 0) { "Invalid hex length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
internal fun ByteArray.u8(index: Int) = this[index].toInt() and 255
internal fun ByteArray.u16(index: Int) = u8(index) or (u8(index + 1) shl 8)
internal fun ByteArray.put16(index: Int, value: Int) {
    this[index] = value.toByte()
    this[index + 1] = (value ushr 8).toByte()
}

/** App wire format, ported from the packet-tested Hanppie Python implementation. */
object Protocol {
    val neutral: ByteArray get() = "0000042000010840000210".hexBytes()

    fun crc(data: ByteArray, initial: Int, polynomial: Int): Int {
        var result = initial
        for (byte in data) {
            result = result xor (byte.toInt() and 255)
            repeat(8) { result = (result ushr 1) xor (if (result and 1 != 0) polynomial else 0) }
        }
        return result
    }

    fun duss(sender: Int, receiver: Int, attr: Int, set: Int, id: Int,
             payload: ByteArray = byteArrayOf(), sequence: Int): ByteArray {
        val size = 13 + payload.size
        require(size in 13..1023)
        return ByteArray(size).apply {
            this[0] = 0x55
            this[1] = size.toByte()
            this[2] = (4 or (size ushr 8)).toByte()
            this[3] = crc(copyOfRange(0, 3), 0x77, 0x8c).toByte()
            this[4] = sender.toByte(); this[5] = receiver.toByte()
            put16(6, sequence)
            this[8] = attr.toByte(); this[9] = set.toByte(); this[10] = id.toByte()
            payload.copyInto(this, 11)
            put16(size - 2, crc(copyOfRange(0, size - 2), 0x3692, 0x8408))
        }
    }

    fun frames(data: ByteArray): List<DussFrame> = buildList {
        var offset = 0
        while (offset <= data.size - 13) {
            if (data.u8(offset) != 0x55 || data.u8(offset + 2) and 0xfc != 4) { offset++; continue }
            val length = data.u8(offset + 1) or ((data.u8(offset + 2) and 3) shl 8)
            if (length < 13 || offset + length > data.size) { offset++; continue }
            val raw = data.copyOfRange(offset, offset + length)
            val valid = crc(raw.copyOfRange(0, 3), 0x77, 0x8c) == raw.u8(3) &&
                crc(raw.copyOfRange(0, length - 2), 0x3692, 0x8408) == raw.u16(length - 2)
            add(DussFrame(offset, raw.u8(4), raw.u8(5), raw.u16(6), raw.u8(8), raw.u8(9),
                raw.u8(10), raw.copyOfRange(11, length - 2), valid))
            offset += length
        }
    }

    fun broadcast(data: ByteArray): DiscoveredRobot? {
        if (data.size != 24) return null
        var key = 7
        val decoded = data.map { byte ->
            val value = (byte.toInt() xor key).toByte()
            key = ((key + 7) xor 178) and 255
            value
        }.toByteArray()
        if (decoded.u8(0) != 0x5a || decoded.u8(1) != 0x5b) return null
        val appBytes = decoded.copyOfRange(16, 24)
        val appId = if (appBytes.all { it == 0.toByte() }) "00000000" else appBytes.decodeToString()
        if (!Regex("[0-9a-fA-F]{8}").matches(appId)) return null
        return DiscoveredRobot((6..9).joinToString(".") { decoded.u8(it).toString() },
            decoded.copyOfRange(10, 16).hex().chunked(2).joinToString(":"), appId.lowercase(),
            decoded.u8(2) and 1 != 0)
    }
}

data class DiscoveredRobot(val ip: String, val mac: String, val appId: String, val pairing: Boolean)
data class DussFrame(val offset: Int, val sender: Int, val receiver: Int, val sequence: Int,
                     val attr: Int, val set: Int, val id: Int, val payload: ByteArray, val valid: Boolean)

class AppEnvelope(var session: Int, private val initialTick: Int) {
    init { require(session in 1..65535) }
    private var directTick = (initialTick + 8) and 65535
    private var directReference = initialTick
    private var latestDirect = initialTick
    private var controlTick = (initialTick + 0xa8) and 65535
    private var controlReference = initialTick
    private var packetIndex = 1

    fun preconnect(): ByteArray =
        "3080dc6800000004d84664006400c005140000640064006400c005140000640014006400c00514000064000101040102".hexBytes().apply {
            put16(2, session); checksum(this); put16(8, initialTick)
        }

    private fun checksum(bytes: ByteArray) {
        bytes[7] = bytes.take(7).fold(0) { sum, byte -> sum xor (byte.toInt() and 255) }.toByte()
    }

    fun direct(duss: ByteArray, flags: ByteArray = byteArrayOf(0, 0)): ByteArray {
        require(flags.size == 2)
        val tick = directTick
        directTick = (tick + 8) and 65535
        latestDirect = tick
        return ByteArray(20 + duss.size).apply {
            this[0] = size.toByte(); this[1] = (0x80 or ((size ushr 8) and 3)).toByte()
            put16(2, session); put16(4, tick); this[6] = 5; checksum(this)
            put16(8, directReference); put16(10, tick)
            this[16] = (packetIndex++).toByte(); this[17] = 1
            flags.copyInto(this, 18); duss.copyInto(this, 20)
        }
    }

    fun control(duss: ByteArray): ByteArray {
        val tick = controlTick
        controlTick = (tick + 8) and 65535
        return ByteArray(34 + duss.size).apply {
            this[0] = size.toByte(); this[1] = 0x80.toByte()
            put16(2, session); this[6] = 4; checksum(this)
            put16(8, tick); put16(10, tick)
            put16(16, controlReference); put16(18, controlReference)
            put16(24, directReference); put16(26, latestDirect)
            put16(32, duss.size); duss.copyInto(this, 34)
        }
    }

    fun observe(data: ByteArray) {
        if (data.size < 12 || data.u16(2) != session) return
        if (data.size >= 28 && data.u16(4) == 0) {
            if (data.u16(24) != 0) directReference = data.u16(24)
            if (data.u16(16) != 0) controlReference = data.u16(16)
            if (data.size != 34 && Protocol.frames(data).any { it.valid && it.offset >= 34 } && data.u16(10) != 0)
                controlTick = data.u16(10)
        }
    }
}
