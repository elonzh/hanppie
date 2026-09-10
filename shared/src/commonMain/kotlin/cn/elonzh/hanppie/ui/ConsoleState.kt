package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import cn.elonzh.hanppie.robot.DiscoveredRobot
import cn.elonzh.hanppie.robot.GimbalTelemetry

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
    val scriptMessage: UiText = uiText(Res.string.not_uploaded),
    val uploadedSource: String? = null,
    val scriptMessages: List<String> = emptyList(),
    val executionUncertain: Boolean = false,
) {
    val status: String get() = statusMessage.resolve()
    val scriptStatus: String get() = scriptMessage.resolve()
    fun canUpload(source: String): Boolean = connected && !busy && !executionUncertain && source.isNotBlank()
    fun canStart(source: String, armed: Boolean): Boolean =
        connected && !busy && !executionUncertain && armed && uploadedSource != null && source == uploadedSource
    val canStop: Boolean get() = connected && !busy

    fun lost(reason: String): ConsoleState = copy(connected = false, connectedAddress = null, reconnecting = false, statusMessage = uiText(Res.string.connection_lost), error = reason,
        uploadedSource = null, battery = null, signalQuality = null, values = emptyList(), gimbal = null,
        scriptMessage = uiText(Res.string.connection_lost_robot_execution_state_unknown))
}
