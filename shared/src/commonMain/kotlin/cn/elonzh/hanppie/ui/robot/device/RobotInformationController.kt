package cn.elonzh.hanppie.ui.robot.device

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal sealed interface InformationValue<out T> {
    data object Unread : InformationValue<Nothing>
    data object Loading : InformationValue<Nothing>
    data class Value<T>(val value: T) : InformationValue<T>
    data class Failed(val message: UiText) : InformationValue<Nothing>
}

internal data class RobotInformationState(
    val connected: Boolean = false,
    val versions: Map<RobotVersionTarget, InformationValue<String>> = emptyMap(),
    val activation: InformationValue<Boolean> = InformationValue.Unread,
    val usage: RobotUsage? = null,
) {
    val busy: Boolean get() = activation == InformationValue.Loading || versions.values.any { it == InformationValue.Loading }
}

/** Session-scoped read snapshots. Module reports select addresses; queries never enable a capability. */
internal class RobotInformationController(private val scope: CoroutineScope) {
    private class Binding(val service: RobotInformationService, val scope: CoroutineScope) {
        val requests = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val slots = Semaphore(4)
        val versions = mutableMapOf<RobotVersionTarget, Mutex>()
        val activation = Mutex()
    }
    val state = MutableStateFlow(RobotInformationState())
    private var binding: Binding? = null

    fun attach(service: RobotInformationService, health: StateFlow<RobotHealth>) {
        clear()
        if (!scope.isActive) return
        val current = Binding(service, CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])))
        binding = current
        state.value = RobotInformationState(connected = true)
        current.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            health.collect { report ->
                val ids = report.modules.orEmpty().map { it.deviceId }.toSet()
                val targets = RobotVersionTarget.entries.filter { it.componentId == null || it.componentId in ids }
                current.versions.keys.retainAll(targets.toSet())
                targets.forEach { current.versions.getOrPut(it) { Mutex() } }
                update(current) { old -> old.copy(versions = targets.associateWith { old.versions[it] ?: InformationValue.Unread }) }
            }
        }
        current.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            service.usage.collect { value -> update(current) { it.copy(usage = value) } }
        }
    }

    fun refresh() {
        val current = binding ?: return
        state.value.versions.keys.forEach(::readVersion)
        readActivation(current)
    }
    fun readActivation() { binding?.let(::readActivation) }
    private fun readActivation(current: Binding) = query(current, current.activation,
        { old, value -> old.copy(activation = value) }) { current.service.readActivated() }

    fun readVersion(target: RobotVersionTarget) {
        val current = binding ?: return
        val slot = current.versions[target] ?: return
        query(current, slot, { old, value ->
            if (current.versions[target] === slot) old.copy(versions = old.versions + (target to value)) else old
        }) { current.service.readFirmware(target) }
    }

    private fun <T> query(current: Binding, mutex: Mutex,
        set: (RobotInformationState, InformationValue<T>) -> RobotInformationState, action: suspend () -> T,
    ) {
        if (binding !== current || !current.requests.isActive || !mutex.tryLock()) return
        update(current) { set(it, InformationValue.Loading) }
        current.requests.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = current.slots.withPermit { action() }
                currentCoroutineContext().ensureActive()
                update(current) { set(it, InformationValue.Value(result)) }
            } catch (error: CancellationException) {
                update(current) { set(it, InformationValue.Unread) }
                throw error
            } catch (error: Exception) {
                val message = if (error is RobotRequestException && error.problem == RequestProblem.REJECTED)
                    uiText(Res.string.robot_info_rejected) else robotRequestMessage(error, Res.string.robot_info_failed)
                update(current) { set(it, InformationValue.Failed(message)) }
            } finally { mutex.unlock() }
        }
    }

    fun cancelQueries() { binding?.requests?.coroutineContext?.cancelChildren() }
    private fun update(current: Binding, transform: (RobotInformationState) -> RobotInformationState) {
        state.update { if (binding === current) transform(it) else it }
    }
    fun clear() {
        val old = binding; binding = null
        state.value = RobotInformationState()
        old?.scope?.cancel()
    }
}
