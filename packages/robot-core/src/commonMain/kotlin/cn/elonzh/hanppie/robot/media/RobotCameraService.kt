package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.put16
import cn.elonzh.hanppie.robot.protocol.u16
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex

data class CameraState(val zoomHundredths: Int? = null, val revision: Long = 0, val timeSynced: Boolean? = null,
    val storage: CameraStorageStatus? = null)

interface RobotCameraService {
    val state: StateFlow<CameraState>
    suspend fun setZoom(hundredths: Int)
    suspend fun getAntiFlicker(): Int
    suspend fun setAntiFlicker(mode: AntiFlicker): Int
    suspend fun getDate(): CameraDate?
    suspend fun syncClock(): CameraDate
    /** Returns after the command reply, not after physical media erasure has been independently verified. */
    suspend fun formatStorage()
}

/** Native camera status and configuration requests, owned by the App connection. */
internal class CameraCommands(
    private val request: suspend (receiver: Int, set: Int, id: Int, payload: ByteArray) -> ByteArray,
    private val localNow: () -> CameraDate = CameraDate::localNow,
    private val nowMillis: () -> Long,
) : RobotCameraService {
    private val mutableState = MutableStateFlow(CameraState())
    override val state = mutableState.asStateFlow()
    private var lastReceived = 0L
    @Volatile private var closed = false
    private val storageOperation = Mutex()

    fun receive(frame: DussFrame) {
        if (closed || !frame.valid || frame.sender != Protocol.HOST_CAMERA || frame.receiver != Protocol.HOST_MOBILE ||
            frame.attr != Protocol.ATTR_NO_ACK || frame.set != Protocol.CMDSET_CAMERA || frame.id != 0xe4 || frame.payload.size < 26) return
        val zoom = frame.payload.u16(8)
        lastReceived = nowMillis()
        mutableState.value = CameraState(zoom.takeIf { it in 100..400 }, state.value.revision + 1,
            frame.payload.u16(5) and 0xc0 != 0, CameraStorageStatus.decode(frame.payload))
    }

    fun expire() {
        if ((state.value.zoomHundredths != null || state.value.timeSynced != null || state.value.storage != null) && nowMillis() - lastReceived >= 1_500)
            mutableState.value = CameraState(revision = state.value.revision + 1)
    }

    override suspend fun setZoom(hundredths: Int) {
        require(hundredths in 100..400)
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
        if (state.value.zoomHundredths == null || nowMillis() - lastReceived >= 1_500)
            throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
        val before = state.value.revision
        val payload = byteArrayOf(9, 0, 0, 0, 0).apply { put16(3, hundredths) }
        request(Protocol.HOST_CAMERA, Protocol.CMDSET_CAMERA, Protocol.CMD_SET_ZOOM_PARAM, payload)
        val confirmed = withTimeoutOrNull(1_500) {
            state.first {
                if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
                it.revision > before && it.zoomHundredths == hundredths
            }
        }
        if (confirmed == null) throw RobotRequestException(RequestProblem.NOT_APPLIED)
    }

    private suspend fun command(id: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
        return request(Protocol.HOST_CAMERA, Protocol.CMDSET_CAMERA, id, payload)
    }

    override suspend fun getAntiFlicker(): Int {
        val response = command(0x47)
        if (response.size < 2) throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
        return response[1].toInt()
    }

    override suspend fun setAntiFlicker(mode: AntiFlicker): Int {
        command(0x46, byteArrayOf(mode.wireValue.toByte()))
        return getAntiFlicker().also {
            if (it != mode.wireValue) throw RobotRequestException(RequestProblem.NOT_APPLIED)
        }
    }

    override suspend fun getDate(): CameraDate? {
        val p = command(0x55)
        if (p.size < 8 || p.u8(1) > 99 || p.u8(2) > 99) throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
        if ((1..7).all { p[it] == 0.toByte() }) return null
        // Native getter uses two decimal components, unlike the uint16 year in SetCameraDate.
        return try { CameraDate(p.u8(1) + p.u8(2) * 100, p.u8(3), p.u8(4), p.u8(5), p.u8(6), p.u8(7)) }
        catch (_: IllegalArgumentException) { throw RobotRequestException(RequestProblem.INVALID_RESPONSE) }
    }

    override suspend fun syncClock(): CameraDate {
        val start = nowMillis()
        val expected = localNow()
        command(0x54, expected.payload())
        val actual = getDate() ?: throw RobotRequestException(RequestProblem.NOT_APPLIED)
        val elapsedSeconds = ((nowMillis() - start).coerceAtLeast(0) + 999) / 1000
        if (actual.wallSeconds - expected.wallSeconds !in 0..elapsedSeconds + 1)
            throw RobotRequestException(RequestProblem.NOT_APPLIED)
        return actual
    }

    override suspend fun formatStorage() {
        check(storageOperation.tryLock()) { "存储卡操作正在进行" }
        try {
            if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
            check(nowMillis() - lastReceived < 1_500 && state.value.storage?.canFormat == true) { "存储卡状态已变化，不能格式化" }
            command(0x72, byteArrayOf(0))
        } finally { storageOperation.unlock() }
    }

    fun close() {
        closed = true
        mutableState.value = CameraState(revision = state.value.revision + 1)
    }
}
