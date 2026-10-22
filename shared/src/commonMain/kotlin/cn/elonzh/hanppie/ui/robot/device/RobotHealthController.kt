package cn.elonzh.hanppie.ui.robot.device

import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/** Copies only the active session's health; no health event owns movement or firing. */
internal class RobotHealthController(private val scope: CoroutineScope) {
    val state = MutableStateFlow(RobotHealth())
    private class Binding
    @Volatile private var binding: Binding? = null
    private var observer: Job? = null
    fun attach(source: StateFlow<RobotHealth>) {
        clear()
        if (!scope.isActive) return
        val current = Binding(); binding = current
        observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            source.collect { value -> if (binding === current) state.value = value }
        }
    }
    fun clear() { binding = null; observer?.cancel(); observer = null; state.value = RobotHealth() }
}
