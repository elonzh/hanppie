package cn.elonzh.hanppie.robot.telemetry

import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.RobotLock
import cn.elonzh.hanppie.robot.session.robotNanoTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Native units have not been established. The signed temperature and current remain raw. */
data class BatteryTelemetry(val voltageRaw: Int, val temperatureRaw: Int, val currentRaw: Int, val percent: Int?)

data class RobotIssue(val deviceId: Int, val detail: Int) {
    val code: Int get() = (deviceId shl 16) or detail
    val severity: Int get() = (detail ushr 14) and 3
    val subDevice: Int get() = (detail ushr 8) and 0x3f
    val message: Int get() = detail and 0xff
    val hexCode: String get() = "0x" + code.toUInt().toString(16).uppercase().padStart(8, '0')
    /** Same chassis submodule association as the official device-status view. */
    val affectedDeviceId: Int get() = when (deviceId) {
        0x0300 -> when (subDevice) { 2 -> 0x030a; 5 -> 0x0400; in 11..14 -> 0x0314 + subDevice - 11; else -> deviceId }
        0x0100 -> when (detail) { 0x14 -> 0x030a; in 0x15..0x18 -> 0x0314 + detail - 0x15
            in 0x1a..0x1f -> 0x1801 + detail - 0x1a; else -> deviceId }
        else -> deviceId
    }
}

data class RobotHealth(val modules: List<WorkingRobotDevice>? = null, val seenDeviceIds: Set<Int> = emptySet(),
    val battery: BatteryTelemetry? = null, val moduleReportAgeSeconds: Long = 0) {
    val issues: List<RobotIssue> get() = modules.orEmpty().flatMap { device -> device.details.map { RobotIssue(device.deviceId, it) } }
        .distinctBy { it.code }.sortedByDescending { it.severity }
    val unreportedDeviceIds: Set<Int> get() = if (modules == null) emptySet() else seenDeviceIds - modules.map { it.deviceId }.toSet()
}

/** Module reports are snapshots with an age; the existing periodic battery stream expires independently. */
internal class RobotHealthStore(private val nowMillis: () -> Long = { robotNanoTime() / 1_000_000 }) {
    private val lock = RobotLock()
    private val mutable = MutableStateFlow(RobotHealth())
    val state = mutable.asStateFlow()
    private var modulesAt: Long? = null
    private var batteryAt: Long? = null
    private var closed = false

    fun receive(frame: DussFrame) = lock.withLock {
        if (closed || !frame.valid || frame.receiver != Protocol.HOST_MOBILE) return@withLock
        if (frame.sender == Protocol.HOST_SYSTEM && frame.attr == Protocol.ATTR_NO_ACK) RobotProductProtocol.capabilities(frame)?.let { capabilities ->
            modulesAt = nowMillis()
            mutable.value = state.value.copy(modules = capabilities.workingDevices,
                seenDeviceIds = state.value.seenDeviceIds + capabilities.workingDevices.map { it.deviceId }, moduleReportAgeSeconds = 0)
        }
        if (frame.sender == Protocol.HOST_HDVT_UAV && frame.attr in listOf(Protocol.ATTR_NO_ACK, Protocol.ATTR_NEED_ACK) &&
            frame.set == Protocol.CMDSET_VIRTUAL_BUS &&
            frame.id == Protocol.CMD_VBUS_DATA_ANALYSIS && frame.payload.size == 62 && frame.payload.u8(0) == 0 && frame.payload.u8(1) == 1) {
            batteryAt = nowMillis()
            mutable.value = state.value.copy(battery = decodeBattery(frame.payload, 2))
        }
    }

    fun expire() = lock.withLock {
        if (closed) return@withLock
        val now = nowMillis()
        mutable.value = state.value.copy(
            moduleReportAgeSeconds = modulesAt?.let { ((now - it) / 1_000).coerceAtLeast(0) } ?: 0,
            battery = state.value.battery.takeUnless { batteryAt?.let { now - it >= 3_000 } == true })
    }
    fun close() = lock.withLock { closed = true; mutable.value = RobotHealth() }
}

internal fun decodeBattery(payload: ByteArray, offset: Int): BatteryTelemetry = BatteryTelemetry(
    payload.u16(offset), payload.u16(offset + 2).toShort().toInt(),
    (0..3).fold(0) { value, index -> value or (payload.u8(offset + 4 + index) shl (index * 8)) },
    payload.u8(offset + 8).takeIf { it <= 100 })
