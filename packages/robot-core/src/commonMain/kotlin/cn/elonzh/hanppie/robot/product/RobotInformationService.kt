package cn.elonzh.hanppie.robot.product

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.telemetry.RobotSystemStatus
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Native App addresses, not product-model support lists or firmware-package identifiers. */
enum class RobotVersionTarget(val node: Int, val componentId: Int? = null, val decimalBuild: Boolean = false) {
    SYSTEM(0x28), SYSTEM_CAN(0x49), PROGRAM_RUNTIME(0xa9),
    CAMERA(0x01, 0x0104, true), CHASSIS(0xc3, 0x0300), BATTERY(0x0b, 0x030a),
    GIMBAL(0x04, 0x0400), GIMBAL_MOTOR(0x24, 0x0400),
    MOTOR_FRONT_RIGHT(0x0c, 0x0314), MOTOR_FRONT_LEFT(0x2c, 0x0315),
    MOTOR_REAR_LEFT(0x4c, 0x0316), MOTOR_REAR_RIGHT(0x6c, 0x0317),
    ARMOR_BACK(0x38, 0x1801), ARMOR_FRONT(0x58, 0x1802), ARMOR_LEFT(0x78, 0x1803),
    ARMOR_RIGHT(0x98, 0x1804), ARMOR_HEAD_LEFT(0xb8, 0x1805), ARMOR_HEAD_RIGHT(0xd8, 0x1806),
    BLASTER(0x17, 0x1700), INFRARED_BLASTER(0x37, 0x1701),
    SERVO_1(0x39, 0x0318), SERVO_2(0x59, 0x0319), SERVO_3(0x79, 0x031a), SERVO_4(0x99, 0x031b),
    TOF_1(0x32, 0x1201), TOF_2(0x52, 0x1202), TOF_3(0x72, 0x1203), TOF_4(0x92, 0x1204),
    ADAPTER_1(0x36, 0x1601), ADAPTER_2(0x56, 0x1602), ADAPTER_3(0x76, 0x1603),
    ADAPTER_4(0x96, 0x1604), ADAPTER_5(0xb6, 0x1605), ADAPTER_6(0xd6, 0x1606),
    CLAW(0x3b, 0x031f), VISION(0xf1, 0x0100), PERCEPTION(0xc9, 0x0100),
}

interface RobotInformationService {
    val systemStatus: kotlinx.coroutines.flow.StateFlow<RobotSystemStatus?>
    suspend fun readFirmware(target: RobotVersionTarget): String
    /** Native read operation only. This service cannot activate a device or contact a server. */
    suspend fun readActivated(): Boolean
}

internal class InformationCommands(private val request: suspend (Int, Int, Int, ByteArray) -> ByteArray,
    private val nowMillis: () -> Long = { robotNanoTime() / 1_000_000 }) : RobotInformationService {
    private val lock = RobotLock()
    private var closed = false
    private var statusAt: Long? = null
    private val mutableStatus = MutableStateFlow<RobotSystemStatus?>(null)
    override val systemStatus = mutableStatus.asStateFlow()

    private suspend fun command(node: Int, set: Int, id: Int, payload: ByteArray): ByteArray {
        currentCoroutineContext().ensureActive()
        lock.withLock { if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED) }
        return request(node, set, id, payload)
    }

    override suspend fun readFirmware(target: RobotVersionTarget): String {
        val p = command(target.node, Protocol.CMDSET_COMMON, Protocol.CMD_GET_DEVICE_VERSION, byteArrayOf())
        if (p.size < 26) invalid()
        val major = p.u8(25); val minor = p.u8(24)
        val third: Int; val fourth: Int
        if (target.decimalBuild) {
            val build = p.u16(22)
            third = (build / 100) and 0xff; fourth = build % 100
        } else { third = p.u8(23); fourth = p.u8(22) }
        return listOf(major, minor, third, fourth).joinToString(".") { it.toString().padStart(2, '0') }
    }

    override suspend fun readActivated(): Boolean {
        val p = command(Protocol.HOST_SYSTEM, Protocol.CMDSET_COMMON, 0x32, byteArrayOf(0x11, 0))
        if (p.size < 2) invalid()
        return p.u8(1) and 1 != 0
    }

    fun receive(frame: DussFrame) = lock.withLock {
        if (closed || !frame.valid || frame.sender != Protocol.HOST_SYSTEM || frame.receiver != Protocol.HOST_MOBILE ||
            frame.attr != Protocol.ATTR_NO_ACK || frame.set != Protocol.CMDSET_RM || frame.id != 3 || frame.payload.size < 12) return@withLock
        fun int(offset: Int) = (0..3).fold(0) { value, index -> value or (frame.payload.u8(offset + index) shl (index * 8)) }
        statusAt = nowMillis()
        mutableStatus.value = RobotSystemStatus(int(4).takeIf { it >= 0 }, int(8).takeIf { it >= 0 }, frame.payload.u8(0) and 1 != 0)
    }
    fun updateAge() = lock.withLock {
        if (!closed) mutableStatus.value = systemStatus.value?.copy(reportAgeSeconds = statusAt?.let { ((nowMillis() - it) / 1_000).coerceAtLeast(0) } ?: 0)
    }
    fun close() = lock.withLock { closed = true; mutableStatus.value = null }
}

private fun invalid(): Nothing = throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
