package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.put16
import cn.elonzh.hanppie.robot.protocol.u16
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class CameraState(val zoomHundredths: Int? = null, val revision: Long = 0)

interface RobotCameraService {
    val state: StateFlow<CameraState>
    suspend fun setZoom(hundredths: Int)
}

/** Native 02:e4 camera status and 02:34 zoom, owned by the App connection. */
internal class CameraCommands(
    private val request: suspend (receiver: Int, set: Int, id: Int, payload: ByteArray) -> ByteArray,
    private val nowMillis: () -> Long,
) : RobotCameraService {
    private val mutableState = MutableStateFlow(CameraState())
    override val state = mutableState.asStateFlow()
    private var lastReceived = 0L
    @Volatile private var closed = false

    fun receive(frame: DussFrame) {
        if (closed || !frame.valid || frame.sender != Protocol.HOST_CAMERA || frame.receiver != Protocol.HOST_MOBILE ||
            frame.attr != Protocol.ATTR_NO_ACK || frame.set != Protocol.CMDSET_CAMERA || frame.id != 0xe4 || frame.payload.size < 26) return
        val zoom = frame.payload.u16(8)
        lastReceived = nowMillis()
        mutableState.value = CameraState(zoom.takeIf { it in 100..400 }, state.value.revision + 1)
    }

    fun expire() {
        if (state.value.zoomHundredths != null && nowMillis() - lastReceived >= 1_500)
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

    fun close() {
        closed = true
        mutableState.value = CameraState(revision = state.value.revision + 1)
    }
}
