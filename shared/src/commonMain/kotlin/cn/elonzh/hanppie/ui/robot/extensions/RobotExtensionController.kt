package cn.elonzh.hanppie.ui.robot.extensions

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.extensions.*
import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.robot.session.RobotLock
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.robot.device.robotRequestMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal enum class ExtensionUiOperation { OBSERVE, IDENTIFY }
internal data class ExtensionUiState(val connection: Long = 0, val connected: Boolean = false,
    val task: RobotExtensionState = RobotExtensionState(), val family: ExtensionKind = ExtensionKind.SERVO,
    val selected: RobotComponent? = null, val operation: ExtensionUiOperation? = null,
    val pausing: Boolean = false, val error: UiText? = null) {
    val changing: Boolean get() = operation == ExtensionUiOperation.IDENTIFY
    val busy: Boolean get() = operation != null || pausing || task.busy
}

/** Route visibility and selection own intent; the bound connection owns all reports and lamp writes. */
internal class RobotExtensionController(private val scope: CoroutineScope,
    private val change: suspend (RobotExtensionService, suspend () -> Unit) -> Unit,
    private val canObserve: (RobotExtensionService) -> Boolean,
) {
    private class Binding(val service: RobotExtensionService)
    private val lock = RobotLock()
    private var binding: Binding? = null
    private var connection = 0L
    private var revision = 0L
    private var active = false
    private var attempted = false
    private var observer: Job? = null
    private var job: Job? = null
    private var cleanup: Job? = null
    private var cleanupBinding: Binding? = null
    val state = MutableStateFlow(ExtensionUiState())

    fun attach(service: RobotExtensionService) = lock.withLock {
        detach()
        if (!scope.isActive) return@withLock
        val current = Binding(service); binding = current
        state.value = ExtensionUiState(connection = connection, connected = true, task = service.state.value)
        observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            service.state.collect { value -> lock.withLock {
                if (binding !== current) return@withLock
                val previous = state.value.selected
                state.value = state.value.copy(task = value)
                if (previous != null && previous !in value.online.orEmpty()) {
                    state.value = state.value.copy(selected = null, error = uiText(Res.string.extensions_module_offline))
                    attempted = false; pause(current)
                }
            } }
        }
        reconcile()
    }
    fun setActive(value: Boolean) = lock.withLock {
        if (active != value) { active = value; attempted = false }
        reconcile()
    }
    fun refreshVisibility() = lock.withLock { reconcile() }
    fun selectFamily(family: ExtensionKind, connection: Long = state.value.connection) = lock.withLock {
        if (state.value.connection != connection || state.value.family == family) return@withLock
        state.value = state.value.copy(family = family, selected = null, error = null)
        attempted = false; binding?.let(::pause)
    }
    fun select(component: RobotComponent, connection: Long = state.value.connection) = lock.withLock {
        if (state.value.connection != connection || component == state.value.selected) return@withLock
        if (component !in state.value.task.online.orEmpty() || state.value.family.index(component) == null) return@withLock
        state.value = state.value.copy(selected = component, error = null)
        attempted = false
        binding?.let(::pause)
    }
    fun retry(connection: Long = state.value.connection) = lock.withLock {
        if (state.value.connection != connection || state.value.busy) return@withLock
        attempted = false; reconcile()
    }
    private fun reconcile() {
        val current = binding ?: return
        if (!active || !canObserve(current.service) || state.value.selected == null) {
            val task = current.service.state.value
            if (job?.isActive == true || task.observation in setOf(ExtensionObservation.ACTIVE, ExtensionObservation.STARTING) ||
                task.identifying != null) pause(current)
            return
        }
        if (cleanup?.isActive == true || job?.isActive == true || attempted) return
        attempted = true
        val family = state.value.family
        run(ExtensionUiOperation.OBSERVE) { it.service.setObserving(family) }
    }
    private fun pause(current: Binding) {
        if (cleanupBinding === current && cleanup?.isActive == true) return
        val previous = job; previous?.cancel(); job = null
        revision++; attempted = false
        current.service.stopIdentification()
        state.value = state.value.copy(operation = null, pausing = true)
        cleanupBinding = current
        val launched = scope.launch {
            try { previous?.cancelAndJoin(); current.service.setObserving(null) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { lock.withLock { if (binding === current) state.value = state.value.copy(error = message(error)) } }
            finally { lock.withLock {
                if (cleanupBinding === current) {
                    cleanup = null; cleanupBinding = null
                    if (binding === current) { state.value = state.value.copy(pausing = false); reconcile() }
                }
            } }
        }
        if (launched.isActive) cleanup = launched
    }
    suspend fun pauseAndJoin() {
        setActive(false)
        lock.withLock { cleanup }?.join()
    }
    fun identify(connection: Long = state.value.connection) = lock.withLock {
        if (state.value.connection != connection) return@withLock
        val selected = state.value.selected ?: return@withLock
        run(ExtensionUiOperation.IDENTIFY) { it.service.identify(selected) }
    }
    fun stopIdentification() = lock.withLock { binding?.service?.stopIdentification() }
    private fun run(kind: ExtensionUiOperation, action: suspend (Binding) -> Unit) {
        val current = binding ?: return
        val selected = state.value.selected ?: return
        if (!scope.isActive || !active || !canObserve(current.service) || state.value.busy || job?.isActive == true || cleanup?.isActive == true) return
        revision++; val token = revision
        state.value = state.value.copy(operation = kind, error = null)
        val launched = scope.launch {
            try {
                if (kind == ExtensionUiOperation.IDENTIFY) change(current.service) {
                    lock.withLock { check(binding === current && active && revision == token && state.value.selected == selected && canObserve(current.service)) }
                    currentCoroutineContext().ensureActive(); action(current)
                } else {
                    currentCoroutineContext().ensureActive(); action(current)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { lock.withLock { if (binding === current && revision == token) state.value = state.value.copy(error = message(error)) } }
            finally { lock.withLock { if (binding === current && revision == token) { job = null; state.value = state.value.copy(operation = null) } } }
        }
        if (revision == token && launched.isActive) job = launched
    }
    fun detach() = lock.withLock {
        val previous = binding; previous?.service?.stopIdentification()
        binding = null; connection++; revision++; attempted = false
        observer?.cancel(); observer = null
        cleanup = null; cleanupBinding = null
        val pending = job; pending?.cancel(); job = null
        if (previous != null) scope.launch { pending?.cancelAndJoin(); runCatching { previous.service.setObserving(null) } }
        state.value = ExtensionUiState(connection = connection)
    }
    fun clear() = lock.withLock { active = false; detach() }
}

internal fun extensionProblemMessage(problem: ExtensionProblem): UiText = uiText(when (problem) {
    ExtensionProblem.MODULES_UNKNOWN -> Res.string.extensions_modules_wait
    ExtensionProblem.MODULE_OFFLINE -> Res.string.extensions_module_offline
    ExtensionProblem.REQUEST_REJECTED -> Res.string.extensions_rejected
    ExtensionProblem.REQUEST_UNKNOWN -> Res.string.extensions_request_unknown
    ExtensionProblem.INVALID_RESPONSE -> Res.string.extensions_invalid_response
    ExtensionProblem.DISCONNECTED -> Res.string.extensions_not_connected
})
private fun message(error: Exception): UiText = if (error is ExtensionOperationException) extensionProblemMessage(error.problem)
    else robotRequestMessage(error, Res.string.extensions_task_active)
