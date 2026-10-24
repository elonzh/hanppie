package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.put16
import cn.elonzh.hanppie.robot.protocol.u16
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex

data class CameraState(val zoomHundredths: Int? = null, val revision: Long = 0, val timeSynced: Boolean? = null,
    val storage: CameraStorageStatus? = null, val workMode: Int? = null, val imaging: CameraImagingStatus? = null)

interface RobotCameraService {
    val state: StateFlow<CameraState>
    /** Optional host interpolation; completion still requires the final ACK and a new target report. */
    suspend fun setZoom(hundredths: Int, smooth: Boolean = false)
    suspend fun getAntiFlicker(): Int
    suspend fun setAntiFlicker(mode: AntiFlicker): Int
    suspend fun getDate(): CameraDate?
    suspend fun syncClock(): CameraDate
    suspend fun setPhotoSize(size: CameraPhotoSize)
    /** Confirms the request only; this client exposes no native transmission-rate getter. */
    suspend fun setTransmissionRate(rate: VideoTransmissionRate)
    suspend fun switchCamera(index: Int)
    /** Acknowledgement does not prove that a new frame has reached a decoder. */
    suspend fun requestKeyFrame()
    /** Returns after the command reply, not after physical media erasure has been independently verified. */
    suspend fun formatStorage()
    /** Acknowledged photo request and a new status; does not verify a saved file. */
    suspend fun takePhoto()
    /** Waits for the command reply and a new matching recording status. */
    suspend fun setRecording(active: Boolean)
}

/** Fixed official Lerp, integer truncation and final snap; input values are hundredths of magnification. */
internal fun nextCameraZoom(current: Int, target: Int, elapsedSeconds: Float): Int {
    require(current in 100..400 && target in 100..400 && elapsedSeconds.isFinite() && elapsedSeconds >= 0f)
    val next = (current + (target - current) * (elapsedSeconds * 10f).coerceIn(0f, 1f)).toInt()
    return if (abs(target - next) < 6) target else next
}

/** Native camera status and configuration requests, owned by the App connection. */
internal class CameraCommands(
    private val request: suspend (receiver: Int, set: Int, id: Int, payload: ByteArray) -> ByteArray,
    private val storageAccess: Mutex = Mutex(),
    private val localNow: () -> CameraDate = CameraDate::localNow,
    private val nowMillis: () -> Long,
) : RobotCameraService {
    private val mutableState = MutableStateFlow(CameraState())
    override val state = mutableState.asStateFlow()
    @Volatile private var lastReceived = 0L
    @Volatile private var closed = false
    private val storageOperation = Mutex()

    fun receive(frame: DussFrame) {
        if (closed || !frame.valid || frame.sender != Protocol.HOST_CAMERA || frame.receiver != Protocol.HOST_MOBILE ||
            frame.attr != Protocol.ATTR_NO_ACK || frame.set != Protocol.CMDSET_CAMERA || frame.id != 0xe4 || frame.payload.size < 26) return
        val zoom = frame.payload.u16(8)
        lastReceived = nowMillis()
        mutableState.value = CameraState(zoom.takeIf { it in 100..400 }, state.value.revision + 1,
            frame.payload.u16(5) and 0xc0 != 0, CameraStorageStatus.decode(frame.payload), frame.payload.u16(5) and 3,
            CameraImagingStatus(frame.payload.u8(6) and 15, frame.payload.u8(7) and 15,
                (frame.payload.u16(5) ushr 4) and 3, (frame.payload.u16(5) ushr 2) and 3))
    }

    fun expire() {
        if ((state.value.zoomHundredths != null || state.value.timeSynced != null || state.value.storage != null) && nowMillis() - lastReceived >= 1_500)
            mutableState.value = CameraState(revision = state.value.revision + 1)
    }

    override suspend fun setZoom(hundredths: Int, smooth: Boolean) = setZoomChecked(hundredths, smooth) { payload ->
        request(Protocol.HOST_CAMERA, Protocol.CMDSET_CAMERA, Protocol.CMD_SET_ZOOM_PARAM, payload)
    }

    /** AppSession supplies an atomic admission check on every write, including after an ACK suspension. */
    suspend fun setZoomChecked(hundredths: Int, smooth: Boolean, write: suspend (ByteArray) -> Unit) {
        require(hundredths in 100..400)
        fun requireFreshZoom(): Int {
            if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
            return state.value.zoomHundredths?.takeIf { nowMillis() - lastReceived < 1_500 }
                ?: throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
        }
        var value = requireFreshZoom()
        var previous = nowMillis()
        do {
            if (smooth && value != hundredths) {
                // At most about 60 writes/s, with one request in flight; missed frames are never queued.
                delay(17)
                val now = nowMillis()
                value = nextCameraZoom(value, hundredths, (now - previous).coerceAtLeast(0) / 1000f)
                previous = now
            } else value = hundredths
            coroutineContext.ensureActive()
            requireFreshZoom()
            val before = state.value.revision
            write(byteArrayOf(9, 0, 0, 0, 0).apply { put16(3, value) })
            if (value == hundredths) {
                awaitStatus(before) { it.zoomHundredths == hundredths }
                return
            }
        } while (true)
    }

    private suspend fun awaitStatus(after: Long, matches: (CameraState) -> Boolean) {
        val confirmed = withTimeoutOrNull(1_500) {
            state.first {
                if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
                it.revision > after && nowMillis() - lastReceived < 1_500 && matches(it)
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

    override suspend fun setTransmissionRate(rate: VideoTransmissionRate) {
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
        request(Protocol.HOST_CAMERA, Protocol.CMDSET_DM368, 0x20, ByteArray(2).apply { put16(0, rate.kbps) })
    }

    override suspend fun requestKeyFrame() {
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
        // Native ActionRequestIFrame targets 0x0e through the special command set, not camera 02:xx.
        request(0x0e, Protocol.CMDSET_SPECIAL, 0x01, byteArrayOf(0, 0, 0, 0, 0, 0x20, 0, 0, 0, 0x20))
    }

    private fun requireIdleImaging(): CameraImagingStatus {
        val card = freshStorage()
        check(!card.recording && !card.shooting && !card.formatting) { "相机正在拍录或格式化" }
        return checkNotNull(state.value.imaging) { "没有相机状态" }
    }

    override suspend fun setPhotoSize(size: CameraPhotoSize) = storageTask {
        requireIdleImaging()
        val before = state.value.revision
        command(0x12, byteArrayOf(size.code.toByte(), 1))
        awaitStatus(before) { it.imaging?.photoSize == size.code }
    }

    override suspend fun switchCamera(index: Int) = storageTask {
        require(index in 0..1)
        check(storageAccess.tryLock()) { "存储卡传输正在进行" }
        try {
            check(index in requireIdleImaging().availableCameras) { "设备未报告此相机" }
            val before = state.value.revision
            command(0xe3, byteArrayOf(index.toByte()))
            awaitStatus(before) { it.imaging?.let { image -> image.cameraIndex == index && index in image.availableCameras } == true }
        } finally { storageAccess.unlock() }
    }

    private suspend fun storageTask(action: suspend () -> Unit) {
        check(storageOperation.tryLock()) { "存储卡操作正在进行" }
        try {
            if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
            action()
        } finally { storageOperation.unlock() }
    }

    private fun freshStorage(): CameraStorageStatus {
        check(nowMillis() - lastReceived < 1_500) { "存储卡状态已过期" }
        return checkNotNull(state.value.storage) { "没有存储卡状态" }
    }

    override suspend fun formatStorage() = storageTask {
        check(storageAccess.tryLock()) { "存储卡传输正在进行" }
        try {
            check(freshStorage().canFormat) { "存储卡状态已变化，不能格式化" }
            command(0x72, byteArrayOf(0))
        } finally { storageAccess.unlock() }
    }

    fun canReadStorage(): Boolean = !closed && nowMillis() - lastReceived < 1_500 &&
        state.value.storage?.let { it.inserted && !it.formatting && !it.hasError } == true

    private suspend fun prepareCapture(mode: Int) {
        check(freshStorage().canCapture) { "存储卡不可拍录" }
        if (state.value.workMode != mode) {
            val before = state.value.revision
            command(0x10, byteArrayOf(mode.toByte()))
            awaitStatus(before) { it.workMode == mode }
        }
        check(freshStorage().canCapture) { "切换模式后存储卡不可拍录" }
    }

    override suspend fun takePhoto() = storageTask {
        prepareCapture(0)
        val before = state.value.revision
        command(0x01, byteArrayOf(1))
        awaitStatus(before) { it.storage != null && it.workMode == 0 }
    }

    override suspend fun setRecording(active: Boolean) = storageTask {
        if (active) prepareCapture(1)
        val before = state.value.revision
        command(0x02, byteArrayOf(if (active) 1 else 0))
        awaitStatus(before) { it.storage?.recording == active }
    }

    fun close() {
        closed = true
        mutableState.value = CameraState(revision = state.value.revision + 1)
    }
}
