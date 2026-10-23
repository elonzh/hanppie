@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.robot.maintenance

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.maintenance.firmware.*
import cn.elonzh.hanppie.robot.session.RobotTarget
import cn.elonzh.hanppie.robot.session.RobotRequestException
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.robot.device.robotRequestMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.Source
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong

internal data class SelectedFirmware(val name: String, val inspection: FirmwarePackageInspection, val token: Long)
internal data class FirmwareRecovery(val target: RobotTarget, val previous: RobotFirmwareState)
internal data class FirmwareUiState(
    val connection: Long = 0, val connected: Boolean = false, val task: RobotFirmwareState = RobotFirmwareState(),
    val working: Boolean = false, val interrupting: Boolean = false, val recovery: FirmwareRecovery? = null,
    val canRecover: Boolean = false, val file: SelectedFirmware? = null, val selectedName: String? = null, val inspecting: Boolean = false,
    val inspectedBytes: Long = 0, val inspectionSize: Long = 0, val canReinspect: Boolean = false,
    val fileProblem: FirmwarePackageProblem? = null, val fileError: UiText? = null, val error: UiText? = null,
) {
    val busy: Boolean get() = working || interrupting || task.busy || inspecting
    val blocksControl: Boolean get() = working || interrupting || task.blocksControl || recovery != null
}

/** UI intent and source identity live here; native state and transfer ownership live in RobotFirmwareService. */
internal class RobotFirmwareController(private val scope: CoroutineScope,
    private val change: suspend (RobotFirmwareService, suspend () -> Unit) -> Unit,
    private val onOccupied: (RobotFirmwareService) -> Unit,
) {
    private class Binding(val service: RobotFirmwareService, val target: RobotTarget) { var recovering = false }
    private class PackageSource(val name: String, val size: () -> Long, val open: () -> Source)
    @Volatile private var binding: Binding? = null
    private val revision = AtomicLong(0)
    private val fileRevision = AtomicLong(0)
    private var job: Job? = null
    private var observer: Job? = null
    private var fileJob: Job? = null
    private var source: PackageSource? = null
    val state = MutableStateFlow(FirmwareUiState())

    fun selectPackage(name: String, size: () -> Long, open: () -> Source) {
        if (!scope.isActive || state.value.working || state.value.task.busy || state.value.interrupting || job?.isActive == true) return
        source = PackageSource(name, size, open)
        inspectSelected()
    }
    fun reinspect() {
        if (!state.value.busy && job?.isActive != true) inspectSelected()
    }
    private fun inspectSelected() {
        val selected = source ?: return
        fileJob?.cancel()
        val token = fileRevision.addAndFetch(1)
        state.update { it.copy(file = null, selectedName = selected.name, inspecting = true, inspectedBytes = 0, inspectionSize = 0,
            canReinspect = true, fileProblem = null, fileError = null) }
        fileJob = scope.launch {
            try {
                val inspection = withContext(Dispatchers.IO) {
                    val size = selected.size()
                    state.update { if (fileRevision.load() == token) it.copy(inspectionSize = size) else it }
                    selected.open().use { input -> FirmwareArchive.inspect(input, size) { count ->
                        state.update { if (fileRevision.load() == token) it.copy(inspectedBytes = count) else it }
                    } }
                }
                currentCoroutineContext().ensureActive()
                state.update { if (fileRevision.load() == token) it.copy(file = SelectedFirmware(selected.name, inspection, token)) else it }
            } catch (error: CancellationException) { throw error }
            catch (error: FirmwarePackageException) {
                state.update { if (fileRevision.load() == token) it.copy(fileProblem = error.problem) else it }
            } catch (_: Exception) {
                state.update { if (fileRevision.load() == token) it.copy(fileError = uiText(Res.string.firmware_file_failed)) else it }
            } finally {
                state.update { if (fileRevision.load() == token) it.copy(inspecting = false) else it }
            }
        }
    }
    fun cancelInspection() { fileJob?.cancel() }

    fun attach(service: RobotFirmwareService, target: RobotTarget) {
        detach()
        if (!scope.isActive) return
        val current = Binding(service, target); binding = current
        val initial = service.state.value
        state.update { it.copy(connection = revision.load(), connected = true, task = initial,
            canRecover = it.recovery?.target?.sameFirmwareRobot(target) == true, error = null) }
        if (initial.blocksControl) onOccupied(service)
        observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            service.state.collect { value ->
                val oldBlocking = state.value.task.blocksControl
                state.update { ui -> if (binding !== current) ui else {
                    val again = value.observingInterrupted && value.requiresRecovery && ui.recovery == null
                    if (again) current.recovering = true
                    val resolved = current.recovering && !value.requiresRecovery && value.phase in setOf(FirmwarePhase.VERIFIED, FirmwarePhase.RECOVERED)
                    if (resolved) current.recovering = false
                    ui.copy(task = value, recovery = if (resolved) null else if (again) FirmwareRecovery(current.target, value) else ui.recovery,
                        canRecover = if (resolved) false else again || ui.canRecover)
                } }
                if (binding === current && !oldBlocking && value.blocksControl && !state.value.working) onOccupied(service)
            }
        }
    }

    fun refresh(connection: Long = state.value.connection) = run(connection) { it.service.refreshConfiguration() }
    fun install(consent: FirmwareInstallConsent, configuration: FirmwareManifest, fileToken: Long,
        connection: Long = state.value.connection) {
        if (state.value.connection != connection) return
        val selected = state.value.file ?: return
        val selectedSource = source ?: return
        if (selected.token != fileToken || state.value.task.configuration != configuration) { changed(); return }
        run(connection) { current ->
            check(state.value.file?.token == fileToken && source === selectedSource)
            current.service.install(selected.inspection, configuration, consent, selectedSource.open)
        }
    }
    fun observePrevious(connection: Long = state.value.connection) {
        if (state.value.connection != connection) return
        val recovery = state.value.recovery ?: return
        if (!state.value.canRecover) { changed(); return }
        run(connection, allowObservation = true) { current ->
            check(recovery.target.sameFirmwareRobot(current.target))
            current.recovering = true
            try { current.service.observeInterrupted(recovery.previous) }
            catch (error: Exception) {
                current.recovering = current.service.state.value.observingInterrupted
                throw error
            }
        }
    }
    fun confirmRestarted(connection: Long = state.value.connection) = run(connection) { current ->
        check(current.recovering && state.value.canRecover && state.value.recovery?.target?.sameFirmwareRobot(current.target) == true)
        check(current.service.state.value.configuration != null)
        current.service.refreshConfiguration()
        if (current.service.state.value.requiresRecovery) current.service.confirmRestartedAndChecked()
    }
    fun interruptTransfer(connection: Long = state.value.connection, onStopped: (() -> Unit)? = null) {
        val current = binding ?: return
        if (state.value.connection != connection || !scope.isActive || state.value.interrupting || !current.service.state.value.canInterruptTransfer) return
        val previous = job; val token = revision.addAndFetch(1)
        state.update { it.copy(working = true, interrupting = true, error = null) }
        val launched = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) { current.service.interruptTransfer() }
                previous?.cancelAndJoin(); currentCoroutineContext().ensureActive()
                if (binding === current && revision.load() == token) onStopped?.invoke()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed(token, error) }
            finally { complete(token) }
        }
        if (revision.load() == token && launched.isActive) job = launched
    }
    private fun run(connection: Long, allowObservation: Boolean = false, action: suspend (Binding) -> Unit) {
        val current = binding ?: return
        if (state.value.connection != connection || !scope.isActive || state.value.busy || job?.isActive == true) return
        val pending = state.value.recovery
        if (pending != null && (!pending.target.sameFirmwareRobot(current.target) || !current.recovering && !allowObservation)) { changed(); return }
        val token = revision.addAndFetch(1)
        state.update { it.copy(working = true, error = null) }
        val launched = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                change(current.service) { check(binding === current); action(current) }
                currentCoroutineContext().ensureActive()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed(token, error) }
            finally { complete(token) }
        }
        if (revision.load() == token && launched.isActive) job = launched
    }
    private fun changed() { state.update { it.copy(error = uiText(Res.string.firmware_target_changed)) } }
    private fun failed(token: Long, error: Exception) {
        val confirmed = binding?.service?.state?.value?.phase in setOf(FirmwarePhase.REPORTED_SUCCESS, FirmwarePhase.VERIFIED)
        state.update { ui -> if (revision.load() != token) ui else {
            val changed = error is FirmwareOperationException && error.problem == FirmwareProblem.PACKAGE_CHANGED || error is FirmwarePackageException
            ui.copy(file = if (changed) null else ui.file,
                error = if (changed) uiText(Res.string.firmware_file_changed) else if (confirmed && error is RobotRequestException) null
                    else robotRequestMessage(error, Res.string.firmware_request_failed))
        } }
    }
    private fun complete(token: Long) {
        if (revision.load() != token) return
        job = null
        state.update { if (revision.load() == token) it.copy(working = false, interrupting = false) else it }
    }
    fun detach() {
        val old = binding
        revision.addAndFetch(1); binding = null; observer?.cancel(); observer = null; job?.cancel(); job = null
        val task = old?.service?.state?.value ?: state.value.task
        val pending = state.value.recovery
        val recovery = if (old != null && task.requiresRecovery && (pending == null || old.recovering && pending.target.sameFirmwareRobot(old.target)))
            FirmwareRecovery(old.target, task) else pending
        state.update { it.copy(connection = revision.load(), connected = false, task = task.copy(busy = false),
            working = false, interrupting = false, recovery = recovery, canRecover = false, error = null) }
    }
    fun clear() {
        detach(); fileRevision.addAndFetch(1); fileJob?.cancel(); fileJob = null; source = null
        state.value = FirmwareUiState(connection = revision.load())
    }
}
private fun RobotTarget.sameFirmwareRobot(other: RobotTarget): Boolean = ip == other.ip && appId.equals(other.appId, ignoreCase = true) &&
    remotePort == other.remotePort && localIp == other.localIp
