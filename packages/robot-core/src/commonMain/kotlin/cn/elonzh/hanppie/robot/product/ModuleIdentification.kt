package cn.elonzh.hanppie.robot.product

internal data class ModuleIdentificationCommand(val node: Int, val payload: ByteArray) {
    val set: Int get() = 0
    val id: Int get() = 0x60
}

/** Native module identification. Steady green ends the interval; it is not a saved previous colour. */
internal fun moduleIdentificationCommand(component: RobotComponent, blinking: Boolean): ModuleIdentificationCommand {
    val (host, index) = when (component.deviceId) {
        in 0x0318..0x031b -> 0x19 to (component.deviceId - 0x0317)
        in 0x1201..0x1204 -> 0x12 to (component.deviceId - 0x1200)
        else -> error("该模块没有已建立的识别灯指令")
    }
    return ModuleIdentificationCommand(host or (index shl 5),
        byteArrayOf(0xff.toByte(), if (blinking) 2 else 1, 0, 0xff.toByte(), 0))
}
