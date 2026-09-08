package cn.elonzh.hanppie.desktop

import cn.elonzh.hanppie.robot.DiscoveredRobot

internal data class ConsoleState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val status: String = tr("未连接"),
    val error: String? = null,
    val devices: List<DiscoveredRobot> = emptyList(),
    val packets: Long = 0,
    val battery: Int? = null,
    val values: List<Pair<String, String>> = emptyList(),
    val logs: List<String> = emptyList(),
    val frames: List<String> = emptyList(),
    val scriptStatus: String = tr("尚未上传"),
    val uploadedSource: String? = null,
    val scriptMessages: List<String> = emptyList(),
    val executionUncertain: Boolean = false,
) {
    fun canUpload(source: String): Boolean = connected && !busy && !executionUncertain && source.isNotBlank()
    fun canStart(source: String, armed: Boolean): Boolean =
        connected && !busy && !executionUncertain && armed && uploadedSource != null && source == uploadedSource
    val canStop: Boolean get() = connected && !busy

    fun lost(reason: String): ConsoleState = copy(connected = false, status = tr("连接失效"), error = reason,
        uploadedSource = null, battery = null, values = emptyList(), scriptStatus = tr("连接失效；机内执行状态未知"))
}
