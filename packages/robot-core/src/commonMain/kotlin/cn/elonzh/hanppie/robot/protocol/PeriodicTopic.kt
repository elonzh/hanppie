package cn.elonzh.hanppie.robot.protocol

/** One App-owned DDS topic. Message IDs are allocated per connection, independently of the UID. */
internal data class PeriodicTopic(val messageId: Int, val uid: Long, val frequency: Int, val bytes: Int) {
    fun remove() = byteArrayOf(0, 2, messageId.toByte())
    fun add() = ByteArray(15).apply {
        this[0] = 2; this[1] = messageId.toByte(); this[4] = 1
        repeat(8) { this[5 + it] = (uid ushr (8 * it)).toByte() }
        put16(13, frequency)
    }
    fun data(frame: DussFrame): ByteArray? = frame.payload.takeIf {
        frame.valid && frame.sender == Protocol.HOST_HDVT_UAV && frame.receiver == Protocol.HOST_MOBILE &&
            frame.attr == Protocol.ATTR_NO_ACK && frame.set == Protocol.CMDSET_VIRTUAL_BUS &&
            frame.id == Protocol.CMD_VBUS_DATA_ANALYSIS && it.size == bytes + 2 && it.u8(0) == 0 && it.u8(1) == messageId
    }?.copyOfRange(2, bytes + 2)
}
