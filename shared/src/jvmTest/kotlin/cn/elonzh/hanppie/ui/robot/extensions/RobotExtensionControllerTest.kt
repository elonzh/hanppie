package cn.elonzh.hanppie.ui.robot.extensions

import cn.elonzh.hanppie.robot.extensions.*
import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.telemetry.*
import cn.elonzh.hanppie.ui.app.RobotSessionFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

internal class ExtensionServiceFixture : RobotExtensionService {
    override val state = MutableStateFlow(RobotExtensionState(online = ExtensionKind.entries.flatMap { kind -> (1..kind.count).map(kind::component) }.toSet()))
    val calls = CopyOnWriteArrayList<String>()
    var onObserve: suspend () -> Unit = {}
    var onIdentify: suspend () -> Unit = {}
    override suspend fun setObserving(kind: ExtensionKind?) {
        calls += "observe:$kind"
        if (kind == null) { stopIdentification(); state.value = state.value.copy(kind = null, observation = ExtensionObservation.OFF, readings = emptyMap(), fresh = false); return }
        state.value = state.value.copy(kind = kind, observation = ExtensionObservation.STARTING, busy = true, readings = emptyMap(), fresh = false, problem = null)
        try { onObserve(); state.value = state.value.copy(observation = ExtensionObservation.ACTIVE) }
        catch (error: Exception) { state.value = state.value.copy(observation = ExtensionObservation.FAILED); throw error }
        finally { state.value = state.value.copy(busy = false) }
    }
    override suspend fun identify(component: RobotComponent) {
        calls += "identify:$component"; state.value = state.value.copy(identifying = component, busy = true)
        try { onIdentify() } finally { state.value = state.value.copy(busy = false) }
    }
    override fun stopIdentification() { state.value = state.value.copy(identifying = null) }
    fun sample() {
        val kind = state.value.kind ?: return
        val readings = state.value.online.orEmpty().filter { kind.index(it) != null }.associateWith { c -> when (kind) {
            ExtensionKind.SERVO -> ServoReading(c, true, -42, 1024)
            ExtensionKind.TOF -> TofReading(c, 254, 171, 128, 1234)
            ExtensionKind.ADAPTER -> AdapterReading(c, 1, 255, 1023, 65535)
        } }
        state.value = state.value.copy(readings = readings, fresh = true, ageSeconds = 0)
    }
}

internal fun extensionSession(service: ExtensionServiceFixture, versions: MutableList<RobotVersionTarget> = CopyOnWriteArrayList()): RobotSessionFixture = RobotSessionFixture().apply {
    extensions = service
    health.value = RobotHealth(modules = service.state.value.online.orEmpty().map { WorkingRobotDevice(it.deviceId, emptyList()) })
    information = object : RobotInformationService {
        override val systemStatus = MutableStateFlow<RobotSystemStatus?>(null)
        override suspend fun readFirmware(target: RobotVersionTarget): String { versions += target; return "01.02.03.04" }
        override suspend fun readActivated(): Boolean = true
    }
}

class RobotExtensionControllerTest {
    private fun controller(scope: CoroutineScope, allowed: () -> Boolean = { true },
        change: suspend (RobotExtensionService, suspend () -> Unit) -> Unit = { _, action -> action() }) =
        RobotExtensionController(scope, change, { allowed() })

    @Test fun onlyAnExplicitOnlineSelectionStartsObservationAndLeavingStopsTheLampAndReports(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val service = ExtensionServiceFixture(); val c = controller(scope)
        try {
            c.attach(service); c.setActive(true); assertTrue(service.calls.isEmpty())
            c.select(RobotComponent.SERVO_1); assertEquals(ExtensionObservation.ACTIVE, c.state.value.task.observation)
            service.sample(); assertTrue(c.state.value.task.fresh)
            c.identify(); assertEquals(RobotComponent.SERVO_1, service.state.value.identifying)
            c.setActive(false); c.pauseAndJoin(); assertNull(service.state.value.identifying)
            assertTrue(c.state.value.task.readings.isEmpty()); assertEquals(ExtensionObservation.OFF, service.state.value.observation)
        } finally { c.clear(); scope.cancel() }
    }
    @Test fun familyChangesAndOfflineSelectionDiscardOldReadingsWithoutChoosingAnotherModule(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val service = ExtensionServiceFixture(); val c = controller(scope)
        try {
            c.attach(service); c.setActive(true); c.select(RobotComponent.SERVO_4); service.sample()
            c.selectFamily(ExtensionKind.TOF); assertNull(c.state.value.selected); assertTrue(c.state.value.task.readings.isEmpty())
            c.select(RobotComponent.SERVO_1); assertNull(c.state.value.selected)
            c.select(RobotComponent.TOF_2); service.sample(); val online = service.state.value.online!!
            service.state.value = service.state.value.copy(online = online - RobotComponent.TOF_2)
            assertNull(c.state.value.selected); assertTrue(c.state.value.task.readings.isEmpty()); assertNotNull(c.state.value.error)
            val requests = service.calls.size; service.state.value = service.state.value.copy(online = online)
            assertNull(c.state.value.selected); assertEquals(requests, service.calls.size)
        } finally { c.clear(); scope.cancel() }
    }
    @Test fun failedObservationWaitsForExplicitRetryAndNeverReplaysIdentification(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val service = ExtensionServiceFixture(); val c = controller(scope)
        try {
            service.onObserve = { throw ExtensionOperationException(ExtensionProblem.REQUEST_REJECTED) }
            c.attach(service); c.setActive(true); c.select(RobotComponent.SERVO_1)
            assertNotNull(c.state.value.error); val count = service.calls.size
            c.refreshVisibility(); assertEquals(count, service.calls.size)
            service.onObserve = {}; c.retry(); assertEquals(ExtensionObservation.ACTIVE, service.state.value.observation)
            assertNull(c.state.value.error); assertTrue(service.calls.none { it.startsWith("identify:") })
        } finally { c.clear(); scope.cancel() }
    }
    @Test fun foregroundAndOtherTaskPausesMayResumeOnlyReadOnlyObservation(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val service = ExtensionServiceFixture(); var allowed = true; val c = controller(scope, { allowed })
        try {
            c.attach(service); c.setActive(true); c.select(RobotComponent.SERVO_1); c.identify()
            allowed = false; c.refreshVisibility(); assertEquals(ExtensionObservation.OFF, service.state.value.observation)
            assertNull(service.state.value.identifying); val identities = service.calls.count { it.startsWith("identify:") }
            allowed = true; c.refreshVisibility(); assertEquals(ExtensionObservation.ACTIVE, service.state.value.observation)
            assertEquals(identities, service.calls.count { it.startsWith("identify:") }); assertTrue(c.state.value.task.readings.isEmpty())
        } finally { c.clear(); scope.cancel() }
    }
    @Test fun lateCancelledOldConnectionCannotPublishOrStopTheNewConnection(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val old = ExtensionServiceFixture(); val fresh = ExtensionServiceFixture(); val c = controller(scope)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            old.onObserve = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            c.attach(old); c.setActive(true); val connection = c.state.value.connection; c.select(RobotComponent.SERVO_1); entered.await()
            c.attach(fresh); c.select(RobotComponent.SERVO_4); fresh.sample(); release.complete(Unit); yield()
            assertEquals(RobotComponent.SERVO_4, c.state.value.selected); assertTrue(c.state.value.task.fresh)
            c.selectFamily(ExtensionKind.ADAPTER, connection); c.identify(connection); c.retry(connection)
            assertEquals(ExtensionKind.SERVO, c.state.value.family); assertTrue(fresh.calls.none { it.startsWith("identify:") })
            assertEquals(ExtensionObservation.OFF, old.state.value.observation)
        } finally { release.complete(Unit); c.clear(); scope.cancel() }
    }
    @Test fun leavingDuringIdentificationCancelsTheRootOperationAndNewSelectionCannotRunTheOldWrite(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val service = ExtensionServiceFixture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val c = controller(scope, change = { _, action -> entered.complete(Unit); release.await(); action() })
        try {
            c.attach(service); c.setActive(true); c.select(RobotComponent.SERVO_1); c.identify(); entered.await()
            c.select(RobotComponent.SERVO_2); release.complete(Unit); yield()
            assertEquals(RobotComponent.SERVO_2, c.state.value.selected); assertFalse(c.state.value.busy)
            assertTrue(service.calls.none { it.startsWith("identify:") })
        } finally { release.complete(Unit); c.clear(); scope.cancel() }
    }
}
