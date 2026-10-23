package cn.elonzh.hanppie.robot.maintenance

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.Volatile

/** Addresses are per operation. In particular, the MC firmware node is not its wheel-control node. */
enum class ModuleAddressingKind { WHEELS, ARMOR, SERVO, TOF, SENSOR_ADAPTER }
internal enum class ModuleAddressingStep(val code: Int) { BEGIN(0), ASSIGN(1), END(2) }
enum class AddressingWheel { FRONT_RIGHT, FRONT_LEFT, REAR_LEFT, REAR_RIGHT }

internal data class WheelEncodingReport(val state: Int, val percentRaw: Int) {
    val percent: Int? get() = percentRaw.takeIf { it in 0..100 }
}
internal data class ArmorAddressingSignal(val sequence: Int, val raw: Int)
internal data class ArmServoIds(val main: Int, val secondary: Int)
internal data class AddressingCommand(val node: Int, val set: Int, val id: Int, val payload: ByteArray)

/** One encoding shared by acknowledged termination and best-effort connection cleanup. */
internal fun addressingEndCommands(kind: ModuleAddressingKind): List<AddressingCommand> = when (kind) {
    ModuleAddressingKind.WHEELS -> listOf(
        AddressingCommand(0xc3, 0x3f, 0x3e, byteArrayOf(2)),
        AddressingCommand(3, 0x3f, 0x20, wheelAddressingTestPayload(null)),
        AddressingCommand(3, 0x3f, 0x19, byteArrayOf(0)),
    )
    ModuleAddressingKind.ARMOR -> listOf(AddressingCommand(0x18, 0x3f, 0x62, byteArrayOf(0xff.toByte())))
    ModuleAddressingKind.SERVO -> listOf(AddressingCommand(0xc3, 0x3f, 0x66, byteArrayOf(2, 0)))
    ModuleAddressingKind.TOF -> listOf(AddressingCommand(9, 0x3f, 0x66, byteArrayOf(2, 0)))
    ModuleAddressingKind.SENSOR_ADAPTER -> emptyList()
}

internal fun identificationLightCommand(kind: ModuleAddressingKind, id: Int, blinking: Boolean): AddressingCommand {
    require(kind in setOf(ModuleAddressingKind.SERVO, ModuleAddressingKind.TOF) && id in 1..4)
    val host = if (kind == ModuleAddressingKind.SERVO) 0x19 else 0x12
    return AddressingCommand(host or (id shl 5), 0, 0x60,
        byteArrayOf(0xff.toByte(), if (blinking) 2 else 1, 0, 0xff.toByte(), 0))
}

/** Native constructors and decoders only. The task owner must manage admission, leases and recovery. */
internal class ModuleAddressingProtocol(private val request: suspend (Int, Int, Int, ByteArray) -> ByteArray) {
    @Volatile private var closed = false
    private suspend fun command(node: Int, set: Int, id: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        currentCoroutineContext().ensureActive()
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
        return request(node, set, id, payload)
    }
    suspend fun begin(kind: ModuleAddressingKind, onStart: () -> Unit = {}) {
        when (kind) {
            ModuleAddressingKind.WHEELS -> {
                command(0xc3, 0x3f, 0x3e, byteArrayOf(2))
                currentCoroutineContext().ensureActive(); onStart()
                command(0xc3, 0x3f, 0x3e, byteArrayOf(1))
            }
            ModuleAddressingKind.ARMOR -> { currentCoroutineContext().ensureActive(); onStart(); command(0x18, 0x3f, 0x62, byteArrayOf(0)) }
            ModuleAddressingKind.SERVO, ModuleAddressingKind.TOF -> { currentCoroutineContext().ensureActive(); onStart(); step(kind, ModuleAddressingStep.BEGIN) }
            ModuleAddressingKind.SENSOR_ADAPTER -> error("转接板使用板载按钮编址，没有此远程指令")
        }
    }
    suspend fun end(kind: ModuleAddressingKind) {
        check(kind != ModuleAddressingKind.SENSOR_ADAPTER) { "转接板编址需要在硬件上结束" }
        execute(addressingEndCommands(kind).first())
    }
    suspend fun execute(packet: AddressingCommand) { command(packet.node, packet.set, packet.id, packet.payload) }
    suspend fun assign(kind: ModuleAddressingKind, id: Int) {
        // Servo discovery includes node 4, while the installation guide offers IDs 1–3.
        require(kind == ModuleAddressingKind.SERVO && id in 1..3 || kind == ModuleAddressingKind.TOF && id in 1..4)
        step(kind, ModuleAddressingStep.ASSIGN, id)
    }
    private suspend fun step(kind: ModuleAddressingKind, step: ModuleAddressingStep, id: Int = 0) {
        require(kind == ModuleAddressingKind.SERVO || kind == ModuleAddressingKind.TOF)
        command(if (kind == ModuleAddressingKind.SERVO) 0xc3 else 9, 0x3f, 0x66, byteArrayOf(step.code.toByte(), id.toByte()))
    }
    suspend fun skipArmor() { command(0x18, 0x3f, 0x62, byteArrayOf(0xfe.toByte())) }
    suspend fun armServoIds(): ArmServoIds {
        val p = command(0xc3, 0x33, 0x68)
        if (p.size < 3) throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
        return ArmServoIds(p.u8(1), p.u8(2))
    }
    suspend fun enterWheelTestMode() { command(3, 0x3f, 0x19, byteArrayOf(1)) }
    suspend fun exitWheelTestMode() { command(3, 0x3f, 0x19, byteArrayOf(0)) }
    suspend fun stopWheelTest() { command(3, 0x3f, 0x20, wheelAddressingTestPayload(null)) }

    /** The owner must end the identification interval; steady green is not the previous colour. */
    suspend fun setIdentificationLight(kind: ModuleAddressingKind, id: Int, blinking: Boolean) {
        // Native Flash (3) and Breath (2) both encode as 2 for these module LEDs.
        execute(identificationLightCommand(kind, id, blinking))
    }
    fun close() { closed = true }
}

internal fun wheelEncodingReport(frame: DussFrame): WheelEncodingReport? =
    if (frame.valid && frame.sender == 0xc3 && frame.receiver == 2 && frame.attr == 0 && frame.set == 0x3f && frame.id == 0x2f && frame.payload.size >= 2)
        WheelEncodingReport(frame.payload.u8(0), frame.payload.u8(1)) else null

internal fun armorAddressingSignal(frame: DussFrame): ArmorAddressingSignal? =
    if (frame.valid && frame.sender == 0x18 && frame.receiver == 2 && frame.attr == 0 && frame.set == 0x3f && frame.id == 0x63 && frame.payload.isNotEmpty())
        ArmorAddressingSignal(frame.sequence, frame.payload.u8(0)) else null

/** Native test speed is 200 RPM. Callers must enforce hold/expiry and send zero to the same CAN node. */
internal fun wheelAddressingTestPayload(wheel: AddressingWheel?): ByteArray = ByteArray(8).apply {
    if (wheel != null) put16(wheel.ordinal * 2, 200)
}
