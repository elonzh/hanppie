package cn.elonzh.hanppie.robot.tracking

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.vision.*

/** Tracking interprets the complete Info value, unlike marker ID/distance packing. */
enum class TrackingTargetKind { NONE, CANDIDATE, READY, FOLLOWING, UNKNOWN }
val VisionRect.kind: TrackingTargetKind get() = when (info) {
    0 -> TrackingTargetKind.NONE
    1 -> TrackingTargetKind.CANDIDATE
    2 -> TrackingTargetKind.READY
    3 -> TrackingTargetKind.FOLLOWING
    else -> TrackingTargetKind.UNKNOWN
}

/** RoboMaster 1.1.5 native packet constructors; see docs/architecture-robomaster.md. */
object TrackingProtocol {
    const val ENABLE = 0xa3
    const val PUSH = 0xa4
    const val CONFIRM = 0xa7
    const val SELECT = 0xa8
    const val TRACKING_TYPE = 6
    const val HUMAN_TYPE = 1

    fun enable(enabled: Boolean): ByteArray = byteArrayOf(if (enabled) 0x40 else 0, 0)
    fun confirm(start: Boolean): ByteArray = byteArrayOf(0x40, 0, if (start) 1 else 0, 0, 0, 0, 0)
    fun select(x: Float, y: Float): ByteArray {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        return byteArrayOf(0x40, 0, 0) + listOf(x, y, 0f, 0f).flatMap { value ->
            List(4) { (value.toBits() ushr (it * 8)).toByte() }
        }.toByteArray()
    }

    fun push(frame: DussFrame): VisionReport? = VisionProtocol.push(frame)?.takeIf {
        it.type == TRACKING_TYPE || it.type == HUMAN_TYPE
    }
}
