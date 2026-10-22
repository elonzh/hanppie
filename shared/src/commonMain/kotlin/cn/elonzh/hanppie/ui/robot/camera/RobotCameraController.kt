@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.robot.camera

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.media.AntiFlicker
import cn.elonzh.hanppie.robot.media.CameraDate
import cn.elonzh.hanppie.robot.media.CameraStorageStatus
import cn.elonzh.hanppie.robot.media.RobotCameraService
import cn.elonzh.hanppie.robot.media.CameraImagingStatus
import cn.elonzh.hanppie.robot.media.CameraPhotoSize
import cn.elonzh.hanppie.robot.media.VideoTransmissionRate
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.robot.device.robotRequestMessage
import kotlin.concurrent.atomics.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

internal sealed interface CameraRequest<out T> {
    data object Idle : CameraRequest<Nothing>
    data object Loading : CameraRequest<Nothing>
    data class Value<T>(val value: T) : CameraRequest<T>
    data class Failed(val message: UiText) : CameraRequest<Nothing>
}

internal enum class CaptureAction { PHOTO, START_RECORDING, STOP_RECORDING }

internal data class RobotCameraUiState(
    val connection: Long = 0,
    val antiFlicker: CameraRequest<Int> = CameraRequest.Idle,
    val date: CameraRequest<CameraDate?> = CameraRequest.Idle,
    val timeSynced: Boolean? = null,
    val storage: CameraStorageStatus? = null,
    val formatRequest: CameraRequest<Unit> = CameraRequest.Idle,
    val capture: CameraRequest<CaptureAction> = CameraRequest.Idle,
    val imaging: CameraImagingStatus? = null,
    val photoSize: CameraRequest<CameraPhotoSize> = CameraRequest.Idle,
    val cameraSwitch: CameraRequest<Int> = CameraRequest.Idle,
    val transmissionRate: CameraRequest<VideoTransmissionRate> = CameraRequest.Idle,
    val keyFrame: CameraRequest<Unit> = CameraRequest.Idle,
) {
    val storageBusy: Boolean get() = formatRequest == CameraRequest.Loading || capture == CameraRequest.Loading ||
        photoSize == CameraRequest.Loading || cameraSwitch == CameraRequest.Loading
}

/** Per-connection camera settings and storage tasks. The robot owns persistent media and recording state. */
internal class RobotCameraController(private val scope: CoroutineScope) {
    private class Binding(val id: Long, val service: RobotCameraService, val scope: CoroutineScope) {
        val antiFlicker = Mutex()
        val clock = Mutex()
        val storage = Mutex()
        val video = Mutex()
        var autoSyncAttempted = false
    }
    val state = MutableStateFlow(RobotCameraUiState())
    private val revision = AtomicLong(0)
    private var binding: Binding? = null

    fun attach(service: RobotCameraService) {
        clear()
        val current = Binding(revision.addAndFetch(1), service,
            CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])))
        binding = current
        state.value = RobotCameraUiState(connection = current.id)
        current.scope.launch {
            service.state.collect { status ->
                update(current) { it.copy(timeSynced = status.timeSynced, storage = status.storage, imaging = status.imaging) }
                if (status.timeSynced == false && !current.autoSyncAttempted)
                    current.autoSyncAttempted = syncClock(current)
            }
        }
    }

    fun refresh() {
        val current = binding ?: return
        work(current, current.antiFlicker, { old, value -> old.copy(antiFlicker = value) }) { it.getAntiFlicker() }
        work(current, current.clock, { old, value -> old.copy(date = value) }) { it.getDate() }
    }

    fun setAntiFlicker(mode: AntiFlicker) {
        val current = binding ?: return
        work(current, current.antiFlicker, { old, value -> old.copy(antiFlicker = value) }) { it.setAntiFlicker(mode) }
    }

    fun setPhotoSize(size: CameraPhotoSize) {
        val current = binding ?: return
        work(current, current.storage, { old, value -> old.copy(photoSize = value) }) { it.setPhotoSize(size); size }
    }

    fun switchCamera(index: Int) {
        val current = binding ?: return
        work(current, current.storage, { old, value -> old.copy(cameraSwitch = value) }) { it.switchCamera(index); index }
    }

    fun setTransmissionRate(rate: VideoTransmissionRate) {
        val current = binding ?: return
        work(current, current.video, { old, value -> old.copy(transmissionRate = value) }) { it.setTransmissionRate(rate); rate }
    }

    fun requestKeyFrame() {
        val current = binding ?: return
        work(current, current.video, { old, value -> old.copy(keyFrame = value) }) { it.requestKeyFrame() }
    }

    fun syncClock() { binding?.let { if (syncClock(it)) it.autoSyncAttempted = true } }
    private fun syncClock(current: Binding): Boolean =
        work<CameraDate?>(current, current.clock, { old, value -> old.copy(date = value) }) { it.syncClock() }

    fun formatStorage() {
        val current = binding ?: return
        work(current, current.storage, { old, value -> old.copy(formatRequest = value) },
            errorMessage = { error -> uiText(when {
                error is IllegalStateException -> Res.string.camera_storage_state_changed
                error is RobotRequestException && error.problem == RequestProblem.REJECTED -> Res.string.camera_storage_format_rejected
                else -> Res.string.camera_storage_format_uncertain
            }) }) { it.formatStorage() }
    }

    fun capture(action: CaptureAction) {
        val current = binding ?: return
        work(current, current.storage, { old, value -> old.copy(capture = value) },
            errorMessage = { error -> uiText(when {
                error is IllegalStateException -> Res.string.camera_capture_unavailable
                error is RobotRequestException && error.problem == RequestProblem.REJECTED -> Res.string.camera_capture_rejected
                else -> Res.string.camera_capture_uncertain
            }) }) { service ->
            when (action) {
                CaptureAction.PHOTO -> service.takePhoto()
                CaptureAction.START_RECORDING -> service.setRecording(true)
                CaptureAction.STOP_RECORDING -> service.setRecording(false)
            }
            action
        }
    }

    private fun update(current: Binding, transform: (RobotCameraUiState) -> RobotCameraUiState) {
        state.update { if (it.connection == current.id) transform(it) else it }
    }

    private fun <T> work(current: Binding, mutex: Mutex,
        set: (RobotCameraUiState, CameraRequest<T>) -> RobotCameraUiState,
        errorMessage: (Exception) -> UiText = { robotRequestMessage(it, Res.string.camera_settings_failed) },
        action: suspend (RobotCameraService) -> T,
    ): Boolean {
        if (binding !== current || !current.scope.isActive || !mutex.tryLock()) return false
        update(current) { set(it, CameraRequest.Loading) }
        current.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = action(current.service)
                update(current) { set(it, CameraRequest.Value(result)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update(current) { set(it, CameraRequest.Failed(errorMessage(error))) } }
            finally { mutex.unlock() }
        }
        return true
    }

    fun clear() {
        val old = binding
        binding = null
        state.value = RobotCameraUiState(connection = revision.addAndFetch(1))
        old?.scope?.cancel()
    }
}
