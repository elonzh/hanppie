package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import cn.elonzh.hanppie.robot.DiscoveredRobot
import cn.elonzh.hanppie.robot.GimbalTelemetry

internal enum class ScriptRunPhase {
    IDLE, UPLOADING, STARTING, RUNNING, COMPLETING, COMPLETED, FAILED, STOPPING, STOPPED, UNKNOWN;

    val visible: Boolean get() = this != IDLE
    val active: Boolean get() = this == UPLOADING || this == STARTING || this == RUNNING ||
        this == COMPLETING || this == STOPPING || this == UNKNOWN
    val mayBeExecuting: Boolean get() = this == STARTING || this == RUNNING || this == COMPLETING ||
        this == STOPPING || this == UNKNOWN
}

internal data class ConsoleState(
    val connected: Boolean = false,
    val connectedAddress: String? = null,
    val reconnecting: Boolean = false,
    val busy: Boolean = false,
    val statusMessage: UiText = uiText(Res.string.disconnected),
    val error: String? = null,
    val devices: List<DiscoveredRobot> = emptyList(),
    val packets: Long = 0,
    val battery: Int? = null,
    val signalQuality: Int? = null,
    val values: List<Pair<String, String>> = emptyList(),
    val gimbal: GimbalTelemetry? = null,
    val logs: List<String> = emptyList(),
    val frames: List<String> = emptyList(),
    val scriptMessage: UiText = uiText(Res.string.no_script_running),
    val scriptMessages: List<String> = emptyList(),
    val scriptRunId: String? = null,
    val scriptTitle: String? = null,
    val scriptRunPhase: ScriptRunPhase = ScriptRunPhase.IDLE,
    val scriptStartedAtEpochMillis: Long? = null,
    val scriptFinishedAtEpochMillis: Long? = null,
) {
    val status: String get() = statusMessage.resolve()
    val scriptStatus: String get() = scriptMessage.resolve()
    fun canRun(source: String): Boolean = connected && !busy && !scriptRunPhase.mayBeExecuting && source.isNotBlank()
    val canStop: Boolean get() = connected && !busy &&
        (scriptRunPhase == ScriptRunPhase.STARTING || scriptRunPhase == ScriptRunPhase.RUNNING ||
            scriptRunPhase == ScriptRunPhase.UNKNOWN)

    fun lost(reason: String): ConsoleState = copy(connected = false, connectedAddress = null, reconnecting = false, statusMessage = uiText(Res.string.connection_lost), error = reason,
        battery = null, signalQuality = null, values = emptyList(), gimbal = null,
        scriptRunPhase = if (scriptRunPhase.mayBeExecuting) ScriptRunPhase.UNKNOWN else scriptRunPhase,
        scriptMessage = if (scriptRunPhase.mayBeExecuting) uiText(Res.string.connection_lost_robot_execution_state_unknown) else scriptMessage)
}
