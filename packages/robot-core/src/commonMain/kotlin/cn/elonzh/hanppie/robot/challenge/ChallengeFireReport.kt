package cn.elonzh.hanppie.robot.challenge

import cn.elonzh.hanppie.robot.protocol.*

/** Fixed App OnFireStatusPush fields. Frequency is a raw notification value, not a shot multiplier. */
internal data class ChallengeFireReport(val speedBits: Int, val frequencyRaw: Int, val detailRaw: Int)

internal fun challengeFireReport(frame: DussFrame): ChallengeFireReport? {
    if (!frame.valid || frame.sender != Protocol.HOST_GUN || frame.receiver != Protocol.HOST_MOBILE ||
        frame.attr != Protocol.ATTR_NO_ACK || frame.set != Protocol.CMDSET_RM || frame.id != 0x50 || frame.payload.size < 6) return null
    val p = frame.payload
    return ChallengeFireReport((0..3).fold(0) { bits, i -> bits or (p.u8(i) shl (i * 8)) }, p.u8(4), p.u8(5))
}
