package cn.elonzh.hanppie.robot.remote

import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.protocol.u16
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile

/** Native LoadConfigs names and hashes, not application speed limits or firmware defaults. */
enum class ChassisParameter(val hash: UInt) {
    FORWARD_SPEED(0xfe360681u), BACKWARD_SPEED(0xed0c98d9u), LATERAL_SPEED(0x5ea0e66fu),
    FORWARD_ACCELERATION(0x1053171bu), LATERAL_ACCELERATION(0xa0531a0eu),
    FORWARD_BRAKING(0x33516de9u), LATERAL_BRAKING(0xc35170dcu),
}

data class ChassisParameterRange(val minimum: Float, val maximum: Float, val defaultValue: Float,
    val typeRaw: Int, val attributesRaw: Int) {
    init {
        require(listOf(minimum, maximum, defaultValue).all { it.isFinite() })
        require(minimum <= maximum && defaultValue in minimum..maximum)
    }
    fun accepts(value: Float): Boolean = value.isFinite() && value in minimum..maximum
}

data class ChassisParameterReading(val range: ChassisParameterRange, val value: Float)

/** A sequence of device reads, not an atomic firmware snapshot or a persistence guarantee. */
data class RobotMotionConfiguration(val speedLevelRaw: Int, val parameters: Map<ChassisParameter, ChassisParameterReading>)

interface RobotMotionService {
    suspend fun read(): RobotMotionConfiguration
    suspend fun setSpeedLevel(level: Int, onDispatch: () -> Unit): RobotMotionConfiguration
    suspend fun setParameter(parameter: ChassisParameter, value: Float, onDispatch: () -> Unit): ChassisParameterReading
}

/** Explicit configuration only: entering remote control never reapplies a cached device preference. */
internal class MotionCommands(
    private val request: suspend (Int, Int, Int, ByteArray, () -> Unit) -> ByteArray,
) : RobotMotionService {
    private val access = Mutex()
    @Volatile private var closed = false

    override suspend fun read(): RobotMotionConfiguration = task { readConfiguration() }

    override suspend fun setSpeedLevel(level: Int, onDispatch: () -> Unit): RobotMotionConfiguration = task {
        require(level in 1..4)
        command(9, 0x3f, 0x3f, byteArrayOf(level.toByte()), onDispatch)
        readConfiguration().also {
            if (it.speedLevelRaw != level) throw RobotRequestException(RequestProblem.NOT_APPLIED)
        }
    }

    override suspend fun setParameter(parameter: ChassisParameter, value: Float, onDispatch: () -> Unit): ChassisParameterReading = task {
        require(value.isFinite())
        if (readLevel() != 4) throw RobotRequestException(RequestProblem.NOT_APPLIED)
        val range = readRange(parameter)
        require(range.accepts(value)) { "设备参数超出回读范围" }
        val payload = ByteArray(8).apply { put32(0, parameter.hash.toInt()); put32(4, value.toRawBits()) }
        command(3, 3, 0xf9, payload, onDispatch)
        val actual = readValue(parameter)
        if (actual != value || readLevel() != 4) throw RobotRequestException(RequestProblem.NOT_APPLIED)
        ChassisParameterReading(range, actual)
    }

    private suspend fun readConfiguration(): RobotMotionConfiguration {
        val level = readLevel()
        val values = ChassisParameter.entries.associateWith { parameter ->
            ChassisParameterReading(readRange(parameter), readValue(parameter))
        }
        if (readLevel() != level) throw RobotRequestException(RequestProblem.NOT_APPLIED)
        return RobotMotionConfiguration(level, values)
    }

    private suspend fun readLevel(): Int {
        val payload = command(9, 0x3f, 0x4f)
        invalidUnless(payload.size == 2)
        return payload.u8(1)
    }

    private suspend fun readRange(parameter: ChassisParameter): ChassisParameterRange {
        val payload = command(3, 3, 0xf7, parameterPayload(parameter))
        // Native range callback reads min/max/default at 7/11/15; a name may follow.
        invalidUnless(payload.size >= 19 && payload.u16(3) == 4)
        val min = payload.floatAt(7); val max = payload.floatAt(11); val default = payload.floatAt(15)
        invalidUnless(listOf(min, max, default).all { it.isFinite() } && min <= max && default in min..max)
        return ChassisParameterRange(min, max, default, payload.u16(1), payload.u16(5))
    }

    private suspend fun readValue(parameter: ChassisParameter): Float {
        val payload = command(3, 3, 0xf8, parameterPayload(parameter))
        invalidUnless(payload.size == 9 && payload.int32At(1).toUInt() == parameter.hash)
        return payload.floatAt(5).also { invalidUnless(it.isFinite()) }
    }

    private suspend fun command(node: Int, set: Int, id: Int, payload: ByteArray = byteArrayOf(), beforeSend: () -> Unit = {}): ByteArray {
        ensureOpen()
        val reply = request(node, set, id, payload, beforeSend)
        ensureOpen()
        invalidUnless(reply.isNotEmpty())
        if (reply.u8(0) != 0) throw RobotRequestException(RequestProblem.REJECTED, reply.u8(0))
        return reply
    }

    private suspend fun ensureOpen() {
        currentCoroutineContext().ensureActive()
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
    }

    private suspend fun <T> task(action: suspend () -> T): T {
        check(access.tryLock()) { "运动参数请求正在进行" }
        try { ensureOpen(); return action() } finally { access.unlock() }
    }

    fun close() { closed = true }
}

private fun parameterPayload(parameter: ChassisParameter) = ByteArray(4).apply { put32(0, parameter.hash.toInt()) }
private fun ByteArray.put32(offset: Int, value: Int) { repeat(4) { this[offset + it] = (value ushr (it * 8)).toByte() } }
private fun ByteArray.int32At(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16) or (u8(offset + 3) shl 24)
private fun ByteArray.floatAt(offset: Int): Float = Float.fromBits(int32At(offset))
private fun invalidUnless(condition: Boolean) {
    if (!condition) throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
}
