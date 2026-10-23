@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.robot.maintenance

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.session.RobotTarget
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.robot.device.robotRequestMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong

internal data class AddressingRecovery(val target: RobotTarget, val kind: ModuleAddressingKind)
internal data class AddressingUiState(val connection: Long = 0, val connected: Boolean = false,
    val task: RobotAddressingState = RobotAddressingState(), val working: Boolean = false, val ending: Boolean = false,
    val recovery: AddressingRecovery? = null, val canRecover: Boolean = false, val error: UiText? = null) {
    val busy: Boolean get() = working || task.busy
    val blocksControl: Boolean get() = busy || task.blocksControl || recovery != null
}

/** UI intent and reconnect context; native task state remains owned by the connection service. */
internal class RobotAddressingController(private val scope: CoroutineScope,
    private val change: suspend (RobotAddressingService, suspend () -> Unit) -> Unit,
    private val canHold: (RobotAddressingService) -> Boolean) {
    private class Binding(val service: RobotAddressingService, val target: RobotTarget)
    @Volatile private var binding: Binding? = null
    private val revision = AtomicLong(0)
    private val pressRevision = AtomicLong(0)
    private var job: Job? = null
    private var observer: Job? = null
    private var lease: Job? = null
    val state = MutableStateFlow(AddressingUiState())

    fun attach(service: RobotAddressingService, target: RobotTarget) {
        detach()
        if (!scope.isActive) return
        val current = Binding(service, target); binding = current
        val recovery = state.value.recovery
        state.value = AddressingUiState(connection = revision.load(), connected = true, task = service.state.value,
            recovery = recovery, canRecover = recovery?.target?.sameRobot(target) == true)
        observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            service.state.collect { value -> state.update { if (binding === current) it.copy(task = value) else it } }
        }
    }
    fun begin(kind: ModuleAddressingKind, connection: Long = state.value.connection) {
        if (state.value.connection != connection) return
        if (state.value.recovery != null) { state.update { it.copy(error = uiText(Res.string.addressing_target_changed)) }; return }
        run(connection) { it.service.begin(kind) }
    }
    fun assign(id: Int, connection: Long = state.value.connection, candidate: Int? = state.value.task.candidateId) {
        if (candidate != null) run(connection) { it.service.assign(id, candidate) }
    }
    fun skipArmor(connection: Long = state.value.connection, step: Int = state.value.task.armorSteps) =
        run(connection) { it.service.skipArmor(step) }
    fun prepareVerification(connection: Long = state.value.connection) = run(connection) { it.service.prepareVerification() }
    fun identify(id: Int, connection: Long = state.value.connection) = run(connection) { it.service.identify(id) }
    fun confirmWheel(wheel: AddressingWheel, connection: Long = state.value.connection) = observe(connection) { it.confirmWheel(wheel) }
    fun confirmModule(id: Int, connection: Long = state.value.connection) = observe(connection) { it.confirmModule(id) }

    fun recover(connection: Long = state.value.connection) {
        if (state.value.connection != connection) return
        val recovery = state.value.recovery ?: return
        if (!state.value.canRecover) { state.update { it.copy(error = uiText(Res.string.addressing_target_changed)) }; return }
        run(connection) { current ->
            check(recovery.target.sameRobot(current.target))
            current.service.recover(recovery.kind)
            state.update { if (binding === current) it.copy(recovery = null, canRecover = false) else it }
        }
    }
    /** End can interrupt a request; cancellation is joined before termination is sent to that binding. */
    fun end(connection: Long = state.value.connection, onEnded: (() -> Unit)? = null) {
        if (state.value.connection != connection) return
        val current = binding ?: return
        if (!scope.isActive || state.value.ending) return
        val recovery = state.value.recovery
        if (recovery != null && (!recovery.target.sameRobot(current.target) ||
                !current.service.state.value.blocksControl || current.service.state.value.kind != recovery.kind)) {
            state.update { it.copy(error = uiText(Res.string.addressing_target_changed)) }; return
        }
        holdWheel(null)
        val previous = job; val token = revision.addAndFetch(1)
        state.update { it.copy(working = true, ending = true, error = null) }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.cancelAndJoin()
                currentCoroutineContext().ensureActive()
                change(current.service) {
                    check(binding === current)
                    current.service.end()
                }
                currentCoroutineContext().ensureActive()
                if (binding === current && revision.load() == token) {
                    state.update { it.copy(recovery = null, canRecover = false) }
                    onEnded?.invoke()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed(token, error) }
            finally { complete(token) }
        }
    }

    fun holdWheel(wheel: AddressingWheel?, connection: Long = state.value.connection) {
        if (state.value.connection != connection) return
        val press = pressRevision.addAndFetch(1)
        lease?.cancel(); lease = null
        val current = binding ?: return
        if (wheel != null && (state.value.busy || !canHold(current.service))) {
            runCatching { current.service.holdWheel(null) }; return
        }
        try { current.service.holdWheel(wheel) }
        catch (error: Exception) { if (wheel != null) state.update { it.copy(error = robotRequestMessage(error, Res.string.addressing_request_failed)) }; return }
        if (wheel != null) lease = scope.launch {
            try {
                while (isActive && binding === current && canHold(current.service)) { current.service.renewWheel(); delay(60) }
            } finally { if (pressRevision.load() == press) runCatching { current.service.holdWheel(null) } }
        }
    }
    /** Accessibility activation is an explicit bounded pulse, independent of pointer/key release. */
    fun pulseWheel(wheel: AddressingWheel, connection: Long = state.value.connection) {
        if (state.value.connection != connection) return
        holdWheel(wheel, connection)
        val press = pressRevision.load()
        scope.launch { delay(1_000); if (pressRevision.load() == press) holdWheel(null, connection) }
    }
    fun pageLeft(connection: Long = state.value.connection) {
        if (state.value.connection != connection) return
        holdWheel(null)
        if (state.value.task.blocksControl || state.value.busy) end(connection)
    }
    private fun observe(connection: Long, action: (RobotAddressingService) -> Unit) {
        val current = binding ?: return
        if (state.value.connection != connection || binding !== current || state.value.busy) return
        try { action(current.service) } catch (error: Exception) {
            if (binding === current) state.update { it.copy(error = robotRequestMessage(error, Res.string.addressing_request_failed)) }
        }
    }
    private fun run(connection: Long, action: suspend (Binding) -> Unit) {
        val current = binding ?: return
        if (state.value.connection != connection || binding !== current || !scope.isActive || state.value.busy || job?.isActive == true) return
        holdWheel(null)
        val token = revision.addAndFetch(1)
        state.update { it.copy(working = true, error = null) }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                change(current.service) { check(binding === current); action(current) }
                currentCoroutineContext().ensureActive()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed(token, error) }
            finally { complete(token) }
        }
    }
    private fun failed(token: Long, error: Exception) {
        state.update { if (revision.load() == token) it.copy(error = robotRequestMessage(error, Res.string.addressing_request_failed)) else it }
    }
    private fun complete(token: Long) {
        state.update { if (revision.load() == token) it.copy(working = false, ending = false) else it }
    }
    fun detach() {
        val old = binding
        holdWheel(null)
        revision.addAndFetch(1); binding = null; observer?.cancel(); observer = null; job?.cancel(); job = null
        val task = old?.service?.state?.value ?: state.value.task
        val interrupted = task.kind?.takeIf { task.blocksControl || task.phase == AddressingPhase.CLOSED && task.problem == AddressingProblem.RESULT_UNKNOWN }
        val recovery = if (old != null && interrupted != null) AddressingRecovery(old.target, interrupted) else state.value.recovery
        val retained = task.copy(busy = false, wheelHeld = null, wheelDriving = false, identifyingId = null,
            phase = if (interrupted != null) AddressingPhase.CLOSED else task.phase,
            problem = if (interrupted != null) AddressingProblem.RESULT_UNKNOWN else task.problem)
        state.value = AddressingUiState(connection = revision.load(), task = retained, recovery = recovery)
    }
    fun clear() {
        detach(); state.value = AddressingUiState(connection = revision.load())
    }
}

private fun RobotTarget.sameRobot(other: RobotTarget): Boolean = ip == other.ip && appId.equals(other.appId, ignoreCase = true) &&
    remotePort == other.remotePort && localIp == other.localIp
