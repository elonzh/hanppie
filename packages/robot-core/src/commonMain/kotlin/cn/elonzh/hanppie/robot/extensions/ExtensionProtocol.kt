package cn.elonzh.hanppie.robot.extensions

import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.robot.protocol.*

enum class ExtensionKind(private val firstDeviceId: Int, val count: Int) {
    SERVO(0x0318, 4), TOF(0x1201, 4), ADAPTER(0x1601, 6);

    fun index(component: RobotComponent): Int? = (component.deviceId - firstDeviceId + 1).takeIf { it in 1..count }
    fun component(index: Int): RobotComponent {
        require(index in 1..count)
        return requireNotNull(RobotComponent.fromDeviceId(firstDeviceId + index - 1))
    }
}

sealed interface ExtensionReading { val component: RobotComponent }
data class ServoReading(override val component: RobotComponent, val valid: Boolean, val speedRaw: Int, val angleRaw: Int) : ExtensionReading {
    /** The ordinary App's display conversion, independent of installation/calibration. */
    val angleDegrees: Double get() = angleRaw * 360.0 / 2048.0 - 180.0
}
data class TofReading(override val component: RobotComponent, val commandRaw: Int, val directionRaw: Int,
    val flagRaw: Int, val distanceMillimetres: Int) : ExtensionReading
data class AdapterReading(override val component: RobotComponent, val io1: Int, val io2: Int,
    val adc1: Int, val adc2: Int) : ExtensionReading

/** Ordinary client subscribes to one family when its module details are visible, at 10 Hz. */
internal fun ExtensionKind.topic(): PeriodicTopic = when (this) {
    ExtensionKind.SERVO -> PeriodicTopic(17, 0x000200095f0059e7L, 10, 17)
    ExtensionKind.TOF -> PeriodicTopic(18, 0x0002000986e4c05aL, 10, 20)
    ExtensionKind.ADAPTER -> PeriodicTopic(19, 0x00020009eebb9ffcL, 10, 36)
}

internal fun ExtensionKind.readings(frame: DussFrame): List<ExtensionReading>? = topic().data(frame)?.let { p ->
    fun signed16(offset: Int) = p.u16(offset).toShort().toInt()
    List(count) { i ->
        val component = component(i + 1)
        when (this) {
            ExtensionKind.SERVO -> ServoReading(component, p.u8(0) and (1 shl i) != 0, signed16(1 + i * 2), signed16(9 + i * 2))
            ExtensionKind.TOF -> TofReading(component, p.u8(i * 5), p.u8(i * 5 + 1), p.u8(i * 5 + 2),
                (p.u8(i * 5 + 3) shl 8) or p.u8(i * 5 + 4))
            ExtensionKind.ADAPTER -> AdapterReading(component, p.u8(i * 6), p.u8(i * 6 + 1), p.u16(i * 6 + 2), p.u16(i * 6 + 4))
        }
    }
}
