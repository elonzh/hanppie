package cn.elonzh.hanppie.robot.extensions.arm

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException

/** A binding is distinct from online servo discovery and physical calibration. */
data class ArmServoBinding(val main: Int, val secondary: Int) {
    val installed: Boolean get() = main in 1..3 && secondary in 1..3 && main != secondary
    val uninstalled: Boolean get() = main == 255 && secondary == 255
    companion object { val Uninstalled = ArmServoBinding(255, 255) }
}

enum class ArmLoadMode(val raw: Int) { FREE(0), ON_LOAD(1) }

/** Native coordinates retain their signed integer representation; units are not device-verified. */
data class ArmPosition(val flagsRaw: Int, val xRaw: Int, val yRaw: Int) {
    val limitX: Boolean get() = flagsRaw and 1 != 0
    val limitY: Boolean get() = flagsRaw and 2 != 0
    val blocked: Boolean get() = flagsRaw and 12 != 0
}

data class ClawWarnings(val raw: Int, val sender: Int) {
    val openBlocked: Boolean get() = raw and 3 == 1
    val closeBlocked: Boolean get() = raw and 3 == 2
    val moveUpBlocked: Boolean get() = raw and 12 == 4
    val moveDownBlocked: Boolean get() = raw and 12 == 8
    val moveUpLimit: Boolean get() = raw and 48 == 16
    val moveDownLimit: Boolean get() = raw and 48 == 32
}

/** Host-chosen message IDs do not overlap this App session's existing 1/10/13/14 topics. */
internal object ArmTopic {
    val ARM = PeriodicTopic(15, 0x0002000926abd64dL, 10, 9)
    val CLAW = PeriodicTopic(16, 0x00020009124d156aL, 5, 1)
    val entries = listOf(ARM, CLAW)
}

internal fun decodeArmBinding(payload: ByteArray): ArmServoBinding {
    if (payload.size < 3) throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
    return ArmServoBinding(payload.u8(1), payload.u8(2))
}

internal fun armBindingPayload(binding: ArmServoBinding): ByteArray {
    require(binding.installed || binding.uninstalled)
    return byteArrayOf(binding.main.toByte(), binding.secondary.toByte(), 0)
}

/** Native 33:10 struct, not DJIArmControlParamValue's in-process packed integer. */
internal fun armControlPayload(x: Double, y: Double, claw: Double, armValid: Boolean, clawValid: Boolean): ByteArray {
    require(listOf(x, y, claw).all { it.isFinite() && it in -1.0..1.0 })
    return ByteArray(10).apply {
        this[0] = ((if (armValid) 7 else 0) or (if (clawValid) 8 else 0)).toByte()
        // Ordinary App controls use mode=0. Sustained-load mode is a separate 33:21 setting.
        listOf(x, y, 0.0, claw).forEachIndexed { index, value -> put16(2 + index * 2, (1024 + value * 660).toInt()) }
    }
}

internal fun armPosition(frame: DussFrame): ArmPosition? = ArmTopic.ARM.data(frame)?.let { p ->
    fun signed(offset: Int) = (0..3).fold(0) { value, i -> value or (p.u8(offset + i) shl (8 * i)) }
    ArmPosition(p.u8(0), signed(1), signed(5))
}

/** Native observers use GetDeviceID()=0; retain the source instead of inventing a source-node filter. */
internal fun armNativePush(frame: DussFrame, id: Int): Int? = frame.payload.takeIf {
    frame.valid && frame.receiver == Protocol.HOST_MOBILE && frame.attr == Protocol.ATTR_NO_ACK &&
        frame.set == 0x33 && frame.id == id && it.isNotEmpty()
}?.u8(0)
