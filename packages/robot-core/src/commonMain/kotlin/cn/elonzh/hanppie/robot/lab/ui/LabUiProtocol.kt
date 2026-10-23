package cn.elonzh.hanppie.robot.lab.ui

import cn.elonzh.hanppie.robot.protocol.u8

internal data class LabUiAttribute(val type: Int, val index: Int, val function: Int, val parameters: List<LabUiValue>)

/** Both native directions use four header bytes followed by type/byte-length/value records. */
internal object LabUiProtocol {
    const val ATTRIBUTE = 0xba
    const val EVENT = 0xbb

    fun attribute(payload: ByteArray): LabUiAttribute? {
        if (payload.size !in 4..1010) return null
        var cursor = 4
        val parameters = mutableListOf<LabUiValue>()
        repeat(payload.u8(3)) {
            if (cursor + 2 > payload.size) return null
            val type = payload.u8(cursor++)
            val size = payload.u8(cursor++)
            if (cursor + size > payload.size) return null
            fun integer() = (0..3).fold(0) { value, index -> value or (payload.u8(cursor + index) shl (index * 8)) }
            val value = when (type) {
                0 -> if (size == 1 && payload.u8(cursor) in 0..1) LabUiValue.Flag(payload.u8(cursor) == 1) else return null
                1 -> if (size == 4) LabUiValue.Integer(integer()) else return null
                2 -> if (size == 4) Float.fromBits(integer()).takeIf { it.isFinite() }?.let { LabUiValue.Decimal(it) } ?: return null else return null
                3 -> try { LabUiValue.Text(payload.decodeToString(cursor, cursor + size, throwOnInvalidSequence = true)) }
                    catch (_: CharacterCodingException) { return null }
                else -> return null
            }
            parameters.add(value)
            cursor += size
        }
        if (cursor != payload.size) return null
        return LabUiAttribute(payload.u8(0), payload.u8(1), payload.u8(2), parameters)
    }

    fun event(key: LabWidgetKey, event: Int, value: LabUiValue?): ByteArray {
        require(event in 0..255)
        val parameter = when (value) {
            null -> byteArrayOf()
            is LabUiValue.Flag -> byteArrayOf(0, 1, if (value.value) 1 else 0)
            is LabUiValue.Integer -> byteArrayOf(1, 4) + integer(value.value)
            is LabUiValue.Decimal -> { require(value.value.isFinite()); byteArrayOf(2, 4) + integer(value.value.toBits()) }
            is LabUiValue.Text -> {
                val bytes = value.value.encodeToByteArray()
                require(bytes.size <= 255) { "输入内容超过程序控件的单次传输上限" }
                byteArrayOf(3, bytes.size.toByte()) + bytes
            }
        }
        return byteArrayOf(key.type.raw.toByte(), key.index.toByte(), event.toByte(), if (value == null) 0 else 1) + parameter
    }

    private fun integer(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }
}
