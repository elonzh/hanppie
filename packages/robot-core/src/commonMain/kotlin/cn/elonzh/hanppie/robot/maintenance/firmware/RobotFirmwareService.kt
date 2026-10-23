package cn.elonzh.hanppie.robot.maintenance.firmware

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.protocol.hexBytes
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

enum class FirmwarePhase { IDLE, READING_CONFIGURATION, PREPARING, ENTERING_LOADER, NEGOTIATING, TRANSFERRING, VERIFYING_TRANSFER,
    INSTALLING, WAITING_REBOOT, REPORTED_SUCCESS, VERIFIED, RECOVERED, FAILED, UNCERTAIN, CLOSED }
enum class FirmwareProblem { PREPARATION_FAILED, CONFIG_CHANGED, INCOMPATIBLE, NOT_READY, CONSENT_REQUIRED, PACKAGE_CHANGED,
    FTP_FAILED, ENDPOINT_MISMATCH, REQUEST_UNKNOWN, TRANSFER_INTERRUPTED, REPORT_UNKNOWN, REPORT_STALE, DEVICE_FAILED, READBACK_MISMATCH }
class FirmwareOperationException(val problem: FirmwareProblem) : Exception("Firmware operation: $problem")

data class RobotFirmwareState(
    val phase: FirmwarePhase = FirmwarePhase.IDLE,
    val busy: Boolean = false,
    val configuration: FirmwareManifest? = null,
    val configurationAgeSeconds: Long? = null,
    val expectedPackage: FirmwarePackageInspection? = null,
    val inspectedBytes: Long = 0,
    val uploadedBytes: Long = 0,
    val transferAcceptedCode: Int? = null,
    val loaderMayBeActive: Boolean = false,
    val problem: FirmwareProblem? = null,
    val report: FirmwareUpgradeReport? = null,
    val reportAgeSeconds: Long? = null,
    val rebootTypeRaw: Int? = null,
    val installedMatchesTarget: Boolean? = null,
    val observingInterrupted: Boolean = false,
    val restartConfirmedByOperator: Boolean = false,
    val connectionContext: Long = 0,
) {
    val blocksControl: Boolean get() = phase != FirmwarePhase.CLOSED && (loaderMayBeActive || phase == FirmwarePhase.PREPARING)
    val requiresRecovery: Boolean get() = loaderMayBeActive
    val canInterruptTransfer: Boolean get() = busy && phase in setOf(FirmwarePhase.PREPARING, FirmwarePhase.ENTERING_LOADER,
        FirmwarePhase.NEGOTIATING, FirmwarePhase.TRANSFERRING)
}

interface RobotFirmwareService {
    val state: StateFlow<RobotFirmwareState>
    /** Reads current device metadata; after a current-round success report it also checks the expected package versions. */
    suspend fun refreshConfiguration()
    /** Revalidates the archive and configuration before entering loader. Every invocation is a new explicit installation intent. */
    suspend fun install(expected: FirmwarePackageInspection, configuration: FirmwareManifest, consent: FirmwareInstallConsent, openArchive: () -> Source)
    /** Interrupts local preparation/transfer only. It cannot cancel device flashing or undo loader entry. */
    fun interruptTransfer()
    suspend fun observeInterrupted(previous: RobotFirmwareState)
    /** Only after reconnect, fresh metadata and the operator's observation of a completed restart/self-test. */
    fun confirmRestartedAndChecked()
}

@OptIn(ExperimentalAtomicApi::class)
internal class FirmwareCommands(
    private val lock: RobotLock,
    private val targetIp: String,
    private val inventory: () -> RobotHealth,
    request: suspend (Int, Int, Int, ByteArray) -> ByteArray,
    private val createFtp: () -> RobotFtpClient,
    private val send: (Int, Int, Int, ByteArray) -> Unit,
    private val nowMillis: () -> Long = { robotNanoTime() / 1_000_000 },
) : RobotFirmwareService {
    private val protocol = FirmwareProtocol(request)
    private val access = Mutex()
    private val context = nextContext.addAndFetch(1)
    private val mutable = MutableStateFlow(RobotFirmwareState(connectionContext = context))
    override val state = mutable.asStateFlow()
    private var closed = false
    private var stopRequested = false
    private var activeFtp: RobotFtpClient? = null
    private var configurationAt: Long? = null
    private var reportAt: Long? = null
    private var finishDispatched = false
    private var roundProgressSeen = false

    override suspend fun refreshConfiguration() = task {
        val oldPhase = lock.withLock {
            ensureOpen()
            val phase = state.value.phase
            if (!state.value.loaderMayBeActive) mutable.value = state.value.copy(phase = FirmwarePhase.READING_CONFIGURATION, problem = null)
            phase
        }
        try { readConfiguration() }
        finally { lock.withLock {
            if (!closed && state.value.phase == FirmwarePhase.READING_CONFIGURATION) mutable.value = state.value.copy(phase = oldPhase)
        } }
    }

    override suspend fun install(expected: FirmwarePackageInspection, configuration: FirmwareManifest,
        consent: FirmwareInstallConsent, openArchive: () -> Source) = task {
        val previouslyActive = lock.withLock {
            ensureOpen()
            val s = state.value
            check(!s.loaderMayBeActive || s.phase == FirmwarePhase.FAILED && s.report?.kind == FirmwareReportKind.END) {
                "请等待升级结果或重连原机器人后核对恢复状态"
            }
            stopRequested = false; finishDispatched = false; roundProgressSeen = false; reportAt = null
            mutable.value = s.copy(phase = FirmwarePhase.PREPARING, expectedPackage = expected, inspectedBytes = 0, uploadedBytes = 0,
                transferAcceptedCode = null, problem = null, report = null, reportAgeSeconds = null, rebootTypeRaw = null,
                installedMatchesTarget = null, restartConfirmedByOperator = false, observingInterrupted = false)
            s.loaderMayBeActive
        }
        try {
            val actual = withContext(Dispatchers.IO) { openArchive().use { source ->
                FirmwareArchive.inspect(source, expected.archiveSize) { count -> lock.withLock {
                    ensureRunning(); mutable.value = state.value.copy(inspectedBytes = count)
                } }
            } }
            if (actual != expected) fail(FirmwareProblem.PACKAGE_CHANGED)
            val current = readConfiguration()
            if (current != configuration) fail(FirmwareProblem.CONFIG_CHANGED)
            if (!actual.manifest.compatibilityWith(current).matchesMetadata) fail(FirmwareProblem.INCOMPATIBLE)
            lock.withLock {
                ensureRunning()
                if (state.value.report?.kind == FirmwareReportKind.PROGRESS) fail(FirmwareProblem.REPORT_UNKNOWN)
                val readiness = firmwareReadiness(inventory())
                if (!readiness.ready) fail(FirmwareProblem.NOT_READY)
                if (!consent.missingMotorIds.containsAll(readiness.missingMotorIds) ||
                    readiness.needsPowerConfirmation && !consent.externalPowerConfirmed) fail(FirmwareProblem.CONSENT_REQUIRED)
                // Mark uncertainty before dispatch: a missing loader ACK is not proof that entry failed.
                mutable.value = state.value.copy(phase = FirmwarePhase.ENTERING_LOADER, loaderMayBeActive = true)
            }
            protocol.enterLoader(); checkRunning()
            lock.withLock { ensureRunning(); mutable.value = state.value.copy(phase = FirmwarePhase.NEGOTIATING) }
            val endpoint = protocol.requestTransfer(actual.archiveSize); checkRunning()
            // This task may write only to the robot whose App session returned the negotiation response.
            if (endpoint.ip != targetIp.split('.').joinToString(".") { it.toInt().toString() }) fail(FirmwareProblem.ENDPOINT_MISMATCH)
            lock.withLock { ensureRunning(); mutable.value = state.value.copy(phase = FirmwarePhase.TRANSFERRING) }
            withContext(Dispatchers.IO) { upload(endpoint, actual, openArchive) }
            checkRunning()
            lock.withLock {
                ensureRunning()
                ensureRunning(); finishDispatched = true; roundProgressSeen = false; reportAt = nowMillis()
                mutable.value = state.value.copy(phase = FirmwarePhase.VERIFYING_TRANSFER, report = null, reportAgeSeconds = 0)
            }
            val accepted = protocol.finishTransfer(actual.archiveMd5.hexBytes())
            checkRunning()
            lock.withLock {
                val phase = if (state.value.phase == FirmwarePhase.VERIFYING_TRANSFER) FirmwarePhase.INSTALLING else state.value.phase
                mutable.value = state.value.copy(phase = phase, transferAcceptedCode = accepted)
            }
        } catch (error: Exception) {
            lock.withLock {
                if (!closed) {
                    val s = state.value
                    val explicitLoaderRejection = s.phase == FirmwarePhase.ENTERING_LOADER && !previouslyActive &&
                        s.report?.kind != FirmwareReportKind.PROGRESS && error is RobotRequestException && error.problem == RequestProblem.REJECTED
                    val active = s.loaderMayBeActive && !explicitLoaderRejection
                    val problem = when {
                        stopRequested || error is CancellationException -> FirmwareProblem.TRANSFER_INTERRUPTED
                        error is FirmwareOperationException -> error.problem
                        error is RobotRequestException -> FirmwareProblem.REQUEST_UNKNOWN
                        s.phase == FirmwarePhase.TRANSFERRING -> FirmwareProblem.FTP_FAILED
                        else -> FirmwareProblem.PREPARATION_FAILED
                    }
                    // A device terminal/progress report received before the request ACK remains authoritative.
                    val phase = if (finishDispatched && s.phase in setOf(FirmwarePhase.REPORTED_SUCCESS, FirmwarePhase.FAILED,
                        FirmwarePhase.WAITING_REBOOT, FirmwarePhase.INSTALLING)) s.phase else if (active) FirmwarePhase.UNCERTAIN else FirmwarePhase.FAILED
                    mutable.value = s.copy(phase = phase, loaderMayBeActive = active, problem = when {
                        phase == FirmwarePhase.REPORTED_SUCCESS -> null
                        phase == FirmwarePhase.FAILED && s.problem == FirmwareProblem.DEVICE_FAILED -> s.problem
                        else -> problem
                    })
                }
            }
            throw error
        }
    }

    private suspend fun readConfiguration(): FirmwareManifest {
        val bytes = protocol.configuration(); checkRunning()
        val result = FirmwareManifest.parse(bytes)
        currentCoroutineContext().ensureActive()
        lock.withLock {
            ensureRunning(); configurationAt = nowMillis()
            val s = state.value
            val matches = s.expectedPackage?.manifest?.matchesInstalled(result)
            val verified = s.phase == FirmwarePhase.REPORTED_SUCCESS && matches == true
            mutable.value = s.copy(configuration = result, configurationAgeSeconds = 0, installedMatchesTarget = matches,
                phase = if (verified) FirmwarePhase.VERIFIED else s.phase,
                loaderMayBeActive = if (verified) false else s.loaderMayBeActive,
                observingInterrupted = if (verified) false else s.observingInterrupted,
                problem = when {
                    verified -> null
                    s.phase == FirmwarePhase.REPORTED_SUCCESS && matches == false -> FirmwareProblem.READBACK_MISMATCH
                    else -> s.problem
                })
        }
        return result
    }

    private suspend fun upload(endpoint: FirmwareFtpTarget, expected: FirmwarePackageInspection, openArchive: () -> Source) {
        val job = currentCoroutineContext().job
        val ftp = createFtp()
        try {
            lock.withLock { ensureRunning(); activeFtp = ftp }
            ftp.connect(endpoint.ip, endpoint.port); checkRunning()
            if (!ftp.login() || !ftp.binary()) fail(FirmwareProblem.FTP_FAILED)
            checkRunning()
            openArchive().use { source -> RobotMd5().use { digest ->
                var count = 0L
                val checked = object : RawSource {
                    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                        require(byteCount >= 0); if (byteCount == 0L) return 0
                        job.ensureActive(); lock.withLock { ensureRunning() }
                        val buffer = ByteArray(minOf(byteCount, 65_536L).toInt())
                        val n = source.readAtMostTo(buffer)
                        if (n == -1) return -1
                        count += n
                        if (count > expected.archiveSize) fail(FirmwareProblem.PACKAGE_CHANGED)
                        digest.update(buffer, n); sink.write(buffer, 0, n)
                        lock.withLock { ensureRunning(); mutable.value = state.value.copy(uploadedBytes = count) }
                        return n.toLong()
                    }
                    override fun close() = Unit // The surrounding use owns source and digest.
                }.buffered()
                checked.use {
                    if (!ftp.storeFile(endpoint.path, it)) fail(FirmwareProblem.FTP_FAILED)
                    if (count != expected.archiveSize || ftp.transferredBytes != expected.archiveSize ||
                        !source.exhausted() || digest.digest().hex() != expected.archiveMd5)
                        fail(FirmwareProblem.PACKAGE_CHANGED)
                }
            } }
        } finally {
            runCatching { ftp.close() }
            lock.withLock { if (activeFtp === ftp) activeFtp = null }
        }
    }

    override fun interruptTransfer() {
        val client = lock.withLock {
            if (!state.value.canInterruptTransfer) return@withLock null
            stopRequested = true
            activeFtp
        }
        runCatching { client?.close() }
    }

    override suspend fun observeInterrupted(previous: RobotFirmwareState) = task {
        lock.withLock {
            ensureOpen()
            val current = state.value
            check(previous.requiresRecovery && previous.connectionContext != 0L && previous.connectionContext != context &&
                current.expectedPackage == null && !current.observingInterrupted)
            stopRequested = false; finishDispatched = true; configurationAt = null
            if (current.loaderMayBeActive) {
                mutable.value = current.copy(expectedPackage = previous.expectedPackage, observingInterrupted = true,
                    configuration = null, configurationAgeSeconds = null, installedMatchesTarget = null, restartConfirmedByOperator = false)
            } else {
                roundProgressSeen = false; reportAt = null
                mutable.value = previous.copy(phase = FirmwarePhase.UNCERTAIN, busy = true, configuration = null, configurationAgeSeconds = null,
                    report = null, reportAgeSeconds = null, transferAcceptedCode = null, rebootTypeRaw = null, installedMatchesTarget = null,
                    observingInterrupted = true, restartConfirmedByOperator = false, problem = FirmwareProblem.REQUEST_UNKNOWN, connectionContext = context)
            }
        }
    }

    override fun confirmRestartedAndChecked() = lock.withLock {
        ensureOpen()
        val s = state.value
        check(s.observingInterrupted && !s.busy && s.loaderMayBeActive && s.configuration != null &&
            configurationAt?.let { nowMillis() - it <= 5_000 } == true && s.report?.kind != FirmwareReportKind.PROGRESS &&
            inventory().modules != null) { "请重连原机器人、读取当前配置，并核对已重启且自检完成" }
        mutable.value = s.copy(phase = FirmwarePhase.RECOVERED, loaderMayBeActive = false, problem = null, restartConfirmedByOperator = true)
    }

    fun receive(frame: DussFrame) = lock.withLock {
        if (closed) return@withLock
        firmwareRebootReport(frame)?.let { type ->
            if (state.value.loaderMayBeActive) {
                reportAt = nowMillis()
                mutable.value = state.value.copy(rebootTypeRaw = type, phase = FirmwarePhase.WAITING_REBOOT, reportAgeSeconds = 0)
            }
        }
        val report = firmwareUpgradeReport(frame) ?: return@withLock
        val s = state.value
        if (!s.loaderMayBeActive && report.kind != FirmwareReportKind.PROGRESS) return@withLock
        reportAt = nowMillis()
        val active = s.loaderMayBeActive || report.kind == FirmwareReportKind.PROGRESS
        var phase = s.phase; var problem = s.problem
        val preparing = s.busy && s.phase in setOf(FirmwarePhase.PREPARING, FirmwarePhase.ENTERING_LOADER, FirmwarePhase.NEGOTIATING, FirmwarePhase.TRANSFERRING)
        if (report.kind == FirmwareReportKind.PROGRESS && (!preparing || finishDispatched)) {
            roundProgressSeen = true; phase = FirmwarePhase.INSTALLING; problem = null
        } else if (finishDispatched || !s.busy) {
            when (report.kind) {
                FirmwareReportKind.END -> if (roundProgressSeen) {
                    phase = if (report.valueRaw == 1) FirmwarePhase.REPORTED_SUCCESS else FirmwarePhase.FAILED
                    problem = if (report.valueRaw == 1) null else FirmwareProblem.DEVICE_FAILED
                    runCatching { protocol.stopReports(send) }
                }
                FirmwareReportKind.REBOOT -> phase = FirmwarePhase.WAITING_REBOOT
                FirmwareReportKind.UNKNOWN -> { phase = FirmwarePhase.UNCERTAIN; problem = FirmwareProblem.REPORT_UNKNOWN }
                else -> Unit
            }
        }
        mutable.value = s.copy(report = report, reportAgeSeconds = 0, loaderMayBeActive = active, phase = phase, problem = problem,
            restartConfirmedByOperator = if (active) false else s.restartConfirmedByOperator)
    }

    fun tick() = lock.withLock {
        if (closed) return@withLock
        val s = state.value; val now = nowMillis()
        val stale = !s.busy && s.phase == FirmwarePhase.INSTALLING && reportAt?.let { now - it >= 30_000 } == true
        mutable.value = s.copy(configurationAgeSeconds = configurationAt?.let { ((now - it) / 1000).coerceAtLeast(0) },
            reportAgeSeconds = reportAt?.let { ((now - it) / 1000).coerceAtLeast(0) },
            phase = if (stale) FirmwarePhase.UNCERTAIN else s.phase, problem = if (stale) FirmwareProblem.REPORT_STALE else s.problem)
    }

    fun close() {
        val client = lock.withLock {
            if (closed) return@withLock null
            closed = true; stopRequested = true; protocol.close()
            mutable.value = state.value.copy(phase = FirmwarePhase.CLOSED, busy = false, configuration = null, configurationAgeSeconds = null,
                problem = if (state.value.loaderMayBeActive) FirmwareProblem.REQUEST_UNKNOWN else state.value.problem)
            activeFtp.also { activeFtp = null }
        }
        // Never close a platform socket while holding the shared session lock.
        runCatching { client?.close() }
    }
    private suspend fun task(action: suspend () -> Unit) {
        check(access.tryLock()) { "请等待固件任务结束" }
        try {
            lock.withLock { ensureOpen(); stopRequested = false; mutable.value = state.value.copy(busy = true) }
            action()
        } finally {
            lock.withLock { if (!closed) mutable.value = state.value.copy(busy = false) }
            access.unlock()
        }
    }
    private suspend fun checkRunning() { currentCoroutineContext().ensureActive(); lock.withLock { ensureRunning() } }
    private fun ensureOpen() { if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED) }
    private fun ensureRunning() { ensureOpen(); if (stopRequested) throw CancellationException("Firmware transfer interrupted") }
    companion object { private val nextContext = AtomicLong(0) }
}
private fun fail(problem: FirmwareProblem): Nothing = throw FirmwareOperationException(problem)
