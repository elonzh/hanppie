package cn.elonzh.hanppie.ui.robot.maintenance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.maintenance.firmware.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.*
import cn.elonzh.hanppie.ui.i18n.tr
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class FirmwareAction { INSTALL, INTERRUPT, EXIT, OBSERVE, RESTARTED, RECONNECT }
private data class FirmwarePrompt(val action: FirmwareAction, val connection: Long, val phase: FirmwarePhase,
    val interruptible: Boolean, val file: SelectedFirmware?, val configuration: FirmwareManifest?,
    val readiness: FirmwareReadiness, val consent: FirmwareInstallConsent)

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun RobotFirmwarePage(model: ConsoleController, modifier: Modifier = Modifier, onBack: () -> Unit,
    onSelectFile: (() -> Unit)? = null, exitRequested: Boolean = false, onExitCancelled: () -> Unit = {},
    onExitComplete: () -> Unit = onBack) {
    val controller = model.robotFirmware
    val ui by controller.state.collectAsState()
    LaunchedEffect(controller, ui.connection, ui.connected) {
        if (ui.connected && !ui.busy && ui.recovery == null && ui.task.phase == FirmwarePhase.IDLE) controller.refresh()
    }
    val console by model.state.collectAsState()
    val health by model.robotHealth.state.collectAsState()
    val camera by model.robotCamera.state.collectAsState()
    val album by model.robotAlbum.state.collectAsState()
    val files by model.robotFiles.state.collectAsState()
    val chat by model.chat.state.collectAsState()
    val talk by model.pushToTalk.state.collectAsState()
    val addressing by model.robotAddressing.state.collectAsState()
    val calibration by model.robotCalibration.state.collectAsState()
    val wifi by model.robotWifi.state.collectAsState()
    val sdr by model.robotSdr.state.collectAsState()
    val scope = rememberCoroutineScope()
    val task = ui.task
    val readiness = firmwareReadiness(health)
    val configuration = task.configuration
    val file = ui.file
    val comparison = if (configuration != null && file != null) file.inspection.manifest.compatibilityWith(configuration) else null
    val canChange = ui.connected && console.connected && !console.busy && !console.scriptRunPhase.mayBeExecuting &&
        !camera.storageBusy && camera.storage?.recording != true && !album.nativeBusy && !files.busy && !chat.running && !talk.active &&
        !addressing.blocksControl && !calibration.blocksControl && wifi.recovery == null && sdr.recovery == null
    val preparing = !ui.blocksControl || task.phase == FirmwarePhase.FAILED && ui.recovery == null
    var missingConfirmed by remember(ui.connection, file?.token, configuration, readiness.missingMotorIds) { mutableStateOf(false) }
    var powerConfirmed by remember(ui.connection, file?.token, configuration, readiness.needsPowerConfirmation) { mutableStateOf(false) }
    val consent = FirmwareInstallConsent(if (missingConfirmed) readiness.missingMotorIds else emptySet(), powerConfirmed)
    val canInstall = preparing && ui.recovery == null && canChange && !ui.busy && comparison?.matchesMetadata == true && readiness.ready &&
        (readiness.missingMotorIds.isEmpty() || missingConfirmed) && (!readiness.needsPowerConfirmation || powerConfirmed)
    var prompt by remember { mutableStateOf<FirmwarePrompt?>(null) }
    fun request(action: FirmwareAction) {
        prompt = FirmwarePrompt(action, ui.connection, task.phase, task.canInterruptTransfer, file, configuration, readiness, consent)
    }
    fun dismiss() { if (prompt?.action == FirmwareAction.EXIT) onExitCancelled(); prompt = null }
    LaunchedEffect(exitRequested) { if (exitRequested) request(FirmwareAction.EXIT) }
    LaunchedEffect(ui.connection, ui.connected, file?.token, configuration, readiness, task.phase, task.report?.kind, task.canInterruptTransfer, canInstall, canChange) {
        val p = prompt ?: return@LaunchedEffect
        val invalid = p.connection != ui.connection || p.phase != task.phase || when (p.action) {
            FirmwareAction.INSTALL -> !canInstall || p.file?.token != file?.token || p.configuration != configuration || p.readiness != readiness
            FirmwareAction.INTERRUPT -> !task.canInterruptTransfer
            FirmwareAction.EXIT -> p.interruptible != task.canInterruptTransfer
            FirmwareAction.OBSERVE -> !canChange || !ui.canRecover || task.observingInterrupted
            FirmwareAction.RESTARTED -> !canChange || !task.observingInterrupted || configuration == null || task.report?.kind == FirmwareReportKind.PROGRESS
            FirmwareAction.RECONNECT -> ui.busy || !ui.connected || !task.requiresRecovery
        }
        if (invalid) dismiss()
    }
    val pending = prompt
    WorkbenchDialog(show = pending != null, onDismissRequest = ::dismiss,
        title = tr(when (pending?.action) {
            FirmwareAction.INSTALL -> Res.string.firmware_install_question
            FirmwareAction.INTERRUPT -> Res.string.firmware_interrupt_question
            FirmwareAction.OBSERVE -> Res.string.firmware_observe_question
            FirmwareAction.RESTARTED -> Res.string.firmware_restarted_question
            FirmwareAction.RECONNECT -> Res.string.firmware_reconnect_question
            else -> Res.string.firmware_exit_question
        }), summary = when (pending?.action) {
            FirmwareAction.INSTALL -> tr(Res.string.firmware_install_summary, pending.file?.inspection?.manifest?.version.orEmpty())
            FirmwareAction.INTERRUPT -> tr(Res.string.firmware_interrupt_summary)
            FirmwareAction.OBSERVE -> tr(Res.string.firmware_observe_summary, ui.recovery?.target?.ip.orEmpty())
            FirmwareAction.RESTARTED -> tr(Res.string.firmware_restarted_summary)
            FirmwareAction.RECONNECT -> tr(Res.string.firmware_reconnect_summary)
            else -> tr(if (pending?.interruptible == true) Res.string.firmware_exit_transfer else Res.string.firmware_exit_observe)
        }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(::dismiss, Modifier.weight(1f).heightIn(min = 48.dp).testTag("firmware-confirm-cancel")) { Text(tr(Res.string.cancel)) }
            Button({
                val p = prompt ?: return@Button
                prompt = null
                if (p.action == FirmwareAction.EXIT) onExitCancelled()
                val current = controller.state.value
                if (current.connection != p.connection) return@Button
                when (p.action) {
                    FirmwareAction.INSTALL -> if (canInstall && p.file != null && p.configuration != null)
                        controller.install(p.consent, p.configuration, p.file.token, p.connection)
                    FirmwareAction.INTERRUPT -> controller.interruptTransfer(p.connection)
                    FirmwareAction.EXIT -> if (p.interruptible) controller.interruptTransfer(p.connection, onExitComplete) else onExitComplete()
                    FirmwareAction.OBSERVE -> controller.observePrevious(p.connection)
                    FirmwareAction.RESTARTED -> controller.confirmRestarted(p.connection)
                    FirmwareAction.RECONNECT -> scope.launch { model.pauseConnection(); model.reconnectFirmware() }
                }
            }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("firmware-confirm"), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(tr(when (pending?.action) {
                    FirmwareAction.INSTALL -> Res.string.firmware_install
                    FirmwareAction.INTERRUPT -> Res.string.firmware_interrupt
                    FirmwareAction.OBSERVE -> Res.string.firmware_observe
                    FirmwareAction.RESTARTED -> Res.string.firmware_confirm_normal
                    FirmwareAction.RECONNECT -> Res.string.firmware_reconnect
                    else -> if (pending?.interruptible == true) Res.string.firmware_stop_leave else Res.string.firmware_leave
                }))
            }
        }
    }
    Column(modifier.fillMaxSize().testTag("robot-firmware-page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().testTag("firmware-header"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchIconButton(tr(Res.string.back), WorkbenchGlyph.BACK, onBack, enabled = !ui.interrupting, tag = "firmware-back")
            Text(tr(Res.string.firmware_title), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("firmware-body"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = HanppieDesignTokens.PageMaxWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (task.phase !in setOf(FirmwarePhase.IDLE, FirmwarePhase.READING_CONFIGURATION)) FirmwareTaskStatus(task, ui.connected)
                task.problem?.let { FirmwareError(tr(firmwareProblemText(it)), "firmware-problem") }
                if (task.problem == null) ui.error?.let { FirmwareError(it.resolve(), "firmware-error") }
                if (ui.recovery != null) {
                    Text(tr(Res.string.firmware_recovery_target, ui.recovery!!.target.ip), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("firmware-recovery"))
                    when {
                        !ui.connected -> Button(model::reconnectFirmware, Modifier.heightIn(min = 48.dp).testTag("firmware-reconnect"),
                            enabled = !console.busy && !ui.busy) { Text(tr(Res.string.firmware_reconnect)) }
                        !ui.canRecover -> {
                            Text(tr(Res.string.firmware_wrong_target), fontSize = 15.sp)
                            Button(model::disconnect, Modifier.heightIn(min = 48.dp).testTag("firmware-disconnect"), enabled = !console.busy) {
                                Text(tr(Res.string.disconnect_close_session))
                            }
                        }
                        !task.observingInterrupted -> Button({ request(FirmwareAction.OBSERVE) }, Modifier.heightIn(min = 48.dp)
                            .testTag("firmware-observe"), enabled = canChange && !ui.busy) { Text(tr(Res.string.firmware_observe)) }
                        else -> {
                            Text(tr(Res.string.firmware_recovery_guidance), fontSize = 15.sp)
                            Button({ request(FirmwareAction.RESTARTED) }, Modifier.heightIn(min = 48.dp).testTag("firmware-restarted"),
                                enabled = canChange && !ui.busy && configuration != null && task.report?.kind != FirmwareReportKind.PROGRESS) {
                                Text(tr(Res.string.firmware_confirm_normal))
                            }
                        }
                    }
                } else if (task.requiresRecovery && !ui.busy && task.phase !in setOf(FirmwarePhase.REPORTED_SUCCESS, FirmwarePhase.FAILED)) {
                    Text(tr(Res.string.firmware_wait_device), fontSize = 15.sp)
                    if (task.phase != FirmwarePhase.REPORTED_SUCCESS) Button({ request(FirmwareAction.RECONNECT) },
                        Modifier.heightIn(min = 48.dp).testTag("firmware-reconnect-current"), enabled = ui.connected && !console.busy) {
                        Text(tr(Res.string.firmware_reconnect_after_restart))
                    }
                }
                if ((ui.recovery == null || task.observingInterrupted) &&
                    (preparing || task.observingInterrupted || task.phase in setOf(FirmwarePhase.REPORTED_SUCCESS, FirmwarePhase.VERIFIED, FirmwarePhase.RECOVERED))) {
                    Text(tr(Res.string.firmware_device_configuration), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (configuration == null) Text(tr(if (ui.connected) Res.string.firmware_read_needed else Res.string.firmware_connect_needed), fontSize = 15.sp)
                    else {
                        Text(tr(Res.string.firmware_device_version, configuration.version, configuration.deviceId),
                            Modifier.testTag("firmware-device-version"), fontSize = 15.sp)
                        task.configurationAgeSeconds?.let { Text(tr(Res.string.firmware_snapshot_age, it), fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    }
                    if (task.phase != FirmwarePhase.REPORTED_SUCCESS) Button({ controller.refresh() }, Modifier.heightIn(min = 48.dp).testTag("firmware-refresh"), enabled = canChange && !ui.busy) {
                        Text(tr(if (ui.working) Res.string.firmware_reading else if (task.phase == FirmwarePhase.REPORTED_SUCCESS)
                            Res.string.firmware_check_installed else Res.string.firmware_read_configuration))
                    }
                }
                if (preparing) {
                    Text(tr(Res.string.firmware_local_package), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(ui.selectedName ?: tr(Res.string.firmware_select_hint), Modifier.testTag("firmware-file-name"), fontSize = 15.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onSelectFile != null) Button(onSelectFile, Modifier.heightIn(min = 48.dp).testTag("firmware-select"), enabled = !ui.busy) {
                            Text(tr(if (ui.selectedName == null) Res.string.firmware_select else Res.string.firmware_select_another))
                        }
                        if (ui.canReinspect && !ui.inspecting) Button(controller::reinspect, Modifier.heightIn(min = 48.dp).testTag("firmware-reinspect"),
                            enabled = !ui.busy) { Text(tr(Res.string.firmware_reinspect)) }
                        if (ui.inspecting) Button(controller::cancelInspection, Modifier.heightIn(min = 48.dp).testTag("firmware-cancel-inspection")) {
                            Text(tr(Res.string.firmware_cancel_inspection))
                        }
                    }
                    if (ui.inspecting) FirmwareByteProgress(ui.inspectedBytes, ui.inspectionSize, "firmware-inspection-progress")
                    ui.fileProblem?.let { FirmwareError(tr(firmwarePackageProblemText(it)), "firmware-file-problem") }
                    ui.fileError?.let { FirmwareError(it.resolve(), "firmware-file-problem") }
                    if (file != null) {
                        Text(tr(Res.string.firmware_package_version, file.inspection.manifest.version, file.inspection.manifest.deviceId,
                            file.inspection.manifest.modules.size), Modifier.testTag("firmware-file-version"), fontSize = 15.sp)
                        Text(tr(Res.string.firmware_integrity_checked), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        comparison?.let { result ->
                            result.issues.forEach { FirmwareError(tr(firmwareCompatibilityText(it)), "firmware-compatibility") }
                            var expanded by remember(file.token, configuration) { mutableStateOf(false) }
                            Button({ expanded = !expanded }, Modifier.heightIn(min = 48.dp).testTag("firmware-modules")) {
                                Text(tr(if (expanded) Res.string.firmware_hide_modules else Res.string.firmware_show_modules))
                            }
                            if (expanded) result.changes.forEach { change ->
                                Text(tr(Res.string.firmware_module_change, change.moduleId, change.hardwareType,
                                    change.installed, change.available ?: tr(Res.string.firmware_variant_absent)),
                                    Modifier.testTag("firmware-module-${change.moduleId}-${change.hardwareType}"), fontSize = 14.sp)
                            }
                        }
                    }
                    if (ui.connected) {
                        readiness.issues.forEach { FirmwareError(tr(firmwareReadinessText(it)), "firmware-readiness") }
                        if (readiness.missingMotorIds.isNotEmpty()) FirmwareConsent(missingConfirmed, { missingConfirmed = it },
                            tr(Res.string.firmware_missing_motors, readiness.missingMotorIds.joinToString { (it - 0x0313).toString() }),
                            !ui.busy, "firmware-motors-consent")
                        if (readiness.needsPowerConfirmation) FirmwareConsent(powerConfirmed, { powerConfirmed = it },
                            tr(Res.string.firmware_external_power), !ui.busy, "firmware-power-consent")
                        if (!canChange) Text(tr(Res.string.firmware_task_active), fontSize = 15.sp)
                    }
                }
            }
        }
        when {
            task.canInterruptTransfer -> Button({ request(FirmwareAction.INTERRUPT) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("firmware-interrupt"), enabled = !ui.interrupting) { Text(tr(Res.string.firmware_interrupt)) }
            task.phase == FirmwarePhase.REPORTED_SUCCESS && (ui.recovery == null || task.observingInterrupted) ->
                Button({ controller.refresh() }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("firmware-refresh"),
                    enabled = canChange && !ui.busy, colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(tr(if (ui.working) Res.string.firmware_reading else Res.string.firmware_check_installed))
                }
            preparing && ui.recovery == null -> Button({ request(FirmwareAction.INSTALL) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("firmware-install"), enabled = canInstall, colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(tr(if (task.phase == FirmwarePhase.FAILED) Res.string.firmware_retry_install else Res.string.firmware_install))
            }
        }
    }
}

@Composable
private fun FirmwareError(message: String, tag: String) {
    Text(message, Modifier.testTag(tag), fontSize = 15.sp, color = MiuixTheme.colorScheme.error)
}

@Composable
private fun FirmwareConsent(checked: Boolean, onChecked: (Boolean) -> Unit, label: String, enabled: Boolean, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChecked)
        .testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(if (checked) ToggleableState.On else ToggleableState.Off, null, enabled = enabled)
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
    }
}

@Composable
private fun FirmwareByteProgress(done: Long, total: Long, tag: String) {
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr(Res.string.firmware_bytes, done / 1024, total / 1024), fontSize = 15.sp)
        LinearProgressIndicator(progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FirmwareTaskStatus(task: RobotFirmwareState, connected: Boolean) {
    val report = task.report
    val phase = if (!connected && task.requiresRecovery) FirmwarePhase.CLOSED else task.phase
    Column(Modifier.fillMaxWidth().testTag("firmware-task"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(firmwarePhaseText(phase)), Modifier.testTag("firmware-phase"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        task.expectedPackage?.let { Text(tr(Res.string.firmware_target_version, it.manifest.version), fontSize = 15.sp) }
        when (phase) {
            FirmwarePhase.PREPARING -> FirmwareByteProgress(task.inspectedBytes, task.expectedPackage?.archiveSize ?: 0, "firmware-prepare-progress")
            FirmwarePhase.TRANSFERRING -> FirmwareByteProgress(task.uploadedBytes, task.expectedPackage?.archiveSize ?: 0, "firmware-upload-progress")
            FirmwarePhase.VERIFIED -> Text(tr(Res.string.firmware_verified_detail), Modifier.testTag("firmware-result"), fontSize = 15.sp)
            FirmwarePhase.RECOVERED -> Text(tr(Res.string.firmware_recovered_detail), Modifier.testTag("firmware-result"), fontSize = 15.sp)
            FirmwarePhase.REPORTED_SUCCESS -> Text(tr(Res.string.firmware_readback_needed), fontSize = 15.sp)
            else -> Unit
        }
        if (connected && report?.kind == FirmwareReportKind.PROGRESS) {
            val percent = report.valueRaw.takeIf { it in 0..100 }
            Text(if (percent == null) tr(Res.string.firmware_native_progress_unknown) else tr(Res.string.firmware_native_progress, percent),
                Modifier.testTag("firmware-native-progress"), fontSize = 16.sp)
        }
        if (task.phase == FirmwarePhase.FAILED && report?.kind == FirmwareReportKind.END)
            Text(tr(Res.string.firmware_native_failure, report.valueRaw, report.detailRaw), fontSize = 15.sp)
        if (connected) task.reportAgeSeconds?.let { Text(tr(Res.string.firmware_report_age, it), fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
    }
}

private fun firmwarePhaseText(phase: FirmwarePhase): StringResource = when (phase) {
    FirmwarePhase.IDLE -> Res.string.firmware_ready
    FirmwarePhase.READING_CONFIGURATION -> Res.string.firmware_reading
    FirmwarePhase.PREPARING -> Res.string.firmware_preparing
    FirmwarePhase.ENTERING_LOADER -> Res.string.firmware_entering_loader
    FirmwarePhase.NEGOTIATING -> Res.string.firmware_negotiating
    FirmwarePhase.TRANSFERRING -> Res.string.firmware_transferring
    FirmwarePhase.VERIFYING_TRANSFER -> Res.string.firmware_verifying_transfer
    FirmwarePhase.INSTALLING -> Res.string.firmware_installing
    FirmwarePhase.WAITING_REBOOT -> Res.string.firmware_waiting_reboot
    FirmwarePhase.REPORTED_SUCCESS -> Res.string.firmware_reported_success
    FirmwarePhase.VERIFIED -> Res.string.firmware_verified
    FirmwarePhase.RECOVERED -> Res.string.firmware_recovered
    FirmwarePhase.FAILED -> Res.string.firmware_failed
    FirmwarePhase.UNCERTAIN -> Res.string.firmware_uncertain
    FirmwarePhase.CLOSED -> Res.string.firmware_disconnected
}
private fun firmwareProblemText(problem: FirmwareProblem): StringResource = when (problem) {
    FirmwareProblem.PREPARATION_FAILED -> Res.string.firmware_problem_preparation
    FirmwareProblem.CONFIG_CHANGED -> Res.string.firmware_problem_configuration
    FirmwareProblem.INCOMPATIBLE -> Res.string.firmware_problem_incompatible
    FirmwareProblem.NOT_READY -> Res.string.firmware_problem_not_ready
    FirmwareProblem.CONSENT_REQUIRED -> Res.string.firmware_problem_consent
    FirmwareProblem.PACKAGE_CHANGED -> Res.string.firmware_file_changed
    FirmwareProblem.FTP_FAILED -> Res.string.firmware_problem_ftp
    FirmwareProblem.ENDPOINT_MISMATCH -> Res.string.firmware_problem_endpoint
    FirmwareProblem.REQUEST_UNKNOWN -> Res.string.firmware_problem_request
    FirmwareProblem.TRANSFER_INTERRUPTED -> Res.string.firmware_problem_interrupted
    FirmwareProblem.REPORT_UNKNOWN -> Res.string.firmware_problem_report
    FirmwareProblem.REPORT_STALE -> Res.string.firmware_problem_stale
    FirmwareProblem.DEVICE_FAILED -> Res.string.firmware_problem_device
    FirmwareProblem.READBACK_MISMATCH -> Res.string.firmware_problem_readback
}
private fun firmwarePackageProblemText(problem: FirmwarePackageProblem): StringResource = when (problem) {
    FirmwarePackageProblem.INVALID_MANIFEST -> Res.string.firmware_package_manifest
    FirmwarePackageProblem.INVALID_ARCHIVE -> Res.string.firmware_package_invalid
    FirmwarePackageProblem.UNSUPPORTED_ARCHIVE -> Res.string.firmware_package_unsupported
    FirmwarePackageProblem.MISSING_FILE -> Res.string.firmware_package_missing
    FirmwarePackageProblem.UNEXPECTED_FILE -> Res.string.firmware_package_extra
    FirmwarePackageProblem.SIZE_MISMATCH -> Res.string.firmware_package_size
    FirmwarePackageProblem.CHECKSUM_MISMATCH -> Res.string.firmware_package_checksum
}
private fun firmwareCompatibilityText(issue: FirmwareCompatibilityIssue): StringResource = when (issue) {
    FirmwareCompatibilityIssue.PRODUCT_MISMATCH -> Res.string.firmware_product_mismatch
    FirmwareCompatibilityIssue.DOWNGRADE -> Res.string.firmware_downgrade
    FirmwareCompatibilityIssue.MISSING_VARIANT -> Res.string.firmware_missing_variant
    FirmwareCompatibilityIssue.INVALID_VERSION -> Res.string.firmware_invalid_version
}
private fun firmwareReadinessText(issue: FirmwareReadinessIssue): StringResource = when (issue) {
    FirmwareReadinessIssue.MODULES_UNKNOWN -> Res.string.firmware_modules_unknown
    FirmwareReadinessIssue.POWER_UNKNOWN -> Res.string.firmware_power_unknown
    FirmwareReadinessIssue.BATTERY_LOW -> Res.string.firmware_battery_low
    FirmwareReadinessIssue.ADDRESS_CONFLICT -> Res.string.firmware_address_conflict
}
