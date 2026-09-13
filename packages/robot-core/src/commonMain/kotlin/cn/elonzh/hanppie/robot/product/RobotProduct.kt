package cn.elonzh.hanppie.robot.product

import cn.elonzh.hanppie.robot.protocol.DussFrame

/** Product type reported by RoboMaster's product-information command. */
enum class RobotModel {
    UNKNOWN,
    ROBOMASTER_S1,
    ROBOMASTER_EP,
}

/** Components reported by the system-working-devices push. */
enum class RobotComponent(val deviceId: Int) {
    IMAGE_TRANSMISSION(0x0100),
    CAMERA(0x0104),
    CHASSIS(0x0300),
    BATTERY(0x030a),
    ESC_0(0x0314),
    ESC_1(0x0315),
    ESC_2(0x0316),
    ESC_3(0x0317),
    SERVO_1(0x0318),
    SERVO_2(0x0319),
    SERVO_3(0x031a),
    SERVO_4(0x031b),
    ARM(0x031e),
    CLAW(0x031f),
    GIMBAL(0x0400),
    TOF_1(0x1201),
    TOF_2(0x1202),
    TOF_3(0x1203),
    TOF_4(0x1204),
    SENSOR_ADAPTER_1(0x1601),
    SENSOR_ADAPTER_2(0x1602),
    SENSOR_ADAPTER_3(0x1603),
    SENSOR_ADAPTER_4(0x1604),
    SENSOR_ADAPTER_5(0x1605),
    SENSOR_ADAPTER_6(0x1606),
    WATER_GUN(0x1700),
    INFRARED_GUN(0x1701),
    BACK_ARMOR(0x1801),
    FRONT_ARMOR(0x1802),
    LEFT_ARMOR(0x1803),
    RIGHT_ARMOR(0x1804),
    LEFT_HEAD_ARMOR(0x1805),
    RIGHT_HEAD_ARMOR(0x1806),
    ;

    companion object {
        fun fromDeviceId(deviceId: Int): RobotComponent? = entries.firstOrNull { it.deviceId == deviceId }
    }
}

/** One advertised device and its opaque vendor-defined 16-bit detail values. */
data class WorkingRobotDevice(val deviceId: Int, val details: List<Int>) {
    val component: RobotComponent? get() = RobotComponent.fromDeviceId(deviceId)
}

/** Capability evidence is kept independently from the product model. */
data class RobotCapabilities(val workingDevices: List<WorkingRobotDevice> = emptyList()) {
    val components: Set<RobotComponent> get() = workingDevices.mapNotNullTo(linkedSetOf()) { it.component }
    val unknownDeviceIds: Set<Int> get() = workingDevices.mapNotNullTo(linkedSetOf()) {
        it.deviceId.takeIf { id -> RobotComponent.fromDeviceId(id) == null }
    }

    operator fun contains(component: RobotComponent): Boolean = workingDevices.any { it.deviceId == component.deviceId }
}

data class RobotProduct(
    val model: RobotModel = RobotModel.UNKNOWN,
    val capabilities: RobotCapabilities = RobotCapabilities(),
)

/** Decodes the product and component messages used by DJI's RoboMaster product manager. */
object RobotProductProtocol {
    private const val PRODUCT_SET = 0x3f
    private const val PRODUCT_TYPE_ID = 0xfe
    private const val WORKING_DEVICES_ID = 0x12
    private const val RESPONSE_ATTRIBUTE = 0xc0
    private const val PUSH_ATTRIBUTE = 0x00

    fun model(frame: DussFrame): RobotModel? {
        if (!frame.valid || frame.attr != RESPONSE_ATTRIBUTE || frame.set != PRODUCT_SET ||
            frame.id != PRODUCT_TYPE_ID || frame.payload.size < 2) return null
        return when (frame.payload[1].toInt() and 0xff) {
            1 -> RobotModel.ROBOMASTER_S1
            2 -> RobotModel.ROBOMASTER_EP
            else -> RobotModel.UNKNOWN
        }
    }

    fun capabilities(frame: DussFrame): RobotCapabilities? {
        if (!frame.valid || frame.attr != PUSH_ATTRIBUTE || frame.set != PRODUCT_SET ||
            frame.id != WORKING_DEVICES_ID || frame.payload.isEmpty()) return null
        val expectedDevices = frame.payload.u8(0)
        val devices = ArrayList<WorkingRobotDevice>(expectedDevices)
        var cursor = 1
        repeat(expectedDevices) {
            if (cursor + 3 > frame.payload.size) return null
            val deviceId = frame.payload.u16(cursor)
            val detailCount = frame.payload.u8(cursor + 2)
            cursor += 3
            if (cursor + detailCount * 2 > frame.payload.size) return null
            val details = List(detailCount) { index -> frame.payload.u16(cursor + index * 2) }
            cursor += detailCount * 2
            devices += WorkingRobotDevice(deviceId, details)
        }
        return RobotCapabilities(devices)
    }

    fun updated(current: RobotProduct, frame: DussFrame): RobotProduct? {
        val model = model(frame)
        val capabilities = capabilities(frame)
        if (model == null && capabilities == null) return null
        val updated = current.copy(
            model = model ?: current.model,
            capabilities = capabilities ?: current.capabilities,
        )
        return updated.takeIf { it != current }
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff
    private fun ByteArray.u16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)
}
