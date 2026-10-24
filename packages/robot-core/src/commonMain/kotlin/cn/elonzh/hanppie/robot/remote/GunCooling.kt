package cn.elonzh.hanppie.robot.remote

import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GunCoolingPhase { IDLE, REQUESTING, COOLING, RESETTING, UNCONFIRMED }
enum class GunCoolingProblem { REJECTED, TIMED_OUT, INVALID_RESPONSE, SEND_FAILED }

/** A referee heat counter, not a temperature or a physical firing confirmation. */
data class GunCoolingState(val heatRaw: Int? = null, val fresh: Boolean = false,
    val phase: GunCoolingPhase = GunCoolingPhase.IDLE, val problem: GunCoolingProblem? = null) {
    val ownsCooling: Boolean get() = phase != GunCoolingPhase.IDLE
    val canCool: Boolean get() = fresh && heatRaw?.let { it in 1..1999 } == true && !ownsCooling
    val canRetryReset: Boolean get() = fresh && heatRaw == 0 && phase == GunCoolingPhase.UNCONFIRMED
}

/** Serialized by AppSession. Reuses its clock, receive loop and sender; no background writer. */
internal class GunCooling(private val nowMillis: () -> Long, private val send: (ByteArray) -> Int) {
    private val mutable = MutableStateFlow(GunCoolingState())
    val state = mutable.asStateFlow()
    private data class Pending(val reset: Boolean, val sequence: Int, val sentAt: Long)
    private var pending: Pending? = null
    private var reportedAt: Long? = null
    private var reportNumber = 0L
    private var coolingReport = 0L
    private var closed = false

    fun request() {
        check(!closed)
        tick()
        val current = state.value
        check(current.canCool || current.canRetryReset) { "请等待新鲜热量数据和当前冷却请求结束" }
        if (current.canRetryReset) command(reset = true)
        else {
            coolingReport = reportNumber
            command(reset = false)
        }
    }

    private fun command(reset: Boolean) {
        mutable.value = state.value.copy(phase = if (reset) GunCoolingPhase.RESETTING else GunCoolingPhase.REQUESTING, problem = null)
        try { pending = Pending(reset, send(byteArrayOf(if (reset) 1 else 0)), nowMillis()) }
        catch (_: Exception) {
            pending = null
            mutable.value = state.value.copy(phase = GunCoolingPhase.UNCONFIRMED, problem = GunCoolingProblem.SEND_FAILED)
        }
    }

    fun receive(frame: DussFrame) {
        tick()
        if (closed || !frame.valid || frame.sender != Protocol.HOST_HDVT_UAV || frame.receiver != Protocol.HOST_MOBILE ||
            frame.set != Protocol.CMDSET_RM) return
        if (frame.id == 0x0e && frame.attr == Protocol.ATTR_NO_ACK && frame.payload.size >= 15) {
            reportedAt = nowMillis(); reportNumber++
            val heat = frame.payload.u16(12)
            val returnedToIdle = state.value.phase == GunCoolingPhase.IDLE &&
                state.value.heatRaw?.let { it > 0 } == true && heat == 0
            mutable.value = state.value.copy(heatRaw = heat, fresh = true,
                problem = state.value.problem.takeUnless { heat == 0 && !state.value.ownsCooling })
            resetWhenCooled()
            if (returnedToIdle) resetIdleBarrel()
        }
        val expected = pending ?: return
        if (frame.id != Protocol.CMD_RM_SYSTEM_STATUS_CONFIG || frame.attr != Protocol.ATTR_RESP_NEED_ACK || frame.sequence != expected.sequence) return
        pending = null
        if (frame.payload.isEmpty()) unconfirmed(GunCoolingProblem.INVALID_RESPONSE)
        else if (frame.payload.u8(0) != 0) {
            mutable.value = state.value.copy(phase = if (expected.reset) GunCoolingPhase.UNCONFIRMED else GunCoolingPhase.IDLE,
                problem = GunCoolingProblem.REJECTED)
            if (!expected.reset && state.value.fresh && state.value.heatRaw == 0 && reportNumber > coolingReport)
                resetIdleBarrel()
        } else if (expected.reset) mutable.value = state.value.copy(phase = GunCoolingPhase.IDLE, problem = null)
        else {
            mutable.value = state.value.copy(phase = GunCoolingPhase.COOLING, problem = null)
            resetWhenCooled()
        }
    }

    /** Native Standy also resets after ordinary heat drains. This is a send, not a confirmed task. */
    private fun resetIdleBarrel() {
        try { send(byteArrayOf(1)) }
        catch (_: Exception) { unconfirmed(GunCoolingProblem.SEND_FAILED) }
    }

    private fun resetWhenCooled() {
        if (state.value.phase == GunCoolingPhase.COOLING && state.value.fresh && state.value.heatRaw == 0 && reportNumber > coolingReport)
            command(reset = true)
    }

    private fun unconfirmed(problem: GunCoolingProblem) {
        mutable.value = state.value.copy(phase = GunCoolingPhase.UNCONFIRMED, problem = problem)
    }

    fun tick() {
        if (closed) return
        val now = nowMillis()
        if (reportedAt?.let { now - it >= 1_500 } == true && state.value.fresh)
            mutable.value = state.value.copy(fresh = false)
        pending?.takeIf { now - it.sentAt >= 1_500 }?.let {
            pending = null; unconfirmed(GunCoolingProblem.TIMED_OUT)
        }
    }

    /** Leaving FPV retires local ownership, not proof of a device reset. The existing entry sends 01. */
    fun retire() { pending = null; reportedAt = null; reportNumber = 0; coolingReport = 0; mutable.value = GunCoolingState() }
    fun close() { retire(); closed = true }
}
