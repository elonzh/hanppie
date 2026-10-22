package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.media.RobotCameraService
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.robot.device.robotRequestMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class CameraZoomState(val hundredths: Int? = null, val busy: Boolean = false, val error: UiText? = null)

internal class CameraZoomController(private val scope: CoroutineScope) {
    val state = MutableStateFlow(CameraZoomState())
    private var service: RobotCameraService? = null
    private var observer: Job? = null
    private var operation: Job? = null

    fun attach(service: RobotCameraService) {
        clear(); this.service = service
        observer = scope.launch {
            service.state.collect { value -> if (this@CameraZoomController.service === service)
                state.update { it.copy(hundredths = value.zoomHundredths) }
            }
        }
    }

    fun setZoom(hundredths: Int) {
        val current = service ?: return
        if (operation?.isActive == true) return
        state.update { it.copy(busy = true, error = null) }
        operation = scope.launch {
            try {
                current.setZoom(hundredths)
                if (service === current) state.update { it.copy(busy = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (service === current) state.update { it.copy(busy = false, error = robotRequestMessage(error, Res.string.camera_zoom_failed)) }
            }
        }
    }

    fun clear() {
        service = null
        observer?.cancel(); observer = null
        operation?.cancel(); operation = null
        state.value = CameraZoomState()
    }
}
