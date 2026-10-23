package cn.elonzh.hanppie.robot.maintenance.firmware

import cn.elonzh.hanppie.robot.product.WorkingRobotDevice
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.*
import kotlinx.coroutines.*
import kotlinx.io.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FirmwareCommandsTest {
    @Test fun freshConfigurationAndArchivePrecedeLoaderAndFtpUsesTheNegotiatedLocation(): Unit = runBlocking {
        val r = rig(); r.install()
        assertEquals(listOf(0x4f, 7, 8, 0x0a), r.ids)
        assertEquals("127.0.0.1" to 2121, r.ftp.destination)
        assertEquals("/upgrade/package.tar", r.ftp.path); assertContentEquals(r.bundle.archive, r.ftp.bytes)
        assertEquals(r.bundle.archive.size.toLong(), r.commands.state.value.uploadedBytes)
        assertEquals(FirmwarePhase.INSTALLING, r.commands.state.value.phase)
        assertTrue(r.commands.state.value.blocksControl); assertFalse(r.commands.state.value.busy)
        assertEquals(0, r.commands.state.value.transferAcceptedCode)
    }

    @Test fun knownLoaderRejectionReleasesButLostAckKeepsUncertainty(): Unit = runBlocking {
        val rejected = rig().apply { beforeReply = { if (it == 7) throw RobotRequestException(RequestProblem.REJECTED, 5) } }
        assertFailsWith<RobotRequestException> { rejected.install() }
        assertFalse(rejected.commands.state.value.blocksControl); assertEquals(FirmwarePhase.FAILED, rejected.commands.state.value.phase)
        val lost = rig().apply { beforeReply = { if (it == 7) throw RobotRequestException(RequestProblem.TIMED_OUT) } }
        assertFailsWith<RobotRequestException> { lost.install() }
        assertTrue(lost.commands.state.value.blocksControl); assertEquals(FirmwarePhase.UNCERTAIN, lost.commands.state.value.phase)
        assertEquals(listOf(0x4f, 7), lost.ids)
    }

    @Test fun packageOrConfigurationChangesNeverEnterLoader(): Unit = runBlocking {
        val r = rig()
        assertEquals(FirmwareProblem.PACKAGE_CHANGED, assertFailsWith<FirmwareOperationException> {
            r.commands.install(r.bundle.inspection.copy(archiveMd5 = "0".repeat(32)), r.installed(), FirmwareInstallConsent(), r::source)
        }.problem)
        assertTrue(r.ids.isEmpty()); assertFalse(r.commands.state.value.blocksControl)
        val old = r.installed(); r.configuration = r.bundle.configuration
        assertEquals(FirmwareProblem.CONFIG_CHANGED, assertFailsWith<FirmwareOperationException> {
            r.commands.install(r.bundle.inspection, old, FirmwareInstallConsent(), r::source)
        }.problem)
        assertEquals(listOf(0x4f), r.ids); assertFalse(r.commands.state.value.blocksControl)
    }

    @Test fun compatibilityReadinessAndCurrentMissingMotorConsentAreCheckedAtDispatch(): Unit = runBlocking {
        val r = rig(); r.configuration = r.configuration.decodeToString().replace("xw607", "another").encodeToByteArray()
        assertEquals(FirmwareProblem.INCOMPATIBLE, assertFailsWith<FirmwareOperationException> { r.install() }.problem)
        assertEquals(listOf(0x4f), r.ids)
        val low = rig().apply { health = healthy(49) }
        assertEquals(FirmwareProblem.NOT_READY, assertFailsWith<FirmwareOperationException> { low.install() }.problem)
        val missing = rig().apply { health = healthy().copy(modules = healthy().modules!!.filter { it.deviceId != 0x0314 }) }
        assertEquals(FirmwareProblem.CONSENT_REQUIRED, assertFailsWith<FirmwareOperationException> { missing.install() }.problem)
        missing.install(FirmwareInstallConsent(setOf(0x0314)))
        assertEquals(FirmwarePhase.INSTALLING, missing.commands.state.value.phase)
    }

    @Test fun externalPowerRequiresExplicitConfirmationWithoutAssumingADeviceModel(): Unit = runBlocking {
        val r = rig().apply { health = RobotHealth(modules = (0x0314..0x0317).map { WorkingRobotDevice(it, emptyList()) }) }
        assertEquals(FirmwareProblem.CONSENT_REQUIRED, assertFailsWith<FirmwareOperationException> { r.install() }.problem)
        r.install(FirmwareInstallConsent(externalPowerConfirmed = true)); assertTrue(r.commands.state.value.blocksControl)
    }

    @Test fun aDifferentNegotiatedHostOrChangedUploadCannotBeFinalized(): Unit = runBlocking {
        val wrong = rig().apply { endpointIp = "127.0.0.2" }
        assertEquals(FirmwareProblem.ENDPOINT_MISMATCH, assertFailsWith<FirmwareOperationException> { wrong.install() }.problem)
        assertNull(wrong.ftp.destination); assertFalse(0x0a in wrong.ids); assertTrue(wrong.commands.state.value.requiresRecovery)
        val changed = rig(); var opened = 0
        assertEquals(FirmwareProblem.PACKAGE_CHANGED, assertFailsWith<FirmwareOperationException> {
            changed.commands.install(changed.bundle.inspection, changed.installed(), FirmwareInstallConsent()) {
                opened++; Buffer().apply { write(changed.bundle.archive.copyOf().apply { if (opened == 2) this[512] = 1 }) }
            }
        }.problem)
        assertFalse(0x0a in changed.ids); assertTrue(changed.commands.state.value.blocksControl)
    }

    @Test fun transferAcceptanceAndHundredPercentNeedAReportThenFreshVersionReadback(): Unit = runBlocking {
        val r = rig().apply { finishCode = 0xf7 }; r.install()
        assertEquals(0xf7, r.commands.state.value.transferAcceptedCode)
        r.commands.receive(upgrade(4, 1)); assertEquals(FirmwarePhase.INSTALLING, r.commands.state.value.phase)
        r.commands.receive(upgrade(3, 100)); assertEquals(FirmwarePhase.INSTALLING, r.commands.state.value.phase)
        r.commands.receive(upgrade(4, 1)); assertEquals(FirmwarePhase.REPORTED_SUCCESS, r.commands.state.value.phase)
        assertEquals(1, r.stops); assertTrue(r.commands.state.value.blocksControl)
        r.commands.refreshConfiguration()
        assertEquals(FirmwareProblem.READBACK_MISMATCH, r.commands.state.value.problem); assertTrue(r.commands.state.value.blocksControl)
        r.configuration = r.bundle.configuration; r.commands.refreshConfiguration()
        assertEquals(FirmwarePhase.VERIFIED, r.commands.state.value.phase); assertFalse(r.commands.state.value.blocksControl)
        assertTrue(r.commands.state.value.installedMatchesTarget == true)
    }

    @Test fun reportsBeforeTheTransferAckSurviveEvenWhenTheAckIsLost(): Unit = runBlocking {
        val r = rig().apply { beforeReply = { if (it == 0x0a) {
            commands.receive(upgrade(3, 75)); commands.receive(upgrade(4, 1)); throw RobotRequestException(RequestProblem.TIMED_OUT)
        } } }
        assertFailsWith<RobotRequestException> { r.install() }
        assertEquals(FirmwarePhase.REPORTED_SUCCESS, r.commands.state.value.phase); assertNull(r.commands.state.value.problem)
        assertNull(r.commands.state.value.transferAcceptedCode)
        r.configuration = r.bundle.configuration; r.commands.refreshConfiguration(); assertFalse(r.commands.state.value.blocksControl)
    }

    @Test fun nativeFailureAndStaleReportsKeepOwnershipAndNeverRetryAutomatically(): Unit = runBlocking {
        val r = rig(); r.install(); r.commands.receive(upgrade(3, 60)); r.commands.receive(upgrade(4, 0, 7))
        assertEquals(FirmwarePhase.FAILED, r.commands.state.value.phase)
        assertEquals(FirmwareProblem.DEVICE_FAILED, r.commands.state.value.problem); assertTrue(r.commands.state.value.blocksControl)
        val count = r.ids.size; r.now += 60_000; r.commands.tick(); assertEquals(count, r.ids.size)
        r.install(); assertEquals(2, r.ids.count { it == 7 })
        r.now += 30_000; r.commands.tick()
        assertEquals(FirmwarePhase.UNCERTAIN, r.commands.state.value.phase); assertEquals(FirmwareProblem.REPORT_STALE, r.commands.state.value.problem)
        assertTrue(r.commands.state.value.blocksControl)
    }

    @Test fun interruptionClosesTheTransferAndDoesNotSendFinishOrClaimDeviceCancellation(): Unit = runBlocking {
        val r = rig(); val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        r.ftp.onChunk = { entered.complete(Unit); check(release.await(3, TimeUnit.SECONDS)) }
        r.ftp.onClose = { release.countDown() }
        val work = launch { runCatching { r.install() } }
        withTimeout(3_000) { entered.await() }; r.commands.interruptTransfer(); work.join()
        assertTrue(r.ftp.closed); assertFalse(0x0a in r.ids)
        assertEquals(FirmwareProblem.TRANSFER_INTERRUPTED, r.commands.state.value.problem)
        assertTrue(r.commands.state.value.requiresRecovery); assertFalse(r.commands.state.value.busy)
        assertEquals(0, r.stops, "Stopping local FTP is not the native report-stop or a flash cancel")
    }

    @Test fun closingWhileTheTransferIsActiveRetiresTheSocketAndRejectsLateResults(): Unit = runBlocking {
        val r = rig(); val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        r.ftp.onChunk = { entered.complete(Unit); check(release.await(3, TimeUnit.SECONDS)) }; r.ftp.onClose = { release.countDown() }
        val work = launch { runCatching { r.install() } }
        withTimeout(3_000) { entered.await() }; r.commands.close(); work.join()
        assertEquals(FirmwarePhase.CLOSED, r.commands.state.value.phase); assertTrue(r.commands.state.value.requiresRecovery)
        assertFalse(r.commands.state.value.blocksControl); assertTrue(r.ftp.closed); assertFalse(0x0a in r.ids)
        r.commands.receive(upgrade(3, 50)); assertEquals(FirmwarePhase.CLOSED, r.commands.state.value.phase)
        assertFailsWith<RobotRequestException> { r.commands.refreshConfiguration() }
    }

    @Test fun closingAtEachRequestBoundaryCannotBeUndoneByALateAck(): Unit = runBlocking {
        for (stage in listOf(0x4f, 7, 8, 0x0a)) {
            val r = rig(); val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            r.beforeReply = { if (it == stage) { entered.complete(Unit); gate.await() } }
            val work = launch { runCatching { r.install() } }
            withTimeout(3_000) { entered.await() }; r.commands.close()
            val closed = r.commands.state.value; gate.complete(Unit); work.join()
            assertEquals(closed, r.commands.state.value)
            assertEquals(FirmwarePhase.CLOSED, r.commands.state.value.phase)
            assertEquals(stage != 0x4f, r.commands.state.value.requiresRecovery)
            assertEquals(stage, r.ids.last())
        }
    }

    @Test fun reconnectObservationNeverReplaysAndOperatorRecoveryRequiresFreshReadback(): Unit = runBlocking {
        val old = rig(); old.install(); val previous = old.commands.state.value
        assertFailsWith<IllegalStateException> { old.commands.observeInterrupted(previous) }
        old.commands.close()
        val fresh = rig(); fresh.commands.observeInterrupted(previous)
        assertTrue(fresh.ids.isEmpty()); assertNull(fresh.commands.state.value.transferAcceptedCode)
        assertFailsWith<IllegalStateException> { fresh.commands.confirmRestartedAndChecked() }
        fresh.commands.refreshConfiguration(); fresh.now += 5_001
        assertFailsWith<IllegalStateException> { fresh.commands.confirmRestartedAndChecked() }
        fresh.commands.refreshConfiguration(); fresh.commands.confirmRestartedAndChecked()
        assertEquals(FirmwarePhase.RECOVERED, fresh.commands.state.value.phase)
        assertTrue(fresh.commands.state.value.restartConfirmedByOperator); assertFalse(fresh.commands.state.value.blocksControl)
        assertFalse(fresh.commands.state.value.installedMatchesTarget == true)
        fresh.commands.receive(upgrade(3, 20))
        assertTrue(fresh.commands.state.value.blocksControl); assertFalse(fresh.commands.state.value.restartConfirmedByOperator)
    }

    @Test fun reconnectPreservesAlreadyObservedNativeProgressAndRejectsManualReleaseDuringIt(): Unit = runBlocking {
        val old = rig(); old.install(); val fresh = rig()
        fresh.commands.receive(upgrade(3, 55)); fresh.commands.observeInterrupted(old.commands.state.value)
        assertEquals(55, fresh.commands.state.value.report?.progress)
        fresh.commands.refreshConfiguration()
        assertFailsWith<IllegalStateException> { fresh.commands.confirmRestartedAndChecked() }
        fresh.configuration = fresh.bundle.configuration
        fresh.commands.receive(upgrade(4, 1)); fresh.commands.refreshConfiguration()
        assertEquals(FirmwarePhase.VERIFIED, fresh.commands.state.value.phase)
        assertTrue(fresh.ids.all { it == 0x4f })
    }

    @Test fun concurrentCallsAreRejectedInsteadOfQueuedAndPreparationCanBeInterrupted(): Unit = runBlocking {
        val r = rig(); val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        r.beforeReply = { if (it == 0x4f) { entered.complete(Unit); gate.await() } }
        val work = launch { runCatching { r.install() } }; entered.await()
        assertFailsWith<IllegalStateException> { r.commands.refreshConfiguration() }
        r.commands.interruptTransfer(); gate.complete(Unit); work.join()
        assertFalse(7 in r.ids); assertFalse(r.commands.state.value.blocksControl)
        assertEquals(FirmwareProblem.TRANSFER_INTERRUPTED, r.commands.state.value.problem)
    }

    @Test fun foreignFramesCannotClaimOwnershipButUnsolicitedCurrentDeviceProgressCan(): Unit = runBlocking {
        val r = rig(); val f = upgrade(3, 70)
        for (wrong in listOf(f.copy(sender = 8), f.copy(receiver = 1), f.copy(attr = 0x80), f.copy(valid = false))) r.commands.receive(wrong)
        assertFalse(r.commands.state.value.blocksControl)
        r.commands.receive(f); assertTrue(r.commands.state.value.blocksControl); assertEquals(70, r.commands.state.value.report?.progress)
        assertFailsWith<IllegalStateException> { r.install() }; assertTrue(r.ids.isEmpty())
    }
}

private class Rig(val bundle: FirmwarePackageFixture) {
    var now = 0L
    var configuration = bundle.configuration.decodeToString().replace("00.06.0521", "00.06.0520").encodeToByteArray()
    var health = healthy()
    var endpointIp = "127.0.0.1"
    var finishCode = 0
    var beforeReply: suspend (Int) -> Unit = {}
    val ids = CopyOnWriteArrayList<Int>()
    val ftp = FirmwareFtpFixture()
    var stops = 0
    val commands = FirmwareCommands(RobotLock(), "127.0.0.1", { health }, { _, _, id, _ ->
        ids += id; beforeReply(id)
        when (id) {
            0x4f -> byteArrayOf(0) + le32(configuration.size) + ByteArray(4) + configuration
            8 -> ByteArray(71).apply {
                endpointIp.split('.').map(String::toInt).reversed().forEachIndexed { i, part -> this[i + 1] = part.toByte() }
                this[5] = 0x49; this[6] = 8; "/upgrade/package.tar".encodeToByteArray().copyInto(this, 7)
            }
            0x0a -> byteArrayOf(finishCode.toByte())
            else -> byteArrayOf(0)
        }
    }, { ftp }, { node, set, id, p -> assertEquals(0x28, node); assertEquals(0, set); assertEquals(0x41, id); assertTrue(p.isEmpty()); stops++ }, { now })
    fun source(): Source = Buffer().apply { write(bundle.archive) }
    fun installed() = FirmwareManifest.parse(configuration)
    suspend fun install(consent: FirmwareInstallConsent = FirmwareInstallConsent()) = commands.install(bundle.inspection, installed(), consent, ::source)
}
private suspend fun rig() = Rig(firmwarePackageFixture())
private fun healthy(percent: Int = 80) = RobotHealth(modules = (0x0314..0x0317).map { WorkingRobotDevice(it, emptyList()) } + WorkingRobotDevice(0x030a, emptyList()), battery = BatteryTelemetry(0, 0, 0, percent))
private fun upgrade(type: Int, value: Int, detail: Int = 0) = DussFrame(0, 0x28, 2, 71, 0, 0, 0x42, byteArrayOf(type.toByte(), value.toByte(), detail.toByte()), true)
private fun le32(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }

internal class FirmwareFtpFixture : RobotFtpClient {
    override val replyCode = 226
    override val replyText = "test"
    override var transferredBytes = 0L
    var destination: Pair<String, Int>? = null
    var path: String? = null
    var bytes = byteArrayOf()
    var closed = false
    var onChunk: () -> Unit = {}
    var onClose: () -> Unit = {}
    override fun connect(ip: String, port: Int) { destination = ip to port; closed = false }
    override fun login() = true
    override fun binary() = true
    override fun storeFile(path: String, source: Source): Boolean {
        this.path = path; val output = Buffer(); val buffer = ByteArray(8192); transferredBytes = 0
        while (true) {
            val count = source.readAtMostTo(buffer); if (count == -1) break
            output.write(buffer, 0, count); transferredBytes += count; onChunk(); if (closed) return false
        }
        bytes = output.readByteArray(); return true
    }
    override fun changeWorkingDirectory(path: String): Boolean = error("unused")
    override fun listFiles(path: String): List<RobotFtpEntry> = error("unused")
    override fun retrieveFile(path: String, destination: Sink): Boolean = error("unused")
    override fun makeDirectory(path: String): Boolean = error("unused")
    override fun rename(path: String, destination: String): Boolean = error("unused")
    override fun removeDirectory(path: String): Boolean = error("unused")
    override fun deleteFile(path: String): Boolean = error("unused")
    override fun close() { closed = true; onClose() }
}
