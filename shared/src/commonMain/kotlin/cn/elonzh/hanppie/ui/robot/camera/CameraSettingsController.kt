@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.robot.camera

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.media.AntiFlicker
import cn.elonzh.hanppie.robot.media.CameraDate
import cn.elonzh.hanppie.robot.media.RobotCameraService
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.robot.device.robotRequestMessage
import kotlin.concurrent.atomics.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

internal sealed interface CameraSetting<out T> {
    data object Idle : CameraSetting<Nothing>
    data object Loading : CameraSetting<Nothing>
    data class Value<T>(val value: T) : CameraSetting<T>
    data class Failed(val message: UiText) : CameraSetting<Nothing>
}

internal data class CameraSettingsState(
    val connection: Long = 0,
    val antiFlicker: CameraSetting<Int> = CameraSetting.Idle,
    val date: CameraSetting<CameraDate?> = CameraSetting.Idle,
    val timeSynced: Boolean? = null,
)

/** Per-connection native settings; clock synchronization is attempted once on a fresh unsynced status. */
internal class CameraSettingsController(private val scope: CoroutineScope) {
    private class Binding(val id: Long, val service: RobotCameraService, val scope: CoroutineScope) {
        val antiFlicker = Mutex()
        val clock = Mutex()
        var autoSyncAttempted = false
    }
    val state = MutableStateFlow(CameraSettingsState())
    private val revision = AtomicLong(0)
    private var binding: Binding? = null

    fun attach(service: RobotCameraService) {
        clear()
        val current = Binding(revision.addAndFetch(1), service,
            CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])))
        binding = current
        state.value = CameraSettingsState(connection = current.id)
        current.scope.launch {
            service.state.collect { status ->
                update(current) { it.copy(timeSynced = status.timeSynced) }
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

    fun syncClock() { binding?.let { if (syncClock(it)) it.autoSyncAttempted = true } }
    private fun syncClock(current: Binding): Boolean =
        work<CameraDate?>(current, current.clock, { old, value -> old.copy(date = value) }) { it.syncClock() }

    private fun update(current: Binding, transform: (CameraSettingsState) -> CameraSettingsState) {
        state.update { if (it.connection == current.id) transform(it) else it }
    }

    private fun <T> work(current: Binding, mutex: Mutex,
        set: (CameraSettingsState, CameraSetting<T>) -> CameraSettingsState,
        action: suspend (RobotCameraService) -> T,
    ): Boolean {
        if (binding !== current || !current.scope.isActive || !mutex.tryLock()) return false
        update(current) { set(it, CameraSetting.Loading) }
        current.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = action(current.service)
                update(current) { set(it, CameraSetting.Value(result)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update(current) { set(it, CameraSetting.Failed(robotRequestMessage(error, Res.string.camera_settings_failed))) } }
            finally { mutex.unlock() }
        }
        return true
    }

    fun clear() {
        val old = binding
        binding = null
        state.value = CameraSettingsState(connection = revision.addAndFetch(1))
        old?.scope?.cancel()
    }
}
