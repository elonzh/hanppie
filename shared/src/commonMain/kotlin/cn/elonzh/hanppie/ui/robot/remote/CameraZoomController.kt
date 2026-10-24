@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.media.RobotCameraService
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.robot.device.robotRequestMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference

internal data class CameraZoomState(val hundredths: Int? = null, val error: UiText? = null,
    val connection: Long = 0, val operation: Long? = null) {
    val busy: Boolean get() = operation != null
}

internal class CameraZoomController(private val scope: CoroutineScope) {
    private class Binding(val service: RobotCameraService, val id: Long) {
        val observer = AtomicReference<Job?>(null)
        val operation = AtomicReference<Job?>(null)
    }
    private val mutableState = MutableStateFlow(CameraZoomState())
    val state = mutableState.asStateFlow()
    private val revision = AtomicLong(0)
    private val binding = AtomicReference<Binding?>(null)

    fun attach(service: RobotCameraService) {
        val current = Binding(service, revision.addAndFetch(1))
        retire(binding.exchange(current))
        mutableState.update { if (binding.load() === current) CameraZoomState(connection = current.id) else it }
        val observer = scope.launch(start = CoroutineStart.LAZY) {
            service.state.collect { value ->
                mutableState.update { if (it.connection == current.id && binding.load() === current)
                    it.copy(hundredths = value.zoomHundredths) else it }
            }
        }
        current.observer.store(observer)
        if (binding.load() === current) observer.start() else observer.cancel()
    }

    fun setZoom(hundredths: Int) {
        val current = binding.load() ?: return
        if (state.value.busy) return
        val token = revision.addAndFetch(1)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                current.service.setZoom(hundredths, smooth = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.update { if (it.connection == current.id && it.operation == token)
                    it.copy(error = robotRequestMessage(error, Res.string.camera_zoom_failed)) else it }
            }
        }
        if (!current.operation.compareAndSet(null, job)) { job.cancel(); return }
        fun finished() {
            current.operation.compareAndSet(job, null)
            mutableState.update { if (it.connection == current.id && it.operation == token) it.copy(operation = null) else it }
        }
        job.invokeOnCompletion { finished() }
        mutableState.update { if (it.connection == current.id && !it.busy && binding.load() === current && current.operation.load() === job)
            it.copy(operation = token, error = null) else it }
        if (binding.load() !== current || state.value.operation != token) job.cancel()
        if (!job.start()) finished()
    }

    fun cancelTransition() {
        // Keep ownership/busy until the service's request cleanup has actually finished.
        binding.load()?.operation?.load()?.cancel()
    }

    fun clear() {
        val id = revision.addAndFetch(1)
        retire(binding.exchange(null))
        mutableState.update { if (binding.load() == null) CameraZoomState(connection = id) else it }
    }

    private fun retire(previous: Binding?) {
        previous?.observer?.exchange(null)?.cancel()
        previous?.operation?.load()?.cancel()
    }
}
