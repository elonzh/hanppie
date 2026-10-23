package cn.elonzh.hanppie.robot.extensions.arm

import cn.elonzh.hanppie.robot.product.WorkingRobotDevice
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import kotlinx.coroutines.*
import kotlin.test.*

class ArmCommandsTest {
    @Test fun subscriptionsAreConfirmedTogetherAndOnlyLaterSamplesEnableInput(): Unit = runBlocking {
        val r = ArmRig()
        r.beforeReply = { p -> if (p.set == 0x48) r.samples() }
        r.commands.setObserving(true)
        assertEquals(ArmObservation.ACTIVE, r.state.observation); assertFalse(r.state.positionFresh)
        assertEquals(listOf("9/72/3/020f0000014dd6ab26090002000a00", "9/72/3/02100000016a154d12090002000500"), r.requests.map { it.text })
        r.commands.readBinding(); r.samples()
        assertTrue(r.state.positionFresh); assertTrue(r.state.clawFresh)
        val count = r.requests.size; r.commands.setObserving(true); assertEquals(count, r.requests.size)
        r.commands.setObserving(false)
        r.samples(); assertNull(r.state.position); assertFalse(r.state.positionFresh)
        assertEquals(ArmObservation.OFF, r.state.observation)
        assertEquals(4, r.sent.count { it.set == 0x48 && it.id == 4 })
    }
    @Test fun partialSubscriptionFailureRemovesBothTopicsAndCannotLeaveControlEnabled(): Unit = runBlocking {
        val r = ArmRig(); r.beforeReply = { p -> if (p.set == 0x48 && p.payload.u8(1) == 16) throw RobotRequestException(RequestProblem.REJECTED, 2) }
        assertFailsWith<RobotRequestException> { r.commands.setObserving(true) }
        assertEquals(ArmObservation.FAILED, r.state.observation); assertFalse(r.state.busy)
        assertEquals(ArmProblem.REQUEST_REJECTED, r.state.problem)
        assertEquals(4, r.sent.count { it.set == 0x48 && it.id == 4 })
        assertFailsWith<ArmOperationException> { r.commands.beginControl() }
    }
    @Test fun stoppingObservationRetiresAnAddAwaitingItsAckWithoutPublishingOrAddingTheNextTopic(): Unit = runBlocking {
        val r = ArmRig(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        r.beforeReply = { if (it.set == 0x48) { entered.complete(Unit); release.await() } }
        val opening = async { r.commands.setObserving(true) }; entered.await()
        val stopping = launch { r.commands.setObserving(false) }; yield()
        assertEquals(ArmObservation.OFF, r.state.observation); assertTrue(r.state.busy); release.complete(Unit)
        assertFailsWith<CancellationException> { opening.await() }; stopping.join()
        assertEquals(1, r.requests.size); assertEquals(ArmObservation.OFF, r.state.observation)
        assertFalse(r.state.busy)
    }
    @Test fun bindingWritesHaveFreshExpectedStateOnlineSelectionAndVerifiedReadback(): Unit = runBlocking {
        val r = ArmRig(); val original = r.binding
        r.commands.setBinding(ArmServoBinding(2, 1), original)
        assertEquals(listOf(0x68, 0x67, 0x68), r.requests.map { it.id })
        assertEquals("020100", r.requests[1].payload.hex()); assertTrue(r.state.bindingAcknowledged == true)
        assertTrue(r.state.bindingMatchesRequest == true); assertEquals(ArmServoBinding(2, 1), r.state.binding)
        r.commands.setBinding(ArmServoBinding.Uninstalled, ArmServoBinding(2, 1))
        assertEquals("ffff00", r.requests[4].payload.hex()); assertTrue(r.state.binding!!.uninstalled)
        val changed = ArmRig(); changed.binding = ArmServoBinding(2, 1)
        assertEquals(ArmProblem.BINDING_CHANGED, assertFailsWith<ArmOperationException> { changed.commands.setBinding(ArmServoBinding.Uninstalled, original) }.problem)
        assertEquals(listOf(0x68), changed.requests.map { it.id })
    }
    @Test fun hardwareAndModeChangesAtTheActualDispatchBoundaryInvalidateTheWrite(): Unit = runBlocking {
        val r = ArmRig()
        r.beforeDispatch = { p -> if (p.id == 0x67) r.health = r.health.copy(modules = r.health.modules!!.filter { it.deviceId != 0x0319 }) }
        assertEquals(ArmProblem.SERVO_OFFLINE, assertFailsWith<ArmOperationException> { r.commands.setBinding(ArmServoBinding(2, 1), r.binding) }.problem)
        assertEquals(listOf(0x68), r.requests.map { it.id })
        val mode = ArmRig(); mode.reportMode(0)
        mode.beforeDispatch = { if (it.id == 0x21) mode.reportMode(1) }
        assertEquals(ArmProblem.MODE_CHANGED, assertFailsWith<ArmOperationException> { mode.commands.setLoadMode(ArmLoadMode.ON_LOAD, mode.binding, 0) }.problem)
        assertEquals(listOf(0x68), mode.requests.map { it.id })
    }
    @Test fun lostBindingAckInvalidatesTheBindingAndAnExplicitReadCanResolveWithoutReplaying(): Unit = runBlocking {
        val r = ArmRig(); r.beforeReply = { p -> if (p.id == 0x67) throw RobotRequestException(RequestProblem.TIMED_OUT) }
        assertFailsWith<RobotRequestException> { r.commands.setBinding(ArmServoBinding(2, 1), r.binding) }
        assertNull(r.state.binding); assertNull(r.state.bindingAcknowledged); assertEquals(ArmProblem.REQUEST_UNKNOWN, r.state.problem)
        r.commands.readBinding()
        assertEquals(ArmServoBinding(2, 1), r.state.binding); assertTrue(r.state.bindingMatchesRequest == true)
        assertEquals(1, r.requests.count { it.id == 0x67 }); assertFalse(r.state.busy)
    }
    @Test fun bindingReadbackMismatchAndRejectionNeverClaimTheRequestedBindingWasInstalled(): Unit = runBlocking {
        val mismatch = ArmRig().apply { applyBindingWrite = false }
        assertEquals(ArmProblem.READBACK_MISMATCH, assertFailsWith<ArmOperationException> { mismatch.commands.setBinding(ArmServoBinding(2, 1), mismatch.binding) }.problem)
        assertTrue(mismatch.state.bindingAcknowledged == true); assertFalse(mismatch.state.bindingMatchesRequest!!)
        val rejected = ArmRig(); rejected.beforeReply = { if (it.id == 0x67) throw RobotRequestException(RequestProblem.REJECTED, 5) }
        assertFailsWith<RobotRequestException> { rejected.commands.setBinding(ArmServoBinding(2, 1), rejected.binding) }
        assertEquals(false, rejected.state.bindingAcknowledged); assertNull(rejected.state.binding)
        assertEquals(2, rejected.requests.size)
    }
    @Test fun loadModeAckAndFreshReportAreIndependentAndStoppingInputDoesNotChangeTorqueMode(): Unit = runBlocking {
        val r = ArmRig(); r.reportMode(0)
        r.commands.setLoadMode(ArmLoadMode.ON_LOAD, r.binding, 0)
        assertEquals("01", r.requests.last().payload.hex()); assertTrue(r.state.loadModeAcknowledged == true)
        assertNull(r.state.loadModeMatchesRequest); assertEquals(0, r.state.loadModeRaw)
        r.reportMode(1); assertTrue(r.state.loadModeMatchesRequest == true)
        r.commands.stopControl(); assertEquals(1, r.state.loadModeRaw)
        assertEquals(1, r.requests.count { it.id == 0x21 })
        val unsupported = ArmRig(); unsupported.reportMode(255)
        assertEquals(ArmProblem.MODE_UNSUPPORTED, assertFailsWith<ArmOperationException> { unsupported.commands.setLoadMode(ArmLoadMode.FREE, unsupported.binding, 255) }.problem)
        assertFalse(unsupported.requests.any { it.id == 0x21 })
    }
    @Test fun aModeReportBeforeALostAckRemainsVisibleAndIsNotReplayed(): Unit = runBlocking {
        val r = ArmRig(); r.reportMode(0)
        r.beforeReply = { if (it.id == 0x21) { r.reportMode(1); throw RobotRequestException(RequestProblem.TIMED_OUT) } }
        assertFailsWith<RobotRequestException> { r.commands.setLoadMode(ArmLoadMode.ON_LOAD, r.binding, 0) }
        assertEquals(1, r.state.loadModeRaw); assertTrue(r.state.loadModeMatchesRequest == true)
        assertNull(r.state.loadModeAcknowledged); assertEquals(ArmProblem.REQUEST_UNKNOWN, r.state.problem)
        assertEquals(1, r.requests.count { it.id == 0x21 })
    }
    @Test fun inputExpirySendsNativeNeutralAndTheSameGestureCannotResume(): Unit = runBlocking {
        val r = ArmRig(); r.ready()
        val lease = r.commands.beginControl(); r.commands.move(lease, 1.0, -1.0, 0.5); r.commands.tick(true, true)
        assertEquals("0f0094066c0100044a05", r.control.last().payload.hex())
        r.now += 250; r.commands.tick(true, true)
        assertFalse(r.state.controlArmed); assertFalse(r.state.controlActive)
        assertEquals("00000004000400040004", r.control.last().payload.hex())
        assertEquals(ArmProblem.LEASE_EXPIRED, assertFailsWith<ArmOperationException> { r.commands.move(lease, 1.0, 0.0, 0.0) }.problem)
        r.samples(); val next = r.commands.beginControl(); r.commands.move(next, 0.0, 1.0, 0.0); r.commands.tick(true, true)
        assertTrue(r.state.controlActive)
        r.commands.move(next, 0.0, 0.0, 0.0); assertFalse(r.state.controlArmed)
    }
    @Test fun staleReportsStopEvenWhileTheLinkAndInputKeepRenewing(): Unit = runBlocking {
        val r = ArmRig(); r.ready(); val lease = r.commands.beginControl()
        repeat(5) { r.now = 10_000 + it * 100L; r.commands.move(lease, 1.0, 0.0, 0.0); r.commands.tick(true, true) }
        r.now = 10_500; r.commands.tick(true, true)
        assertFalse(r.state.controlArmed); assertFalse(r.state.positionFresh)
        r.samples(); assertFailsWith<ArmOperationException> { r.commands.move(lease, 1.0, 0.0, 0.0) }
    }
    @Test fun linkLossTaskTakeoverOfflineModuleAndForeignLeaseAllRequireANewGesture(): Unit = runBlocking {
        for (cause in 0..3) {
            val r = ArmRig(); r.ready(); val lease = r.commands.beginControl(); r.commands.move(lease, 1.0, 0.0, 0.0); r.commands.tick(true, true)
            when (cause) {
                0 -> r.commands.tick(true, false)
                1 -> r.commands.tick(false, true)
                2 -> { r.health = r.health.copy(modules = emptyList()); r.commands.tick(true, true) }
                3 -> { val other = ArmRig(); other.ready(); assertFailsWith<ArmOperationException> { r.commands.move(other.commands.beginControl(), 1.0, 0.0, 0.0) } }
            }
            assertFalse(r.state.controlArmed); assertEquals("00000004000400040004", r.control.last().payload.hex())
            r.health = armHealth(); r.samples(); r.commands.tick(true, true)
            assertFalse(r.state.controlActive); assertFailsWith<ArmOperationException> { r.commands.move(lease, 1.0, 0.0, 0.0) }
        }
    }
    @Test fun malformedInputsAndUnboundOrStaleActuatorsCannotKeepEarlierMotion(): Unit = runBlocking {
        val r = ArmRig(); r.ready(); val lease = r.commands.beginControl(); r.commands.move(lease, 1.0, 0.0, 0.0); r.commands.tick(true, true)
        assertFailsWith<IllegalArgumentException> { r.commands.move(lease, Double.NaN, 0.0, 0.0) }; assertFalse(r.state.controlArmed)
        val unbound = ArmRig(); unbound.binding = ArmServoBinding.Uninstalled; unbound.ready()
        assertEquals(ArmProblem.BINDING_INVALID, assertFailsWith<ArmOperationException> { unbound.commands.move(unbound.commands.beginControl(), 1.0, 0.0, 0.0) }.problem)
        val stale = ArmRig(); stale.ready(); stale.now += 1_000
        assertEquals(ArmProblem.REPORT_STALE, assertFailsWith<ArmOperationException> { stale.commands.move(stale.commands.beginControl(), 0.0, 0.0, 1.0) }.problem)
    }
    @Test fun closingAtEveryRequestBoundaryRejectsLateResultsAndNeverSendsAnotherWrite(): Unit = runBlocking {
        for (boundary in 1..3) {
            val r = ArmRig(); r.beforeReply = { if (r.requests.size == boundary) r.commands.close() }
            assertFailsWith<RobotRequestException> { r.commands.setBinding(ArmServoBinding(2, 1), r.binding) }
            assertEquals(boundary, r.requests.size); assertEquals(ArmObservation.CLOSED, r.state.observation)
            assertNull(r.state.binding); assertFalse(r.state.busy)
            assertFailsWith<RobotRequestException> { r.commands.readBinding() }
        }
        val observing = ArmRig(); observing.beforeReply = { observing.commands.close() }
        assertFailsWith<RobotRequestException> { observing.commands.setObserving(true) }
        assertEquals(1, observing.requests.size); assertEquals(ArmObservation.CLOSED, observing.state.observation)
    }
    @Test fun retiringObservationDuringABindingWriteKeepsOwnershipUntilTheInFlightRequestSettles(): Unit = runBlocking {
        val r = ArmRig(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        r.beforeReply = { if (it.id == 0x67) { entered.complete(Unit); release.await() } }
        val changing = async { r.commands.setBinding(ArmServoBinding(2, 1), r.binding) }; entered.await()
        val retiring = launch { r.commands.setObserving(false) }; yield()
        assertTrue(r.state.busy); assertNull(r.state.binding); assertNull(r.state.bindingAcknowledged)
        release.complete(Unit); assertFailsWith<CancellationException> { changing.await() }; retiring.join()
        assertFalse(r.state.busy); assertEquals(listOf(0x68, 0x67), r.requests.map { it.id })
        r.commands.readBinding(); assertTrue(r.state.bindingMatchesRequest == true)
        assertEquals(1, r.requests.count { it.id == 0x67 })
    }
    @Test fun clawOnlyControlDoesNotInventAnArmBindingAndUnknownStatusRemainsRaw(): Unit = runBlocking {
        val r = ArmRig(); r.health = RobotHealth(modules = listOf(WorkingRobotDevice(0x031f, emptyList())))
        r.commands.setObserving(true); r.samples()
        r.commands.receive(armFrame(9, 0x48, 8, "0010ff".hexBytes()))
        assertNull(r.state.binding); assertNull(r.state.position); assertEquals(255, r.state.clawStatusRaw)
        assertTrue(r.state.clawFresh); assertFalse(r.state.armOnline)
        val lease = r.commands.beginControl(); r.commands.move(lease, 0.0, 0.0, 1.0); r.commands.tick(true, true)
        assertEquals("08000004000400049406", r.control.last().payload.hex())
        assertFalse(r.requests.any { it.id == 0x68 })
    }
    @Test fun failedNeutralDeliveryStillRevokesLocalInputAndOnlyNeutralCanBeRetried(): Unit = runBlocking {
        val r = ArmRig(); r.ready(); val lease = r.commands.beginControl()
        r.commands.move(lease, 1.0, 0.0, 0.0); r.commands.tick(true, true)
        var failed = false
        r.beforeSend = { if (it.set == 0x33 && it.payload.u8(0) == 0 && !failed) { failed = true; error("synthetic send failure") } }
        r.commands.stopControl()
        assertFalse(r.state.controlActive); assertFalse(r.state.controlArmed); assertEquals(ArmProblem.REQUEST_UNKNOWN, r.state.problem)
        repeat(3) { r.commands.tick(true, true) }
        assertEquals(1, r.control.count { it.payload.u8(0) != 0 })
        assertEquals(3, r.control.count { it.payload.hex() == "00000004000400040004" })
        assertFailsWith<ArmOperationException> { r.commands.move(lease, 1.0, 0.0, 0.0) }
    }
    @Test fun coroutineCancellationAtDispatchCannotWriteAfterThePreflightCheck(): Unit = runBlocking {
        val r = ArmRig()
        val changing = async {
            val current = currentCoroutineContext()
            r.beforeDispatch = { if (it.id == 0x67) current.cancel() }
            r.commands.setBinding(ArmServoBinding(2, 1), r.binding)
        }
        assertFailsWith<CancellationException> { changing.await() }
        assertEquals(listOf(0x68), r.requests.map { it.id }); assertEquals(ArmServoBinding(1, 2), r.binding)
        assertNull(r.state.requestedBinding); assertFalse(r.state.busy)
    }
    @Test fun servoIdentificationUsesExistingNativeEncodingAndEndsAfterThreeSeconds(): Unit = runBlocking {
        val r = ArmRig(); r.commands.identifyServo(2)
        assertEquals("89/0/96/ff0200ff00", r.requests.single().text)
        assertEquals(2, r.state.identifyingServo)
        r.now += 2_999; r.commands.tick(false, true); assertEquals(2, r.state.identifyingServo)
        r.now++; r.commands.tick(false, true)
        assertNull(r.state.identifyingServo)
        assertEquals("89/0/96/ff0100ff00", r.sent.single { it.id == 0x60 }.text)
    }
    @Test fun identificationReplacementOfflineAndObservationRetirementEndThePreviousLight(): Unit = runBlocking {
        val r = ArmRig(); r.commands.identifyServo(1); r.commands.identifyServo(2)
        assertEquals("57/0/96/ff0100ff00", r.sent.last().text)
        r.health = r.health.copy(modules = r.health.modules!!.filter { it.deviceId != 0x0319 }); r.commands.tick(false, true)
        assertNull(r.state.identifyingServo); assertEquals("89/0/96/ff0100ff00", r.sent.last().text)
        assertEquals(ArmProblem.SERVO_OFFLINE, assertFailsWith<ArmOperationException> { r.commands.identifyServo(2) }.problem)
        r.commands.identifyServo(1); r.commands.setObserving(false)
        assertNull(r.state.identifyingServo); assertEquals("57/0/96/ff0100ff00", r.sent.last().text)
    }
    @Test fun lostIdentificationAckAndCloseStillEndTheLightWithoutFurtherWritesAfterClose(): Unit = runBlocking {
        val r = ArmRig(); r.beforeReply = { if (it.id == 0x60) throw RobotRequestException(RequestProblem.TIMED_OUT) }
        assertFailsWith<RobotRequestException> { r.commands.identifyServo(1) }
        assertEquals(1, r.state.identifyingServo)
        r.commands.close()
        assertEquals("57/0/96/ff0100ff00", r.sent.last().text); assertEquals(ArmObservation.CLOSED, r.state.observation)
        val count = r.sent.size; r.commands.tick(false, true); r.commands.stopIdentification(); assertEquals(count, r.sent.size)
    }
}

private data class ArmPacket(val node: Int, val set: Int, val id: Int, val payload: ByteArray) {
    val text: String get() = "$node/$set/$id/${payload.hex()}"
}
private fun armHealth() = RobotHealth(modules = listOf(0x0318, 0x0319, 0x031e, 0x031f).map { WorkingRobotDevice(it, emptyList()) })
private class ArmRig {
    var now = 10_000L
    var health = armHealth()
    var binding = ArmServoBinding(1, 2)
    var applyBindingWrite = true
    var beforeDispatch: (ArmPacket) -> Unit = {}
    var beforeReply: suspend (ArmPacket) -> Unit = {}
    var beforeSend: (ArmPacket) -> Unit = {}
    val requests = mutableListOf<ArmPacket>()
    val sent = mutableListOf<ArmPacket>()
    val commands = ArmCommands(RobotLock(), { health }, { node, set, id, payload, check ->
        val p = ArmPacket(node, set, id, payload.copyOf()); beforeDispatch(p); check(); requests += p
        if (id == 0x67 && applyBindingWrite) binding = ArmServoBinding(payload.u8(0), payload.u8(1))
        beforeReply(p)
        if (id == 0x68) byteArrayOf(0, binding.main.toByte(), binding.secondary.toByte()) else byteArrayOf(0)
    }, { node, set, id, payload -> val p = ArmPacket(node, set, id, payload.copyOf()); beforeSend(p); sent += p }, { now })
    val state get() = commands.state.value
    val control get() = sent.filter { it.set == 0x33 && it.id == 0x10 }
    suspend fun ready() { commands.setObserving(true); commands.readBinding(); samples() }
    fun reportMode(raw: Int) = commands.receive(armFrame(3, 0x33, 0x22, byteArrayOf(raw.toByte())))
    fun samples() {
        commands.receive(armFrame(9, 0x48, 8, "000f000000000000000000".hexBytes()))
        commands.receive(armFrame(9, 0x48, 8, "001001".hexBytes()))
    }
}
