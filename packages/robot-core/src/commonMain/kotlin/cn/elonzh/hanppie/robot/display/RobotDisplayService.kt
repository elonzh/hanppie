package cn.elonzh.hanppie.robot.display

import cn.elonzh.hanppie.robot.protocol.put16
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.protocol.u16
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile

/** Native image coordinates: 0..10000, with 5000 at the image centre. Y points up. */
data class SightPoint(val x: Int = 5000, val y: Int = 5000) {
    init { require(x in 0..10000 && y in 0..10000) }
}

/** Both pairs are carried in every 3f:56 request; neither pair can be inferred from the other. */
data class SightCalibration(val zoomIn: SightPoint = SightPoint(), val zoomOut: SightPoint = SightPoint())

/** Raw PWM readback; only 0..100 is exposed by the fixed official slider. */
data class HeadlightBrightness(val left: Int, val right: Int) {
    init { require(left in 0..65535 && right in 0..65535) }
}

interface RobotDisplayService {
    suspend fun readSight(): SightCalibration
    suspend fun setSight(value: SightCalibration, onDispatch: () -> Unit): SightCalibration
    suspend fun readHeadlights(): HeadlightBrightness
    suspend fun setHeadlights(percent: Int, onDispatch: () -> Unit): HeadlightBrightness
}

/** Device configuration, independent of host display preferences and armour state LEDs. */
internal class DisplayCommands(
    private val request: suspend (Int, Int, Int, ByteArray, () -> Unit) -> ByteArray,
) : RobotDisplayService {
    private val access = Mutex()
    @Volatile private var closed = false

    override suspend fun readSight(): SightCalibration = task { sight() }

    override suspend fun setSight(value: SightCalibration, onDispatch: () -> Unit): SightCalibration = task {
        val payload = ByteArray(8).apply {
            put16(0, value.zoomIn.x); put16(2, value.zoomIn.y)
            put16(4, value.zoomOut.x); put16(6, value.zoomOut.y)
        }
        command(9, 0x56, payload, onDispatch)
        try {
            sight().also { if (it != value) throw RobotRequestException(RequestProblem.NOT_APPLIED) }
        } catch (error: RobotRequestException) {
            // A rejected readback cannot undo the acknowledged calibration write.
            if (error.problem == RequestProblem.REJECTED) throw RobotRequestException(RequestProblem.NOT_APPLIED)
            throw error
        }
    }

    override suspend fun readHeadlights(): HeadlightBrightness = task { headlights() }

    override suspend fun setHeadlights(percent: Int, onDispatch: () -> Unit): HeadlightBrightness = task {
        require(percent in 0..100)
        // Match the native left/channel 5 then right/channel 4 writes. Masked channels alone change.
        command(0xc3, 0x3c, ByteArray(13).apply { this[0] = 0x20; put16(11, percent) }, onDispatch)
        try {
            command(0xc3, 0x3c, ByteArray(13).apply { this[0] = 0x10; put16(9, percent) }, onDispatch)
            headlights().also {
                if (it.left != percent || it.right != percent) throw RobotRequestException(RequestProblem.NOT_APPLIED)
            }
        } catch (error: CancellationException) { throw error }
        catch (error: RobotRequestException) {
            // A later rejection cannot prove the first acknowledged write was rolled back.
            if (error.problem == RequestProblem.REJECTED) throw RobotRequestException(RequestProblem.NOT_APPLIED)
            throw error
        }
    }

    private suspend fun sight(): SightCalibration {
        val payload = command(9, 0x57)
        return sightCalibration(payload) ?: throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
    }

    private suspend fun headlights(): HeadlightBrightness {
        val payload = command(0xc3, 0x3d)
        invalidUnless(payload.size == 13)
        return HeadlightBrightness(payload.u16(11), payload.u16(9))
    }

    private suspend fun command(node: Int, id: Int, payload: ByteArray = byteArrayOf(), beforeSend: () -> Unit = {}): ByteArray {
        ensureOpen()
        val reply = request(node, 0x3f, id, payload, beforeSend)
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
        check(access.tryLock()) { "显示配置请求正在进行" }
        try { ensureOpen(); return action() } finally { access.unlock() }
    }

    fun close() { closed = true }
}

internal fun sightCalibration(payload: ByteArray): SightCalibration? {
    if (payload.size != 9 || payload.u8(0) != 0 || (1..7 step 2).any { payload.u16(it) !in 0..10000 }) return null
    return SightCalibration(SightPoint(payload.u16(1), payload.u16(3)), SightPoint(payload.u16(5), payload.u16(7)))
}

private fun invalidUnless(condition: Boolean) {
    if (!condition) throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
}
