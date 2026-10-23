package cn.elonzh.hanppie.ui.robot.maintenance

import cn.elonzh.hanppie.robot.maintenance.firmware.*
import cn.elonzh.hanppie.robot.session.RobotTarget
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

internal class FirmwareServiceFixture : RobotFirmwareService {
    override val state = MutableStateFlow(RobotFirmwareState())
    val calls = mutableListOf<String>()
    var configuration = FirmwareManifest.parse(firmwareUiConfiguration())
    var onInstall: suspend () -> Unit = {}
    var onRefresh: suspend () -> Unit = {}
    var onInterrupt: () -> Unit = {}
    override suspend fun refreshConfiguration() {
        calls += "refresh"; onRefresh()
        state.value = state.value.copy(configuration = configuration, configurationAgeSeconds = 0)
    }
    override suspend fun install(expected: FirmwarePackageInspection, configuration: FirmwareManifest, consent: FirmwareInstallConsent, openArchive: () -> Source) {
        calls += "install"
        state.value = state.value.copy(phase = FirmwarePhase.ENTERING_LOADER, busy = true, loaderMayBeActive = true, expectedPackage = expected)
        try { onInstall(); state.value = state.value.copy(phase = FirmwarePhase.INSTALLING) }
        catch (error: Exception) {
            if (state.value.phase !in setOf(FirmwarePhase.REPORTED_SUCCESS, FirmwarePhase.VERIFIED))
                state.value = state.value.copy(phase = FirmwarePhase.UNCERTAIN, problem = FirmwareProblem.TRANSFER_INTERRUPTED)
            throw error
        }
        finally { state.value = state.value.copy(busy = false) }
    }
    override fun interruptTransfer() { calls += "interrupt"; onInterrupt() }
    override suspend fun observeInterrupted(previous: RobotFirmwareState) {
        calls += "observe"
        state.value = previous.copy(phase = FirmwarePhase.UNCERTAIN, busy = false, observingInterrupted = true,
            configuration = null, configurationAgeSeconds = null, report = null, reportAgeSeconds = null,
            transferAcceptedCode = null, rebootTypeRaw = null, installedMatchesTarget = null, problem = FirmwareProblem.REQUEST_UNKNOWN)
    }
    override fun confirmRestartedAndChecked() {
        check(state.value.observingInterrupted && state.value.configuration != null && state.value.report?.kind != FirmwareReportKind.PROGRESS)
        calls += "confirm-restart"
        state.value = state.value.copy(phase = FirmwarePhase.RECOVERED, loaderMayBeActive = false, restartConfirmedByOperator = true)
    }
}

class RobotFirmwareControllerTest {
    @Test fun deviceSuccessReceivedBeforeALostAckKeepsTheStrongerResult(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val c = controller(scope)
        val service = FirmwareServiceFixture(); val bytes = firmwareUiArchive()
        service.onInstall = {
            service.state.value = service.state.value.copy(phase = FirmwarePhase.REPORTED_SUCCESS)
            throw RobotRequestException(RequestProblem.TIMED_OUT)
        }
        try {
            c.attach(service, target()); c.refresh()
            c.selectPackage("local.tar", { bytes.size.toLong() }) { Buffer().apply { write(bytes) } }; ready(c)
            c.install(FirmwareInstallConsent(), service.configuration, c.state.value.file!!.token)
            assertEquals(FirmwarePhase.REPORTED_SUCCESS, c.state.value.task.phase)
            assertNull(c.state.value.error); assertTrue(c.state.value.blocksControl)
        } finally { c.clear(); scope.cancel() }
    }
    @Test fun inspectionPublishesOnlyValidatedMetadataAndReinspectionInvalidatesOldFileTokens(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = controller(scope); var bytes = firmwareUiArchive()
        try {
            controller.selectPackage("local.tar", { bytes.size.toLong() }) { Buffer().apply { write(bytes) } }
            ready(controller)
            val first = assertNotNull(controller.state.value.file)
            assertEquals("local.tar", controller.state.value.selectedName); assertEquals(1, first.inspection.manifest.modules.size)
            bytes = byteArrayOf(1); controller.reinspect()
            withTimeout(3_000) { while (controller.state.value.inspecting) delay(5) }
            assertNull(controller.state.value.file); assertEquals(FirmwarePackageProblem.INVALID_ARCHIVE, controller.state.value.fileProblem)
            assertTrue(controller.state.value.canReinspect)
            bytes = firmwareUiArchive(); controller.reinspect(); ready(controller)
            assertNotEquals(first.token, controller.state.value.file?.token)
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun replacingAFileWhileAnOldInspectionIsBlockedCannotPublishTheOldResult(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = controller(scope); val entered = CountDownLatch(1); val release = CountDownLatch(1); val bytes = firmwareUiArchive()
        try {
            controller.selectPackage("old.tar", { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); bytes.size.toLong() }) { Buffer().apply { write(bytes) } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            controller.selectPackage("new.tar", { bytes.size.toLong() }) { Buffer().apply { write(bytes) } }; ready(controller)
            release.countDown(); delay(30)
            assertEquals("new.tar", controller.state.value.file?.name); assertNull(controller.state.value.fileError)
        } finally { release.countDown(); controller.clear(); scope.cancel() }
    }

    @Test fun staleFileConfigurationAndConnectionConfirmationsNeverInstall(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val c = controller(scope)
        val first = FirmwareServiceFixture(); val next = FirmwareServiceFixture(); val bytes = firmwareUiArchive()
        try {
            c.attach(first, target()); c.refresh()
            c.selectPackage("first.tar", { bytes.size.toLong() }) { Buffer().apply { write(bytes) } }; ready(c)
            val token = c.state.value.file!!.token; val connection = c.state.value.connection; val config = c.state.value.task.configuration!!
            c.selectPackage("second.tar", { bytes.size.toLong() }) { Buffer().apply { write(bytes) } }; ready(c)
            c.install(FirmwareInstallConsent(), config, token, connection); assertFalse("install" in first.calls)
            first.state.value = first.state.value.copy(configuration = config.copy(version = "00.06.0520"))
            c.install(FirmwareInstallConsent(), config, c.state.value.file!!.token, connection); assertFalse("install" in first.calls)
            c.attach(next, target("127.0.0.2"))
            c.install(FirmwareInstallConsent(), config, token, connection)
            assertTrue(next.calls.isEmpty()); assertNull(c.state.value.error)
        } finally { c.clear(); scope.cancel() }
    }

    @Test fun originalTargetObservationAndReadbackAreExplicitAndNeverReplayAnInstall(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val c = controller(scope)
        val old = FirmwareServiceFixture(); val wrong = FirmwareServiceFixture(); val fresh = FirmwareServiceFixture()
        try {
            c.attach(old, target()); old.state.value = old.state.value.copy(phase = FirmwarePhase.INSTALLING, loaderMayBeActive = true)
            c.detach(); assertEquals(target(), c.state.value.recovery?.target)
            c.attach(wrong, target("127.0.0.2")); c.observePrevious(); assertTrue(wrong.calls.isEmpty())
            c.detach(); c.attach(fresh, target()); assertTrue(fresh.calls.isEmpty())
            c.observePrevious(); assertEquals(listOf("observe"), fresh.calls)
            c.confirmRestarted(); assertNotNull(c.state.value.recovery)
            c.refresh(); c.confirmRestarted()
            assertNull(c.state.value.recovery); assertEquals(FirmwarePhase.RECOVERED, c.state.value.task.phase)
            assertEquals(listOf("observe", "refresh", "refresh", "confirm-restart"), fresh.calls)
        } finally { c.clear(); scope.cancel() }
    }

    @Test fun interruptClosesTheLocalOperationBeforeTheExitCallbackAndKeepsNativeOwnership(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val c = controller(scope); val service = FirmwareServiceFixture()
        val bytes = firmwareUiArchive(); var joined = false; var exited = false
        service.onInstall = { try { awaitCancellation() } finally { joined = true } }
        try {
            c.attach(service, target()); c.refresh(); c.selectPackage("local.tar", { bytes.size.toLong() }) { Buffer().apply { write(bytes) } }; ready(c)
            c.install(FirmwareInstallConsent(), service.configuration, c.state.value.file!!.token)
            assertTrue(c.state.value.task.canInterruptTransfer)
            c.interruptTransfer(onStopped = { assertTrue(joined); exited = true })
            withTimeout(3_000) { while (c.state.value.interrupting) delay(5) }
            assertTrue(exited); assertEquals(listOf("refresh", "install", "interrupt"), service.calls)
            assertTrue(c.state.value.blocksControl); assertFalse(c.state.value.working)
        } finally { c.clear(); scope.cancel() }
    }

    @Test fun lateOperationFailureCannotChangeTheNewConnection(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val c = controller(scope)
        val gate = CompletableDeferred<Unit>(); val old = FirmwareServiceFixture().apply { onRefresh = { withContext(NonCancellable) { gate.await() }; error("late") } }
        try {
            c.attach(old, target()); c.refresh(); c.attach(FirmwareServiceFixture(), target("127.0.0.2"))
            val connection = c.state.value.connection; gate.complete(Unit); delay(20)
            assertEquals(connection, c.state.value.connection); assertNull(c.state.value.error); assertFalse(c.state.value.busy)
        } finally { gate.complete(Unit); c.clear(); scope.cancel() }
    }

    @Test fun aContradictoryNativeReportRestoresResolvedRecoveryWithoutReplayingCommands(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); var occupied = 0
        val c = RobotFirmwareController(scope, { _, action -> action() }, { occupied++ }); val old = FirmwareServiceFixture(); val fresh = FirmwareServiceFixture()
        try {
            c.attach(old, target()); old.state.value = old.state.value.copy(phase = FirmwarePhase.INSTALLING, loaderMayBeActive = true)
            c.detach(); c.attach(fresh, target()); c.observePrevious(); c.refresh(); c.confirmRestarted()
            assertNull(c.state.value.recovery)
            fresh.state.value = fresh.state.value.copy(phase = FirmwarePhase.INSTALLING, loaderMayBeActive = true, restartConfirmedByOperator = false,
                report = FirmwareUpgradeReport(3, 40, 0))
            assertEquals(target(), c.state.value.recovery?.target); assertTrue(c.state.value.blocksControl)
            assertEquals(2, occupied); assertFalse("install" in fresh.calls)
        } finally { c.clear(); scope.cancel() }
    }

    @Test fun aChangedFileDuringInstallationNeedsAnewInspection(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val c = controller(scope)
        val service = FirmwareServiceFixture().apply { onInstall = { throw FirmwareOperationException(FirmwareProblem.PACKAGE_CHANGED) } }; val bytes = firmwareUiArchive()
        try {
            c.attach(service, target()); c.refresh(); c.selectPackage("local.tar", { bytes.size.toLong() }) { Buffer().apply { write(bytes) } }; ready(c)
            c.install(FirmwareInstallConsent(), service.configuration, c.state.value.file!!.token)
            assertNull(c.state.value.file); assertNotNull(c.state.value.error); assertTrue(c.state.value.canReinspect)
            c.reinspect(); ready(c); assertNotNull(c.state.value.file)
        } finally { c.clear(); scope.cancel() }
    }
}

private fun controller(scope: CoroutineScope) = RobotFirmwareController(scope, { _, action -> action() }, {})
private fun target(ip: String = "127.0.0.1") = RobotTarget(ip, "12345678")
internal suspend fun ready(controller: RobotFirmwareController) { withTimeout(3_000) { while (controller.state.value.inspecting || controller.state.value.file == null) delay(5) } }
internal fun firmwareUiConfiguration() = ByteArray(519) + ("<dji><device id=\"xw607\"><firmware formal=\"00.06.0521\"><release version=\"00.06.0521\">" +
    "<module id=\"0306\" version=\"01.03.32.00\" type=\"hw02\" group=\"\" size=\"3\" md5=\"900150983cd24fb0d6963f7d28e17f72\">module.sig</module>" +
    "</release></firmware></device></dji>").encodeToByteArray()
internal fun firmwareUiArchive(): ByteArray = Buffer().apply {
    for ((name, data) in listOf("00.06.0521.cfg.sig" to firmwareUiConfiguration(), "module.sig" to "abc".encodeToByteArray())) {
        val h = ByteArray(512); name.encodeToByteArray().copyInto(h)
        (data.size.toString(8).padStart(11, '0') + '\u0000').encodeToByteArray().copyInto(h, 124)
        h.fill(32, 148, 156); h[156] = '0'.code.toByte(); "ustar\u000000".encodeToByteArray().copyInto(h, 257)
        (h.sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0') + "\u0000 ").encodeToByteArray().copyInto(h, 148)
        write(h); write(data); write(ByteArray((512 - data.size % 512) % 512))
    }
    write(ByteArray(1024))
}.readByteArray()
