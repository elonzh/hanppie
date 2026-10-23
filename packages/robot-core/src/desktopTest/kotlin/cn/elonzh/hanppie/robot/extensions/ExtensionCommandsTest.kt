package cn.elonzh.hanppie.robot.extensions

import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import kotlinx.coroutines.*
import kotlin.test.*

class ExtensionCommandsTest {
    @Test fun eachFamilyWaitsForItsAckThenAcceptsOnlyNewReportsAndSwitchingClearsOldData(): Unit = runBlocking {
        val r = ExtensionRig(); r.beforeReply = { r.sample(ExtensionKind.SERVO) }
        r.commands.setObserving(ExtensionKind.SERVO)
        assertEquals(ExtensionObservation.ACTIVE, r.state.observation); assertTrue(r.state.readings.isEmpty())
        r.sample(ExtensionKind.SERVO); assertTrue(r.state.fresh); assertEquals(setOf(RobotComponent.SERVO_1), r.state.readings.keys)
        val count = r.requests.size; r.commands.setObserving(ExtensionKind.SERVO); assertEquals(count, r.requests.size)
        r.commands.setObserving(ExtensionKind.TOF); assertTrue(r.state.readings.isEmpty()); assertFalse(r.state.fresh)
        r.sample(ExtensionKind.SERVO); assertTrue(r.state.readings.isEmpty())
        r.sample(ExtensionKind.TOF); assertEquals(setOf(RobotComponent.TOF_1), r.state.readings.keys)
        assertEquals("9/72/4/000211", r.sent.last())
        r.commands.setObserving(null); r.sample(ExtensionKind.TOF)
        assertEquals(ExtensionObservation.OFF, r.state.observation); assertTrue(r.state.readings.isEmpty())
        assertEquals("9/72/4/000212", r.sent.last())
    }
    @Test fun rejectionClearsSamplesAndExplicitRetryDoesNotReplayIdentification(): Unit = runBlocking {
        val r = ExtensionRig(); r.beforeReply = { throw RobotRequestException(RequestProblem.REJECTED, 3) }
        assertFailsWith<RobotRequestException> { r.commands.setObserving(ExtensionKind.ADAPTER) }
        assertEquals(ExtensionObservation.FAILED, r.state.observation); assertEquals(ExtensionProblem.REQUEST_REJECTED, r.state.problem)
        assertTrue(r.state.readings.isEmpty()); assertEquals("9/72/4/000213", r.sent.last()); assertFalse(r.state.busy)
        r.beforeReply = {}; r.commands.setObserving(ExtensionKind.ADAPTER); r.sample(ExtensionKind.ADAPTER)
        assertTrue(r.state.fresh); assertNull(r.state.identifying); assertTrue(r.requests.all { it.id == 3 })
    }
    @Test fun retirementDuringAddKeepsTheRequestBusyUntilItSettlesAndCannotPublishItsLateResult(): Unit = runBlocking {
        val r = ExtensionRig(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        r.beforeReply = { entered.complete(Unit); release.await() }
        val opening = async { r.commands.setObserving(ExtensionKind.SERVO) }; entered.await()
        val stopping = launch { r.commands.setObserving(null) }; yield()
        assertEquals(ExtensionObservation.OFF, r.state.observation); assertTrue(r.state.busy)
        assertEquals(listOf("9/72/4/000211"), r.sent)
        release.complete(Unit); assertFailsWith<CancellationException> { opening.await() }; stopping.join()
        r.sample(ExtensionKind.SERVO); assertTrue(r.state.readings.isEmpty()); assertFalse(r.state.busy)
        assertEquals(1, r.requests.size); assertEquals("9/72/4/000211", r.sent.last())
        assertEquals(2, r.sent.size)
    }
    @Test fun modulePresenceIsCurrentHealthEvidenceAndRemovedSamplesNeverResurrectOnReconnection(): Unit = runBlocking {
        val r = ExtensionRig(); r.health = RobotHealth()
        assertEquals(ExtensionProblem.MODULES_UNKNOWN, assertFailsWith<ExtensionOperationException> { r.commands.setObserving(ExtensionKind.SERVO) }.problem)
        r.health = RobotHealth(modules = emptyList())
        assertEquals(ExtensionProblem.MODULE_OFFLINE, assertFailsWith<ExtensionOperationException> { r.commands.setObserving(ExtensionKind.SERVO) }.problem)
        r.online(); r.commands.setObserving(ExtensionKind.SERVO); r.sample(ExtensionKind.SERVO)
        r.health = RobotHealth(modules = emptyList()); r.commands.tick(); assertTrue(r.state.readings.isEmpty()); assertFalse(r.state.fresh)
        r.sample(ExtensionKind.SERVO); assertFalse(r.state.fresh)
        r.online(); r.commands.tick(); assertTrue(r.state.readings.isEmpty()); assertFalse(r.state.fresh); r.sample(ExtensionKind.SERVO)
        assertEquals(setOf(RobotComponent.SERVO_1), r.state.readings.keys)
    }
    @Test fun sampleAgeExpiresWithoutLosingRawValuesAndFreshSamplesCanRecoverObservation(): Unit = runBlocking {
        val r = ExtensionRig(); r.commands.setObserving(ExtensionKind.ADAPTER); r.sample(ExtensionKind.ADAPTER)
        assertEquals(0, r.state.ageSeconds); r.now += 1_000; r.commands.tick()
        assertFalse(r.state.fresh); assertEquals(1, r.state.ageSeconds); assertTrue(r.state.readings.isNotEmpty())
        r.sample(ExtensionKind.ADAPTER); assertTrue(r.state.fresh); assertEquals(0, r.state.ageSeconds)
    }
    @Test fun taskTakeoverRetiresTheTopicImmediatelyAndRequiresAnExplicitNewObservation(): Unit = runBlocking {
        val r = ExtensionRig(); r.commands.setObserving(ExtensionKind.TOF); r.sample(ExtensionKind.TOF)
        r.commands.retire(); assertEquals("9/72/4/000212", r.sent.last())
        r.sample(ExtensionKind.TOF); assertTrue(r.state.readings.isEmpty()); assertFalse(r.state.fresh)
        assertEquals(ExtensionObservation.OFF, r.state.observation)
        r.commands.setObserving(ExtensionKind.TOF); r.sample(ExtensionKind.TOF); assertTrue(r.state.fresh)
    }
    @Test fun identifyRoutesAllFamiliesAndReplacesOrEndsThePreviousLampOnTimeoutOrOffline(): Unit = runBlocking {
        val r = ExtensionRig(); r.commands.identify(RobotComponent.SERVO_1)
        assertEquals(0x39, r.requests.last().node); assertEquals("ff0200ff00", r.requests.last().payload.hex())
        r.commands.identify(RobotComponent.SENSOR_ADAPTER_6)
        assertEquals("57/0/96/ff0100ff00", r.sent.last()); assertEquals(0xd6, r.requests.last().node)
        r.now += 3_000; r.commands.tick(); assertNull(r.state.identifying); assertEquals("214/0/96/ff0100ff00", r.sent.last())
        r.commands.identify(RobotComponent.TOF_1); assertEquals(0x32, r.requests.last().node)
        r.health = RobotHealth(modules = emptyList()); r.commands.tick(); assertNull(r.state.identifying)
        assertEquals("50/0/96/ff0100ff00", r.sent.last())
    }
    @Test fun actualDispatchRechecksPresenceAndCancelledCallsCannotReachTheWire(): Unit = runBlocking {
        val r = ExtensionRig(); r.beforeDispatch = { r.health = RobotHealth(modules = emptyList()) }
        assertFailsWith<ExtensionOperationException> { r.commands.identify(RobotComponent.SERVO_1) }
        assertTrue(r.requests.isEmpty()); assertNull(r.state.identifying)
        val c = ExtensionRig(); c.beforeDispatch = { currentCoroutineContext().cancel() }
        val job = async { c.commands.setObserving(ExtensionKind.SERVO) }
        assertFailsWith<CancellationException> { job.await() }; assertTrue(c.requests.isEmpty())
    }
    @Test fun lostIdentificationAckIsStillCleanedAndLoaderRetirementSendsNoCleanup(): Unit = runBlocking {
        val r = ExtensionRig(); r.beforeReply = { throw RobotRequestException(RequestProblem.TIMED_OUT) }
        assertFailsWith<RobotRequestException> { r.commands.identify(RobotComponent.SENSOR_ADAPTER_6) }
        assertEquals(RobotComponent.SENSOR_ADAPTER_6, r.state.identifying)
        r.now += 3_000; r.commands.tick(); assertNull(r.state.identifying); assertEquals("214/0/96/ff0100ff00", r.sent.last())
        r.beforeReply = {}; r.commands.setObserving(ExtensionKind.SERVO); r.commands.identify(RobotComponent.SERVO_1)
        val count = r.sent.size; r.commands.retire(sendCleanup = false); r.commands.close(sendCleanup = false)
        assertEquals(count, r.sent.size); assertEquals(ExtensionObservation.CLOSED, r.state.observation)
        r.sample(ExtensionKind.SERVO); r.commands.tick(); r.commands.stopIdentification(); assertEquals(count, r.sent.size)
        assertFailsWith<RobotRequestException> { r.commands.setObserving(ExtensionKind.SERVO) }
    }
    @Test fun closingWithAnInFlightIdentificationCannotRestoreTheLampOrPublishNewState(): Unit = runBlocking {
        val r = ExtensionRig(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        r.beforeReply = { entered.complete(Unit); release.await() }
        val opening = async { runCatching { r.commands.identify(RobotComponent.SERVO_1) } }; entered.await()
        r.commands.close(); val sent = r.sent.toList(); release.complete(Unit)
        assertIs<RobotRequestException>(opening.await().exceptionOrNull()); assertEquals(ExtensionObservation.CLOSED, r.state.observation)
        assertEquals(sent, r.sent); assertNull(r.state.identifying)
    }
}

private data class ExtensionRequest(val node: Int, val set: Int, val id: Int, val payload: ByteArray)
private class ExtensionRig {
    var now = 10_000L
    var health = RobotHealth()
    var beforeDispatch: suspend (ExtensionRequest) -> Unit = {}
    var beforeReply: suspend (ExtensionRequest) -> Unit = {}
    val requests = mutableListOf<ExtensionRequest>()
    val sent = mutableListOf<String>()
    val commands = ExtensionCommands(RobotLock(), { health }, { node, set, id, payload, check ->
        val p = ExtensionRequest(node, set, id, payload.copyOf()); beforeDispatch(p); check(); requests += p; beforeReply(p); byteArrayOf(0)
    }, { node, set, id, payload -> sent += "$node/$set/$id/${payload.hex()}" }, { now })
    init { online() }
    val state get() = commands.state.value
    fun online() { health = RobotHealth(modules = listOf(0x0318, 0x1201, 0x1606).map { WorkingRobotDevice(it, emptyList()) }) }
    fun sample(kind: ExtensionKind) = commands.receive(extensionFrame(kind, ByteArray(kind.topic().bytes)))
}
