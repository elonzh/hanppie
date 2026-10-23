package cn.elonzh.hanppie.robot.challenge

import cn.elonzh.hanppie.robot.display.SightPoint
import cn.elonzh.hanppie.robot.display.sightCalibration
import cn.elonzh.hanppie.robot.protocol.*

internal data class ChallengeEnvironment(val ready: Boolean = false, val sight: SightPoint? = null,
    val cleanupConfirmed: Boolean = true, val problem: ChallengeProblem? = null)

/** Session-serialized setup and restoration; no independent clock, coroutine or actuator writer. */
internal class ChallengeSetup(private val baseline: ByteArray, private val send: (Int, ByteArray) -> Int,
    private val nowMillis: () -> Long) {
    private enum class Command(val id: Int) { SIGHT(0x57), APPLY(0x09), RESTORE(0x09) }
    private data class Pending(val command: Command, val sequence: Int, val sentAt: Long)
    private var pending: Pending? = null
    private var configuration: ChallengeConfiguration? = null
    private var rulesMayBeActive = false
    private var restoreAttempts = 0
    var environment = ChallengeEnvironment()
        private set

    fun prepare(value: ChallengeConfiguration) {
        check(!rulesMayBeActive && pending == null)
        configuration = value; restoreAttempts = 0
        environment = ChallengeEnvironment()
        command(Command.SIGHT, byteArrayOf())
    }

    fun stop() {
        if (pending?.command == Command.RESTORE) return
        pending = null
        environment = environment.copy(ready = false, cleanupConfirmed = !rulesMayBeActive)
        if (rulesMayBeActive) { restoreAttempts = 0; restore() }
    }

    private fun restore() { restoreAttempts++; command(Command.RESTORE, baseline) }

    private fun command(kind: Command, payload: ByteArray) {
        try { pending = Pending(kind, send(kind.id, payload), nowMillis()) }
        catch (_: Exception) { failed(kind, timedOut = true) }
    }

    private fun failed(kind: Command, timedOut: Boolean) {
        pending = null
        environment = environment.copy(ready = false, cleanupConfirmed = !rulesMayBeActive,
            problem = when (kind) {
                Command.SIGHT -> ChallengeProblem.CALIBRATION_UNAVAILABLE
                Command.APPLY -> if (timedOut) ChallengeProblem.RULES_TIMED_OUT else ChallengeProblem.RULES_REJECTED
                Command.RESTORE -> ChallengeProblem.RESTORE_UNCONFIRMED
            })
    }

    fun receive(frame: DussFrame) {
        val expected = pending ?: return
        if (!frame.valid || frame.sender != Protocol.HOST_HDVT_UAV || frame.receiver != Protocol.HOST_MOBILE ||
            frame.attr != Protocol.ATTR_RESP_NEED_ACK || frame.set != Protocol.CMDSET_RM ||
            frame.id != expected.command.id || frame.sequence != expected.sequence || frame.payload.isEmpty()) return
        pending = null
        if (frame.payload.u8(0) != 0) { failed(expected.command, timedOut = false); return }
        when (expected.command) {
            Command.SIGHT -> {
                val sight = sightCalibration(frame.payload)
                if (sight == null) failed(Command.SIGHT, timedOut = false)
                else {
                    environment = environment.copy(sight = sight.zoomOut, cleanupConfirmed = false)
                    // A missing ACK cannot prove that a transmitted configuration stayed unapplied.
                    rulesMayBeActive = true
                    command(Command.APPLY, challengeRules(baseline, checkNotNull(configuration).firing))
                }
            }
            Command.APPLY -> environment = environment.copy(ready = true)
            Command.RESTORE -> {
                rulesMayBeActive = false
                environment = environment.copy(ready = false, cleanupConfirmed = true,
                    problem = environment.problem.takeUnless { it == ChallengeProblem.RESTORE_UNCONFIRMED })
            }
        }
    }

    fun tick() {
        val expected = pending ?: return
        if (nowMillis() - expected.sentAt < if (expected.command == Command.RESTORE) 500 else 1_500) return
        if (expected.command == Command.RESTORE && restoreAttempts < 3) restore()
        else failed(expected.command, timedOut = true)
    }

    fun close() { pending = null; environment = environment.copy(ready = false) }
}

/** Preserve the packet-tested FPV list and its buff/skill tail; change only the four official challenge rules. */
internal fun challengeRules(baseline: ByteArray, firing: ChallengeFiring): ByteArray {
    require(baseline.size >= 4)
    fun intAt(offset: Int): Int = (0..3).fold(0) { bits, i -> bits or (baseline.u8(offset + i) shl (i * 8)) }
    val count = intAt(0)
    require(count in 1..32 && baseline.size >= 4 + count * 8)
    val changes = mapOf(1002 to if (firing == ChallengeFiring.INFRARED) 1 else 0,
        1008 to 200, 1031 to 0, 1032 to 0)
    val offsets = (0 until count).map { 4 + it * 8 }
    require(changes.keys.all { id -> offsets.count { intAt(it) == id } == 1 })
    return baseline.copyOf().apply {
        offsets.forEach { offset -> changes[intAt(offset)]?.let { value ->
            repeat(4) { this[offset + 4 + it] = (value ushr (it * 8)).toByte() }
        } }
    }
}
