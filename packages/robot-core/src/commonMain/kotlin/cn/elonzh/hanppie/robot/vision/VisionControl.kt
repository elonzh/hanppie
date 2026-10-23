package cn.elonzh.hanppie.robot.vision

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.u8
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VisionMode(val mask: Int, val reportType: Int) {
    ROBOT_AIM(8, 3), MARKERS(32, 5), HEAD_SHOULDERS(1, 0), PEOPLE(2, 1), POSE(4, 2), LINES(16, 4);
    val supportsColor: Boolean get() = this == MARKERS || this == LINES
}
enum class VisionPhase { OFF, CHECKING, STARTING, ACTIVE, STOPPING, ERROR, CLOSED }
enum class VisionProblem { RESOURCE_BUSY, REJECTED, TIMED_OUT, MODE_MISMATCH, TARGET_LOST, DEVICE_ERROR, UNSUPPORTED_REPORT, STOP_UNCONFIRMED }

data class VisionState(
    val phase: VisionPhase = VisionPhase.OFF,
    val mode: VisionMode? = null,
    val mask: Int? = null,
    val report: VisionReport? = null,
    val reportAgeMillis: Long? = null,
    val rectangles: List<VisionRect> = emptyList(),
    val problem: VisionProblem? = null,
    val responseCode: Int? = null,
    val rejectedCommand: Int? = null,
    val enableMayBeActive: Boolean = false,
    val color: VisionColor? = null,
    val colorAccepted: Boolean = false,
    val linePoints: List<VisionLinePoint> = emptyList(),
) {
    val ownsPipeline: Boolean get() = phase in listOf(VisionPhase.CHECKING, VisionPhase.STARTING, VisionPhase.ACTIVE, VisionPhase.STOPPING) ||
        problem == VisionProblem.STOP_UNCONFIRMED
    val ownsGimbal: Boolean get() = mode == VisionMode.ROBOT_AIM && enableMayBeActive
    val blocksManualControl: Boolean get() = ownsGimbal && phase != VisionPhase.ACTIVE
}

/** Session-owned transaction machine. Calls, receive and tick share the session transmit lock. */
class VisionControl(
    private val send: (Int, Int, ByteArray) -> Int,
    private val nowMillis: () -> Long,
    private val stopGimbal: () -> Unit,
) {
    private enum class Command { INSPECT, RESET, COLOR, ENABLE, VERIFY, DISABLE, VERIFY_STOP }
    private data class Pending(val command: Command, val receiver: Int, val id: Int, val sequence: Int, val sentAt: Long)
    private val mutableState = MutableStateFlow(VisionState())
    val state: StateFlow<VisionState> = mutableState.asStateFlow()
    private var pending: Pending? = null
    private var lastReportAt: Long? = null
    private var firstReportDeadline = 0L
    private var targetSeen = false
    private var stopAttempts = 0
    private var stopProblem: VisionProblem? = null

    fun start(mode: VisionMode, color: VisionColor? = null) {
        check(state.value.phase != VisionPhase.CLOSED && !state.value.ownsPipeline)
        require(color == null || mode.supportsColor)
        mutableState.value = VisionState(phase = VisionPhase.CHECKING, mode = mode, color = color)
        lastReportAt = null; targetSeen = false; stopProblem = null; stopAttempts = 0
        command(Command.INSPECT, VisionProtocol.GET_ENABLE, byteArrayOf())
    }

    fun stop(problem: VisionProblem? = null) {
        if (state.value.phase == VisionPhase.ERROR && !state.value.ownsPipeline) {
            lastReportAt = null
            mutableState.value = VisionState()
            return
        }
        if (!state.value.ownsPipeline || state.value.phase == VisionPhase.STOPPING) return
        pending = null
        stopProblem = problem
        if (!state.value.enableMayBeActive) {
            finishStop()
            return
        }
        mutableState.value = state.value.copy(phase = VisionPhase.STOPPING, rectangles = emptyList(), linePoints = emptyList(), problem = null)
        stopAttempts = 0
        sendStop()
    }

    private fun sendStop() {
        stopAttempts++
        command(Command.DISABLE, VisionProtocol.ENABLE, VisionProtocol.enable(0))
        if (state.value.ownsGimbal) runCatching(stopGimbal)
    }

    private fun command(command: Command, id: Int, payload: ByteArray, receiver: Int = Protocol.HOST_VISION) {
        try {
            pending = Pending(command, receiver, id, send(receiver, id, payload), nowMillis())
        } catch (_: Exception) {
            pending = null
            if (state.value.enableMayBeActive) unconfirmed()
            else fail(VisionProblem.TIMED_OUT)
        }
    }

    private fun fail(problem: VisionProblem) {
        pending = null
        mutableState.value = state.value.copy(phase = VisionPhase.ERROR, rectangles = emptyList(), linePoints = emptyList(), problem = problem)
    }

    private fun unconfirmed() {
        fail(VisionProblem.STOP_UNCONFIRMED)
        if (state.value.ownsGimbal) runCatching(stopGimbal)
    }

    private fun finishStop() {
        pending = null
        if (stopProblem == null) lastReportAt = null
        mutableState.value = if (stopProblem == null) VisionState() else
            state.value.copy(phase = VisionPhase.ERROR, mask = 0, rectangles = emptyList(), linePoints = emptyList(),
                problem = stopProblem, enableMayBeActive = false)
    }

    private fun enable() {
        // Mark before transmission: a send failure cannot prove the device stayed off.
        mutableState.value = state.value.copy(enableMayBeActive = true)
        if (state.value.ownsGimbal) runCatching(stopGimbal)
        command(Command.ENABLE, VisionProtocol.ENABLE, VisionProtocol.enable(requireNotNull(state.value.mode).mask))
    }

    fun receive(frame: DussFrame) {
        if (state.value.phase == VisionPhase.CLOSED) return
        val expected = pending
        if (expected != null && frame.valid && frame.sender == expected.receiver && frame.receiver == Protocol.HOST_MOBILE &&
            frame.attr == Protocol.ATTR_RESP_NEED_ACK && frame.set == Protocol.CMDSET_VISION &&
            frame.id == expected.id && frame.sequence == expected.sequence && frame.payload.isNotEmpty()) {
            pending = null
            if (frame.payload.u8(0) != 0) {
                mutableState.value = state.value.copy(responseCode = frame.payload.u8(0), rejectedCommand = frame.id)
                if (expected.command in listOf(Command.DISABLE, Command.VERIFY_STOP)) unconfirmed()
                else if (state.value.enableMayBeActive) stop(VisionProblem.REJECTED)
                else fail(VisionProblem.REJECTED)
                return
            }
            when (expected.command) {
                Command.INSPECT -> {
                    val mask = VisionProtocol.enabled(frame.payload)
                    mutableState.value = state.value.copy(mask = mask)
                    when {
                        mask == null -> fail(VisionProblem.UNSUPPORTED_REPORT)
                        mask != 0 -> fail(VisionProblem.RESOURCE_BUSY)
                        else -> {
                            mutableState.value = state.value.copy(phase = VisionPhase.STARTING)
                            command(Command.RESET, VisionProtocol.ENABLE, VisionProtocol.enable(0))
                        }
                    }
                }
                Command.RESET -> {
                    val color = state.value.color
                    if (color == null) enable() else command(Command.COLOR, VisionProtocol.SET_COLOR,
                        VisionProtocol.color(requireNotNull(state.value.mode), color), VisionProtocol.COLOR_RECEIVER)
                }
                Command.COLOR -> {
                    mutableState.value = state.value.copy(colorAccepted = true)
                    enable()
                }
                Command.ENABLE -> command(Command.VERIFY, VisionProtocol.GET_ENABLE, byteArrayOf())
                Command.VERIFY -> {
                    val mask = VisionProtocol.enabled(frame.payload)
                    mutableState.value = state.value.copy(mask = mask)
                    if (mask != state.value.mode?.mask) stop(if (mask == null) VisionProblem.UNSUPPORTED_REPORT else VisionProblem.MODE_MISMATCH)
                    else {
                        firstReportDeadline = nowMillis() + 3_000
                        mutableState.value = state.value.copy(phase = VisionPhase.ACTIVE)
                    }
                }
                Command.DISABLE -> command(Command.VERIFY_STOP, VisionProtocol.GET_ENABLE, byteArrayOf())
                Command.VERIFY_STOP -> {
                    val mask = VisionProtocol.enabled(frame.payload)
                    mutableState.value = state.value.copy(mask = mask)
                    if (mask == 0) finishStop() else unconfirmed()
                }
            }
            return
        }
        val current = state.value
        if (current.phase !in listOf(VisionPhase.STARTING, VisionPhase.ACTIVE) || !current.enableMayBeActive) return
        val report = VisionProtocol.push(frame)?.takeIf { it.type == current.mode?.reportType } ?: return
        lastReportAt = nowMillis()
        mutableState.value = current.copy(report = report, reportAgeMillis = 0,
            rectangles = if (current.phase == VisionPhase.ACTIVE) report.rectangles else emptyList(),
            linePoints = if (current.phase == VisionPhase.ACTIVE) report.linePoints else emptyList())
        when {
            report.error != 0 -> stop(VisionProblem.DEVICE_ERROR)
            report.deviceStatus == null -> stop(VisionProblem.UNSUPPORTED_REPORT)
            current.mode == VisionMode.ROBOT_AIM && report.rectangles.any { it.info !in listOf(4, 5) } -> stop(VisionProblem.UNSUPPORTED_REPORT)
            current.mode == VisionMode.ROBOT_AIM && targetSeen && report.rectangles.isEmpty() -> stop(VisionProblem.TARGET_LOST)
            report.rectangles.isNotEmpty() -> targetSeen = true
        }
    }

    fun tick(operatorPresent: Boolean) {
        if (state.value.phase == VisionPhase.CLOSED) return
        val now = nowMillis()
        lastReportAt?.let { at ->
            val age = (now - at).coerceAtLeast(0)
            mutableState.value = state.value.copy(reportAgeMillis = age,
                rectangles = if (age < 750) state.value.rectangles else emptyList(),
                linePoints = if (age < 750) state.value.linePoints else emptyList())
        }
        val current = state.value
        if (current.phase == VisionPhase.STOPPING) {
            if (pending?.let { now - it.sentAt >= 500 } == true) {
                if (stopAttempts < 3) sendStop() else unconfirmed()
            }
        } else if (current.ownsPipeline) {
            when {
                !operatorPresent -> stop()
                pending?.let { now - it.sentAt >= 1_500 } == true -> stop(VisionProblem.TIMED_OUT)
                current.phase == VisionPhase.ACTIVE && (lastReportAt?.let { now - it >= 1_500 } ?: (now >= firstReportDeadline)) ->
                    stop(VisionProblem.TIMED_OUT)
            }
        }
    }

    fun close(sendCleanup: Boolean = true) {
        if (state.value.phase == VisionPhase.CLOSED) return
        if (sendCleanup && state.value.enableMayBeActive) {
            runCatching { send(Protocol.HOST_VISION, VisionProtocol.ENABLE, VisionProtocol.enable(0)) }
            if (state.value.ownsGimbal) runCatching(stopGimbal)
        }
        pending = null
        mutableState.value = VisionState(phase = VisionPhase.CLOSED)
    }
}
