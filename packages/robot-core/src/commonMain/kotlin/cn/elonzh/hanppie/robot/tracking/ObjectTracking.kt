package cn.elonzh.hanppie.robot.tracking

import cn.elonzh.hanppie.robot.vision.*

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TrackingPhase { OFF, STARTING, SEARCHING, SELECTING, TARGET_READY, STARTING_FOLLOW, FOLLOWING, STOPPING, ERROR }
enum class TrackingProblem { REJECTED, TIMED_OUT, TARGET_LOST, DEVICE_ERROR, UNSUPPORTED_REPORT, STOP_UNCONFIRMED }

data class TrackingState(
    val phase: TrackingPhase = TrackingPhase.OFF,
    val rectangles: List<VisionRect> = emptyList(),
    val candidates: List<VisionRect> = emptyList(),
    val problem: TrackingProblem? = null,
    val report: VisionReport? = null,
    val responseCode: Int? = null,
    val rejectedCommand: Int? = null,
) {
    /** Official VisionFollow filters against the first tracked rectangle, retaining other candidates. */
    val targets: List<VisionRect> get() {
        val tracked = rectangles.firstOrNull()
        return rectangles + candidates.filter { candidate -> tracked == null || tracked.intersection(candidate) < tracked.width * tracked.height * .5f }
    }
    fun selectableAt(x: Float, y: Float): VisionRect? = if (phase in listOf(TrackingPhase.SEARCHING, TrackingPhase.TARGET_READY))
        targets.asReversed().firstOrNull { it.kind == TrackingTargetKind.CANDIDATE && it.contains(x, y) } else null
    val active: Boolean get() = phase != TrackingPhase.OFF && phase != TrackingPhase.ERROR
    val blocksManualControl: Boolean get() = active || problem == TrackingProblem.STOP_UNCONFIRMED
}

/** Session-owned state machine. Every entry point must run under the session's transmit lock. */
class ObjectTracking(
    private val send: (Int, ByteArray) -> Int,
    private val nowMillis: () -> Long,
    private val stopMotion: () -> Unit,
) {
    private enum class Command { RESET, ENABLE, SELECT, CONFIRM, DISABLE }
    private data class Pending(val command: Command, val id: Int, val sequence: Int, val sentAt: Long)
    private val mutableState = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = mutableState.asStateFlow()
    private var pending: Pending? = null
    private var deadline = 0L
    private var lastVisionReport = 0L
    private var lastCandidatePush = 0L
    private var lastStatus: VisionReport? = null
    private var confirmed = false
    private var stopAttempts = 0
    private var stopProblem: TrackingProblem? = null

    fun start() {
        check(!state.value.blocksManualControl)
        mutableState.value = TrackingState(TrackingPhase.STARTING)
        lastStatus = null; lastVisionReport = 0; lastCandidatePush = 0; confirmed = false; stopProblem = null
        stopMotion()
        command(Command.RESET, TrackingProtocol.ENABLE, TrackingProtocol.enable(false))
    }

    fun select(x: Float, y: Float) {
        check(state.value.phase == TrackingPhase.SEARCHING || state.value.phase == TrackingPhase.TARGET_READY)
        check(nowMillis() - lastCandidatePush < 750 && state.value.selectableAt(x, y) != null) { "请选择当前候选框" }
        val payload = TrackingProtocol.select(x, y)
        mutableState.value = state.value.copy(phase = TrackingPhase.SELECTING, rectangles = emptyList())
        lastStatus = null
        command(Command.SELECT, TrackingProtocol.SELECT, payload)
    }

    fun confirm() {
        check(state.value.phase == TrackingPhase.TARGET_READY && nowMillis() - lastVisionReport < 750 &&
            state.value.rectangles.any { it.kind == TrackingTargetKind.READY })
        confirmed = false
        mutableState.value = state.value.copy(phase = TrackingPhase.STARTING_FOLLOW)
        command(Command.CONFIRM, TrackingProtocol.CONFIRM, TrackingProtocol.confirm(true))
    }

    fun stop(problem: TrackingProblem? = null) {
        if (!state.value.blocksManualControl) return
        if (state.value.phase == TrackingPhase.STOPPING) return
        stopProblem = problem
        confirmed = false; lastStatus = null; stopAttempts = 0
        mutableState.value = state.value.copy(phase = TrackingPhase.STOPPING, rectangles = emptyList(), candidates = emptyList(), problem = null)
        sendStop()
    }

    private fun sendStop() {
        stopAttempts++
        // Cancellation and detection-off are both sent, followed by physical actuator zero commands.
        runCatching { send(TrackingProtocol.CONFIRM, TrackingProtocol.confirm(false)) }
        try { command(Command.DISABLE, TrackingProtocol.ENABLE, TrackingProtocol.enable(false)) }
        finally { stopMotion() }
    }

    private fun command(command: Command, id: Int, payload: ByteArray) {
        try { pending = Pending(command, id, send(id, payload), nowMillis()) }
        catch (error: Exception) {
            pending = null
            mutableState.value = state.value.copy(phase = TrackingPhase.ERROR, rectangles = emptyList(), candidates = emptyList(), problem = TrackingProblem.STOP_UNCONFIRMED)
            runCatching { stopMotion() }
            throw error
        }
        deadline = nowMillis() + 3_000
    }

    fun receive(frame: DussFrame) {
        val expected = pending
        if (expected != null && frame.valid && frame.sender == Protocol.HOST_VISION && frame.receiver == Protocol.HOST_MOBILE &&
            frame.attr == Protocol.ATTR_RESP_NEED_ACK && frame.set == Protocol.CMDSET_VISION &&
            frame.id == expected.id && frame.sequence == expected.sequence && frame.payload.isNotEmpty()) {
            pending = null
            if (frame.payload[0] != 0.toByte()) {
                mutableState.value = state.value.copy(responseCode = frame.payload[0].toInt() and 0xff, rejectedCommand = frame.id)
                if (expected.command == Command.DISABLE) {
                    mutableState.value = state.value.copy(phase = TrackingPhase.ERROR, rectangles = emptyList(), candidates = emptyList(), problem = TrackingProblem.STOP_UNCONFIRMED)
                    stopMotion()
                } else stop(TrackingProblem.REJECTED)
                return
            }
            when (expected.command) {
                Command.RESET -> command(Command.ENABLE, TrackingProtocol.ENABLE, TrackingProtocol.enable(true))
                Command.ENABLE -> {
                    mutableState.value = state.value.copy(phase = TrackingPhase.SEARCHING)
                    deadline = nowMillis() + 3_000
                }
                Command.SELECT -> applyStatus()
                Command.CONFIRM -> { confirmed = true; applyStatus() }
                Command.DISABLE -> {
                    mutableState.value = if (stopProblem == null) TrackingState() else
                        state.value.copy(phase = TrackingPhase.ERROR, problem = stopProblem)
                }
            }
            return
        }
        val push = TrackingProtocol.push(frame) ?: return
        if (!state.value.active || state.value.phase == TrackingPhase.STOPPING || state.value.phase == TrackingPhase.STARTING) return
        if (push.type == TrackingProtocol.HUMAN_TYPE) {
            if (push.error != 0 || push.deviceStatus == null) {
                mutableState.value = state.value.copy(report = push)
                stop(if (push.error != 0) TrackingProblem.DEVICE_ERROR else TrackingProblem.UNSUPPORTED_REPORT)
                return
            }
            lastCandidatePush = nowMillis()
            mutableState.value = state.value.copy(candidates = push.rectangles)
            return
        }
        mutableState.value = state.value.copy(report = push)
        lastVisionReport = nowMillis()
        if (state.value.phase == TrackingPhase.SEARCHING) deadline = nowMillis() + 3_000
        lastStatus = push
        if (push.error != 0) { stop(TrackingProblem.DEVICE_ERROR); return }
        if (push.deviceStatus == null) { stop(TrackingProblem.UNSUPPORTED_REPORT); return }
        if (pending == null) applyStatus()
    }

    private fun applyStatus() {
        val push = lastStatus ?: return
        val phase = state.value.phase
        val expectedKind = when (push.deviceStatus) {
            VisionDeviceStatus.WAITING_CONFIRM -> TrackingTargetKind.READY
            VisionDeviceStatus.RUNNING -> TrackingTargetKind.FOLLOWING
            else -> null
        }
        if (expectedKind != null && push.rectangles.isNotEmpty() && push.rectangles.none { it.kind == expectedKind }) {
            stop(TrackingProblem.UNSUPPORTED_REPORT); return
        }
        val rectangles = if (expectedKind == null) push.rectangles else push.rectangles.filter { it.kind == expectedKind }
        when {
            phase == TrackingPhase.FOLLOWING && (push.status != 3 || rectangles.isEmpty()) -> stop(TrackingProblem.TARGET_LOST)
            phase == TrackingPhase.STARTING_FOLLOW && confirmed && push.status == 3 && rectangles.isNotEmpty() ->
                mutableState.value = state.value.copy(phase = TrackingPhase.FOLLOWING, rectangles = rectangles, candidates = emptyList())
            phase == TrackingPhase.FOLLOWING -> mutableState.value = state.value.copy(rectangles = rectangles)
            phase in listOf(TrackingPhase.SELECTING, TrackingPhase.TARGET_READY) && push.status == 2 && rectangles.isNotEmpty() ->
                mutableState.value = state.value.copy(phase = TrackingPhase.TARGET_READY, rectangles = rectangles)
            phase == TrackingPhase.TARGET_READY && (push.status != 2 || rectangles.isEmpty()) -> stop(TrackingProblem.TARGET_LOST)
            push.status == 3 && phase != TrackingPhase.STARTING_FOLLOW -> stop(TrackingProblem.TARGET_LOST)
        }
    }

    fun tick(operatorPresent: Boolean) {
        val current = state.value
        val now = nowMillis()
        if (current.candidates.isNotEmpty() && now - lastCandidatePush >= 750)
            mutableState.value = current.copy(candidates = emptyList())
        if (current.phase == TrackingPhase.STOPPING) {
            if (pending?.let { now - it.sentAt >= 500 } == true) {
                if (stopAttempts < 3) sendStop()
                else {
                    pending = null
                    mutableState.value = state.value.copy(phase = TrackingPhase.ERROR, rectangles = emptyList(), candidates = emptyList(), problem = TrackingProblem.STOP_UNCONFIRMED)
                    stopMotion()
                }
            }
        } else if (current.active) {
            when {
                !operatorPresent -> stop()
                pending?.let { now - it.sentAt >= 1_500 } == true -> stop(TrackingProblem.TIMED_OUT)
                lastVisionReport != 0L && now - lastVisionReport >= 1_500 -> stop(TrackingProblem.TIMED_OUT)
                current.phase != TrackingPhase.FOLLOWING && current.phase != TrackingPhase.TARGET_READY && now >= deadline ->
                    stop(TrackingProblem.TIMED_OUT)
            }
        }
    }
}
