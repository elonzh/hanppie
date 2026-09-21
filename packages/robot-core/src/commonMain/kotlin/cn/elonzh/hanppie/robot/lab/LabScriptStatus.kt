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
    val isIdle: Boolean get() = status == STATUS_IDLE
    val isPreparing: Boolean get() = status == STATUS_PREPARING
    val isRunning: Boolean get() = status == STATUS_RUNNING
    val isFailed: Boolean get() = status == STATUS_FAILED

    companion object {
        const val STATUS_IDLE = 0
        const val STATUS_PREPARING = 1
        const val STATUS_RUNNING = 2
        const val STATUS_FAILED = 5
    }
}
