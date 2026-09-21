package cn.elonzh.hanppie.robot.lab

/**
 * Native execution status pushed by the robot firmware via DUSS 0x3F / 0xA5
 * (DUSS_MB_CMD_RM_SCRIPT_BLOCK_STATUS_PUSH).
 */
data class LabScriptStatus(
    val status: Int,
    val guid: String,
    val traceback: String? = null,
) {
    val isIdle: Boolean get() = status == 0
    val isPreparing: Boolean get() = status == 1
    val isRunning: Boolean get() = status == 2
    val isFailed: Boolean get() = status == 5
}
