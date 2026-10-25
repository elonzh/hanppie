package cn.elonzh.hanppie.robot.lab

import cn.elonzh.hanppie.robot.protocol.hexBytes
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException

/** Native scratch_sys queries share 3f:a3, but do not share a response layout. */
internal object LabResourceProtocol {
    /** Script download uses DUSS_MB_RET_FINSH, not the ordinary zero success code. */
    fun registered(payload: ByteArray) {
        if (payload.isEmpty()) invalid()
        if (payload.u8(0) != 0xd0) throw RobotRequestException(RequestProblem.REJECTED, payload.u8(0))
    }

    fun programQuery(program: LabProgram): ByteArray = program.metadata(1)

    // Native 1e850 maps zero to match=1 and any nonzero byte to match=0.
    fun programMatches(payload: ByteArray): Boolean {
        if (payload.size != 1) invalid()
        return payload.u8(0) == 0
    }

    fun audioQuery(guid: String, digests: Map<Int, String>): ByteArray {
        require(Regex("[a-f0-9]{32}").matches(guid))
        require(digests.size <= LabAudioClip.MAX_CLIPS)
        require(digests.all { (id, digest) -> id in 0 until LabAudioClip.MAX_CLIPS && Regex("[a-f0-9]{8}").matches(digest) })
        return byteArrayOf(0x0d) + guid.encodeToByteArray() + byteArrayOf(0, digests.size.toByte()) +
            digests.entries.sortedBy { it.key }.fold(byteArrayOf()) { bytes, (id, digest) ->
                bytes + byteArrayOf(id.toByte()) + digest.hexBytes()
            }
    }

    fun audioMatches(payload: ByteArray, requestedIds: Set<Int>): Set<Int> {
        accepted(payload)
        if (payload.size < 2 || payload.size != 2 + payload.u8(1) * 2) invalid()
        val seen = mutableSetOf<Int>()
        val matches = mutableSetOf<Int>()
        repeat(payload.u8(1)) { index ->
            val id = payload.u8(2 + index * 2)
            if (id !in requestedIds || !seen.add(id)) invalid()
            // Missing entries and unknown match values never authorize body omission.
            if (payload.u8(3 + index * 2) == 1) matches.add(id)
        }
        return matches
    }

    private fun accepted(payload: ByteArray) {
        if (payload.isEmpty()) invalid()
        if (payload.u8(0) != 0) throw RobotRequestException(RequestProblem.REJECTED, payload.u8(0))
    }

    private fun invalid(): Nothing = throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
}
