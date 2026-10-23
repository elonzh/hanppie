package cn.elonzh.hanppie.robot.maintenance

import cn.elonzh.hanppie.robot.product.WorkingRobotDevice
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import kotlinx.coroutines.*
import kotlin.test.*

private data class Write(val node: Int, val set: Int, val id: Int, val hex: String)
private class AddressingFixture {
    var now = 0L
    var health = RobotHealth(modules = emptyList())
    val requests = mutableListOf<Write>()
    val sends = mutableListOf<Write>()
    var reply: suspend (Write) -> ByteArray = { byteArrayOf(0) }
    val service = AddressingCommands(RobotLock(), { health }, { node, set, id, p ->
        val write = Write(node, set, id, p.hex()); requests += write; reply(write)
    }, { node, set, id, p -> sends += Write(node, set, id, p.hex()) }, { now })
    fun modules(vararg ids: Int) {
        health = health.copy(modules = ids.map { WorkingRobotDevice(it, emptyList()) }, moduleReportAgeSeconds = 0)
        val payload = byteArrayOf(ids.size.toByte()) + ids.flatMap { listOf(it.toByte(), (it ushr 8).toByte(), 0.toByte()) }.toByteArray()
        service.receive(DussFrame(0, 0x28, 2, 71, 0, 0x3f, 0x12, payload, true))
    }
    fun wheel(state: Int = 1, percent: Int = 100) =
        service.receive(DussFrame(0, 0xc3, 2, 72, 0, 0x3f, 0x2f, byteArrayOf(state.toByte(), percent.toByte()), true))
    fun armor(sequence: Int, raw: Int = 1) =
        service.receive(DussFrame(0, 0x18, 2, sequence, 0, 0x3f, 0x63, byteArrayOf(raw.toByte()), true))
}

class AddressingCommandsTest {
    @Test fun progressBeforeStartAckIsRetainedAndNeverStartsWheelsAutomatically(): Unit = runBlocking {
        val f = AddressingFixture(); val gate = CompletableDeferred<Unit>()
        f.reply = { write -> if (write.id == 0x3e && write.hex == "01") { f.wheel(); gate.await() }; byteArrayOf(0) }
        val begin = launch(start = CoroutineStart.UNDISPATCHED) { f.service.begin(ModuleAddressingKind.WHEELS) }
        assertEquals(AddressingPhase.STARTING, f.service.state.value.phase)
        assertTrue(f.service.state.value.wheelReportedComplete); assertTrue(f.service.state.value.blocksControl)
        assertFailsWith<IllegalStateException> { f.service.end() }
        gate.complete(Unit); begin.join()
        assertEquals(AddressingPhase.ADDRESSING, f.service.state.value.phase)
        assertTrue(f.sends.isEmpty())
        f.service.prepareVerification()
        assertEquals(listOf(Write(0xc3, 0x3f, 0x3e, "02"), Write(3, 0x3f, 0x19, "01"), Write(3, 0x3f, 0x20, "0000000000000000")), f.requests.takeLast(3))
        assertTrue(f.sends.isEmpty()); assertTrue(f.service.state.value.wheelTestReady)
    }

    @Test fun wheelLeaseHasAnIndependentHardLimitAndFreshnessLossRequiresAReleasedPress(): Unit = runBlocking {
        val f = AddressingFixture(); f.service.begin(ModuleAddressingKind.WHEELS); f.wheel(); f.service.prepareVerification()
        f.service.holdWheel(AddressingWheel.FRONT_RIGHT); f.service.tick(true)
        assertEquals("c800000000000000", f.sends.single().hex)
        assertFailsWith<IllegalStateException> { f.service.confirmWheel(AddressingWheel.FRONT_RIGHT) }
        for (time in listOf(200L, 400L, 600L, 800L)) { f.now = time; f.service.renewWheel(); f.service.tick(true) }
        f.now = 1_000; f.service.renewWheel(); f.service.tick(true)
        assertFalse(f.service.state.value.wheelDriving)
        f.service.holdWheel(AddressingWheel.FRONT_LEFT); f.now = 1_100; f.service.renewWheel(); f.service.tick(true)
        assertEquals(1, f.sends.count { it.hex != "0000000000000000" })
        f.service.holdWheel(null); f.service.confirmWheel(AddressingWheel.FRONT_RIGHT)
        assertEquals(setOf(AddressingWheel.FRONT_RIGHT), f.service.state.value.verifiedWheels)
        f.service.holdWheel(AddressingWheel.REAR_LEFT); f.service.tick(true)
        assertEquals("00000000c8000000", f.sends.last().hex)
        f.service.tick(false); f.service.renewWheel(); f.service.tick(true)
        assertEquals(2, f.sends.count { it.hex != "0000000000000000" })
        assertFalse(f.service.state.value.wheelDriving)
        f.service.end(); assertEquals(AddressingPhase.FINISHED, f.service.state.value.phase)
        assertFalse(f.service.state.value.blocksControl)
        val sent = f.sends.size; f.now += 5_000; f.service.tick(true)
        assertEquals(sent, f.sends.size, "No late zero may interfere with a new remote owner after termination ACKs")
        assertFailsWith<IllegalStateException> { f.service.holdWheel(AddressingWheel.FRONT_RIGHT) }
    }

    @Test fun aMissedLeaseCannotBeRenewedIntoAnotherMovementAndCloseUsesNativeStopNode(): Unit = runBlocking {
        val f = AddressingFixture(); f.service.begin(ModuleAddressingKind.WHEELS); f.wheel(); f.service.prepareVerification()
        f.service.holdWheel(AddressingWheel.REAR_RIGHT); f.service.tick(true)
        f.now = 250; f.service.renewWheel(); f.service.tick(true)
        assertFalse(f.service.state.value.wheelDriving)
        f.service.renewWheel(); f.service.tick(true)
        assertEquals(1, f.sends.count { it.hex != "0000000000000000" })
        f.service.close()
        assertEquals(listOf(Write(0xc3, 0x3f, 0x3e, "02"), Write(3, 0x3f, 0x20, "0000000000000000"), Write(3, 0x3f, 0x19, "00")), f.sends.takeLast(3))
        assertEquals(AddressingPhase.CLOSED, f.service.state.value.phase)
        val sent = f.sends.size; f.service.tick(true); f.service.renewWheel(); f.service.close(); assertEquals(sent, f.sends.size)
        assertFailsWith<RobotRequestException> { f.service.begin(ModuleAddressingKind.WHEELS) }
    }

    @Test fun rejectedEndStillAttemptsZeroAndModeReleaseButDoesNotReleaseOwnership(): Unit = runBlocking {
        val f = AddressingFixture(); f.service.begin(ModuleAddressingKind.WHEELS)
        f.reply = { if (it.id == 0x3e) throw RobotRequestException(RequestProblem.REJECTED); byteArrayOf(0) }
        val count = f.requests.size
        assertFailsWith<RobotRequestException> { f.service.end() }
        assertEquals(3, f.requests.size - count)
        assertEquals(Write(3, 0x3f, 0x19, "00"), f.requests.last())
        assertEquals(AddressingPhase.UNCERTAIN, f.service.state.value.phase); assertTrue(f.service.state.value.blocksControl)
        f.reply = { byteArrayOf(0) }; f.service.end()
        assertFalse(f.service.state.value.blocksControl)
    }

    @Test fun servoDiscoveryRequiresAQuietReportAndProtectsArmAndAlreadyAssignedNumbers(): Unit = runBlocking {
        val f = AddressingFixture(); f.modules(0x031e, 0x0318, 0x0319)
        f.reply = { if (it.set == 0x33) "000102".hexBytes() else byteArrayOf(0) }
        f.service.begin(ModuleAddressingKind.SERVO)
        assertEquals(setOf(1, 2), f.service.state.value.reservedIds)
        f.modules(0x031e, 0x031b); assertNull(f.service.state.value.candidateId)
        f.modules(0x031e); assertFalse(f.service.state.value.waitingForQuiet)
        f.modules(0x031e, 0x031b)
        assertEquals(4, f.service.state.value.candidateId)
        assertEquals(setOf(3), f.service.state.value.selectableIds)
        val before = f.requests.size
        assertFailsWith<IllegalStateException> { f.service.assign(1, 4) }; assertEquals(before, f.requests.size)
        f.service.assign(3, 4)
        assertEquals(setOf(3), f.service.state.value.assignedIds)
        assertTrue(f.service.state.value.waitingForQuiet)
        assertEquals(listOf(Write(0xc3, 0x3f, 0x66, "0103"), Write(0xc3, 0x3f, 0x66, "0000")), f.requests.takeLast(2))
        f.modules(0x031e); f.modules(0x031e, 0x031b)
        assertTrue(f.service.state.value.selectableIds.isEmpty())
        f.service.prepareVerification(); f.modules(0x031e, 0x0318, 0x0319, 0x031a)
        f.service.identify(3)
        assertEquals(Write(0x79, 0, 0x60, "ff0200ff00"), f.requests.last())
        assertFailsWith<IllegalStateException> { f.service.confirmModule(3) }
        f.now = 2_999; f.service.tick(true); assertTrue(f.sends.isEmpty())
        f.now = 3_000; f.service.tick(true)
        assertEquals(Write(0x79, 0, 0x60, "ff0100ff00"), f.sends.single())
        assertNull(f.service.state.value.identifyingId); assertTrue(f.service.state.value.blocksControl)
        f.service.confirmModule(3); assertEquals(setOf(3), f.service.state.value.verifiedIds)
        f.modules(0x031e, 0x0318, 0x0319); assertTrue(f.service.state.value.verifiedIds.isEmpty())
        f.modules(0x031e, 0x0318, 0x0319, 0x031a)
        assertFailsWith<IllegalStateException> { f.service.confirmModule(3) }
        f.service.end(); assertEquals(AddressingPhase.FINISHED, f.service.state.value.phase)
    }

    @Test fun unknownArmReservationsDoNotStartAndAnArmConnectionChangeRequiresEnd(): Unit = runBlocking {
        val f = AddressingFixture(); f.modules(0x031e, 0x0318)
        f.reply = { "000100".hexBytes() }
        assertFailsWith<IllegalStateException> { f.service.begin(ModuleAddressingKind.SERVO) }
        assertEquals(AddressingProblem.ARM_RESERVATIONS, f.service.state.value.problem)
        assertFalse(f.service.state.value.blocksControl); assertEquals(listOf(0x68), f.requests.map { it.id })
        f.reply = { if (it.set == 0x33) "000102".hexBytes() else byteArrayOf(0) }
        f.service.begin(ModuleAddressingKind.SERVO); f.modules(0x031e); f.modules(0x031e, 0x031b)
        f.modules(0x031b)
        assertEquals(AddressingProblem.ARM_CHANGED, f.service.state.value.problem)
        assertFailsWith<IllegalStateException> { f.service.assign(3, 4) }
        f.service.end(); assertFalse(f.service.state.value.blocksControl)
    }

    @Test fun tofAssignmentMayReuseTheCandidatesOldNumberButMultipleLogicalModulesStopDiscovery(): Unit = runBlocking {
        val f = AddressingFixture(); f.modules(0x1201, 0x1202); f.service.begin(ModuleAddressingKind.TOF)
        f.modules(); f.modules(0x1202)
        assertEquals(setOf(2, 3, 4), f.service.state.value.selectableIds)
        f.service.assign(2, 2); f.modules(); f.modules(0x1203, 0x1204)
        assertEquals(AddressingProblem.MULTIPLE_MODULES, f.service.state.value.problem)
        assertNull(f.service.state.value.candidateId); assertTrue(f.service.state.value.blocksControl)
        f.service.end()
        // A new task must observe its own quiet report, even after the previous one did.
        f.service.begin(ModuleAddressingKind.TOF); f.modules(0x1204)
        assertNull(f.service.state.value.candidateId); assertTrue(f.service.state.value.waitingForQuiet)
    }

    @Test fun adaptersHaveNoRemoteAddressingCommandAndGuideClosureDoesNotClaimAnAck(): Unit = runBlocking {
        val f = AddressingFixture(); f.health = RobotHealth()
        assertFailsWith<IllegalStateException> { f.service.begin(ModuleAddressingKind.SENSOR_ADAPTER) }
        f.modules(0x1601, 0x1606); f.service.begin(ModuleAddressingKind.SENSOR_ADAPTER)
        assertEquals(setOf(1, 6), f.service.state.value.onlineIds)
        assertEquals(AddressingPhase.VERIFYING, f.service.state.value.phase); assertTrue(f.requests.isEmpty())
        f.service.confirmModule(6); assertEquals(setOf(6), f.service.state.value.verifiedIds)
        f.service.end(); assertTrue(f.requests.isEmpty()); assertFalse(f.service.state.value.nativeEndAcknowledged)
    }

    @Test fun armorDeduplicatesReportsBeforeAndAfterAckAndRecordsSoftwareSkipsSeparately(): Unit = runBlocking {
        val f = AddressingFixture()
        f.reply = { if (it.hex == "00") f.armor(1); byteArrayOf(0) }
        f.service.begin(ModuleAddressingKind.ARMOR); assertEquals(1, f.service.state.value.armorSteps)
        f.armor(1); f.armor(2, 0); assertEquals(1, f.service.state.value.armorSteps)
        f.armor(3); f.service.skipArmor(2)
        assertEquals(3, f.service.state.value.armorSteps); assertEquals(setOf(3), f.service.state.value.skippedArmorSteps)
        f.armor(4); f.armor(5); assertEquals(4, f.service.state.value.armorSteps)
        assertEquals(AddressingPhase.VERIFYING, f.service.state.value.phase)
        f.service.end(); assertEquals(Write(0x18, 0x3f, 0x62, "ff"), f.requests.last())
    }

    @Test fun aHitDuringSkipIsAmbiguousInsteadOfAdvancingTwoSteps(): Unit = runBlocking {
        val f = AddressingFixture(); f.service.begin(ModuleAddressingKind.ARMOR)
        f.reply = { f.armor(8); byteArrayOf(0) }
        assertFailsWith<IllegalStateException> { f.service.skipArmor(0) }
        assertEquals(AddressingProblem.SKIP_RACED, f.service.state.value.problem)
        assertEquals(0, f.service.state.value.armorSteps); assertTrue(f.service.state.value.blocksControl)
        f.service.end()
    }

    @Test fun interruptedStartRetainsUnknownStateAndRecoveryOnlySendsTermination(): Unit = runBlocking {
        val f = AddressingFixture(); val gate = CompletableDeferred<Unit>()
        f.reply = { gate.await(); byteArrayOf(0) }
        val begin = launch(start = CoroutineStart.UNDISPATCHED) { f.service.begin(ModuleAddressingKind.TOF) }
        begin.cancelAndJoin(); gate.complete(Unit)
        assertEquals(AddressingPhase.UNCERTAIN, f.service.state.value.phase)
        assertTrue(f.service.state.value.blocksControl)
        f.reply = { byteArrayOf(0) }; f.service.end()
        val count = f.requests.size; f.service.recover(ModuleAddressingKind.WHEELS)
        assertEquals(listOf(Write(0xc3, 0x3f, 0x3e, "02"), Write(3, 0x3f, 0x20, "0000000000000000"), Write(3, 0x3f, 0x19, "00")), f.requests.drop(count))
    }

    @Test fun lateIdentifyAckCannotReopenAClosedTaskAndCleanupRestoresTheSameLeafOnly(): Unit = runBlocking {
        val f = AddressingFixture(); f.service.begin(ModuleAddressingKind.TOF); f.service.prepareVerification(); f.modules(0x1204)
        val gate = CompletableDeferred<Unit>(); f.reply = { gate.await(); byteArrayOf(0) }
        val identify = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.service.identify(4) } }
        f.service.close(); gate.complete(Unit)
        assertTrue(identify.await().isFailure); assertEquals(AddressingPhase.CLOSED, f.service.state.value.phase)
        assertEquals(listOf(Write(0x92, 0, 0x60, "ff0100ff00"), Write(9, 0x3f, 0x66, "0200")), f.sends)
        f.now = 8_000; f.service.tick(true); assertEquals(2, f.sends.size)
    }
    @Test fun invalidWheelProgressKeepsRawDataButCannotEnableVerificationOrAutomaticEnd(): Unit = runBlocking {
        val f = AddressingFixture(); f.service.begin(ModuleAddressingKind.WHEELS)
        f.wheel(255, 255)
        assertEquals(AddressingProblem.WHEEL_REPORT, f.service.state.value.problem)
        assertNull(f.service.state.value.wheelPercent); assertEquals(255, f.service.state.value.wheelStateCode)
        assertFailsWith<IllegalStateException> { f.service.prepareVerification() }
        assertFailsWith<IllegalStateException> { f.service.holdWheel(AddressingWheel.FRONT_RIGHT) }
        assertEquals(2, f.requests.size); assertTrue(f.sends.isEmpty()); assertTrue(f.service.state.value.blocksControl)
        f.wheel(); assertEquals(255, f.service.state.value.wheelStateCode)
        f.service.end(); assertFalse(f.service.state.value.blocksControl)
    }
    @Test fun endingNumberingRequiresANewReportButRetainsOneThatArrivesBeforeTheEndAck(): Unit = runBlocking {
        val f = AddressingFixture(); f.modules(0x1201); f.service.begin(ModuleAddressingKind.TOF)
        f.service.prepareVerification()
        assertFalse(f.service.state.value.inventoryKnown); assertTrue(f.service.state.value.onlineIds.isEmpty())
        val count = f.requests.size
        assertFailsWith<IllegalStateException> { f.service.identify(1) }; assertEquals(count, f.requests.size)
        f.modules(0x1201); f.service.identify(1); f.service.end()
        f.service.begin(ModuleAddressingKind.TOF)
        f.reply = { write -> if (write.hex == "0200") f.modules(0x1202); byteArrayOf(0) }
        f.service.prepareVerification()
        assertTrue(f.service.state.value.inventoryKnown); assertEquals(setOf(2), f.service.state.value.onlineIds)
        f.service.identify(2)
        assertEquals(Write(0x52, 0, 0x60, "ff0200ff00"), f.requests.last())
    }

    @Test fun aChangedCandidateOrArmorStepRejectsTheOldConfirmationBeforeDispatch(): Unit = runBlocking {
        val f = AddressingFixture(); f.service.begin(ModuleAddressingKind.TOF)
        f.modules(); f.modules(0x1201); f.modules(0x1202)
        val count = f.requests.size
        assertFailsWith<IllegalStateException> { f.service.assign(3, 1) }
        assertEquals(count, f.requests.size)
        f.service.end(); f.service.begin(ModuleAddressingKind.ARMOR); f.armor(1)
        val armorCount = f.requests.size
        assertFailsWith<IllegalStateException> { f.service.skipArmor(0) }
        assertEquals(armorCount, f.requests.size); assertEquals(1, f.service.state.value.armorSteps)
    }

}
