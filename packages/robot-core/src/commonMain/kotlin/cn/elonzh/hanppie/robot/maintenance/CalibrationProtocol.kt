package cn.elonzh.hanppie.robot.maintenance

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.Volatile

internal enum class ImuCalibrationStatus { WAITING, IN_PROGRESS, FINISHED, FAILED, UNKNOWN }
enum class ImuCalibrationSide(val mask: Int, val wireCode: Int) {
    HORIZONTAL(1, 4), FRONT(2, 0), LEFT(4, 3), BACK(8, 1), RIGHT(16, 2);
    companion object { fun fromWire(code: Int?): ImuCalibrationSide? = entries.firstOrNull { it.wireCode == code } }
}
internal enum class GimbalCalibrationStatus { IDLE, IN_PROGRESS, FAILED, UNKNOWN }

internal data class ImuCalibrationReport(
    val statusRaw: Int,
    val progressRaw: Int,
    val uninterpretedByte: Int,
    val sideRaw: Int,
    val finishedMaskRaw: Int,
) {
    val status: ImuCalibrationStatus get() = when (statusRaw) {
        0 -> ImuCalibrationStatus.WAITING
        3 -> ImuCalibrationStatus.IN_PROGRESS
        0x51 -> ImuCalibrationStatus.FINISHED
        in 0x80..0xff -> ImuCalibrationStatus.FAILED
        else -> ImuCalibrationStatus.UNKNOWN
    }
    val progress: Int? get() = progressRaw.takeIf { it in 0..100 }
    // Native wire side values are indices, not the bit masks exposed by the C# API.
    val side: ImuCalibrationSide? get() = ImuCalibrationSide.fromWire(sideRaw)
    val finishedSides: Set<ImuCalibrationSide> get() = ImuCalibrationSide.entries.filterTo(mutableSetOf()) {
        finishedMaskRaw and it.mask != 0
    }
}

internal data class GimbalCalibrationReport(val progressRaw: Int, val statusRaw: Int) {
    val progress: Int? get() = progressRaw.takeIf { it in 0..100 }
    val status: GimbalCalibrationStatus get() = when (statusRaw) {
        0 -> GimbalCalibrationStatus.IDLE
        1 -> GimbalCalibrationStatus.IN_PROGRESS
        3 -> GimbalCalibrationStatus.FAILED
        else -> GimbalCalibrationStatus.UNKNOWN
    }
}

/**
 * Existing message 1 contains battery (10 bytes), then IMU calibration (5 bytes).
 * The UID order belongs to this session's fixed subscription, not to arbitrary 62-byte frames.
 */
internal fun imuCalibrationReport(frame: DussFrame): ImuCalibrationReport? {
    val p = frame.payload
    if (!frame.valid || frame.sender != 9 || frame.receiver != 2 || frame.attr != 0 || frame.set != 0x48 || frame.id != 8 ||
        p.size != 62 || p.u8(0) != 0 || p.u8(1) != 1) return null
    return ImuCalibrationReport(p.u8(12), p.u8(13), p.u8(14), p.u8(15), p.u8(16))
}

internal fun gimbalCalibrationReport(frame: DussFrame): GimbalCalibrationReport? {
    if (!frame.valid || frame.sender != 4 || frame.receiver != 2 || frame.attr != 0 || frame.set != 4 || frame.id != 0x30 ||
        frame.payload.size < 2) return null
    return GimbalCalibrationReport(frame.payload.u8(0), frame.payload.u8(1))
}

/**
 * Native constructors only; the caller owns task admission, observation and interrupted results.
 * No independent stop command is established for gimbal auto-calibration or the arm's zero write.
 */
internal class CalibrationProtocol(private val request: suspend (Int, Int, Int, ByteArray) -> ByteArray) {
    @Volatile private var closed = false
    suspend fun startImu() { command(3, 3, 0xf9, imuCalibrationCommand(true)) }
    suspend fun stopImu() { command(3, 3, 0xf9, imuCalibrationCommand(false)) }
    suspend fun startGimbal() { command(4, 4, 8, byteArrayOf()) }
    suspend fun calibrateArm() { command(0xc3, 0x33, 0x69, byteArrayOf()) }
    private suspend fun command(node: Int, set: Int, id: Int, payload: ByteArray) {
        currentCoroutineContext().ensureActive()
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
        request(node, set, id, payload)
    }
    fun close() { closed = true }
}

/** Native hash of imu_cali_cmd_start_clai_0, followed by the one-byte command 1 (start) or 3 (stop). */
internal fun imuCalibrationCommand(start: Boolean): ByteArray =
    byteArrayOf(0xe4.toByte(), 0xa3.toByte(), 0x99.toByte(), 0x7d, if (start) 1 else 3)
