package cn.elonzh.hanppie.robot.lab

import kotlinx.serialization.Serializable

@Serializable
internal enum class ScriptRunPhase {
    IDLE, UPLOADING, STARTING, RUNNING, COMPLETING, COMPLETED, FAILED, STOPPING, STOP_UNCONFIRMED, UNKNOWN;

    val active: Boolean get() = this == UPLOADING || this == STARTING || this == RUNNING ||
        this == COMPLETING || this == STOPPING || this == UNKNOWN
    val progressing: Boolean get() = this == UPLOADING || this == STARTING || this == RUNNING ||
        this == COMPLETING || this == STOPPING
    val mayBeExecuting: Boolean get() = this == STARTING || this == RUNNING || this == COMPLETING ||
        this == STOPPING || this == UNKNOWN
}
