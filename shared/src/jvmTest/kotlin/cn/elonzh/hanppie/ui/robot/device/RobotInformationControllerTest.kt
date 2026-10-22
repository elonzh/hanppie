package cn.elonzh.hanppie.ui.robot.device

import cn.elonzh.hanppie.robot.telemetry.RobotSystemStatus
import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

internal class InformationFixture : RobotInformationService {
    override val systemStatus = MutableStateFlow<RobotSystemStatus?>(null)
    val reads = mutableListOf<RobotVersionTarget>()
    var version: suspend (RobotVersionTarget) -> String = { "01.02.03.04" }
    var activation: suspend () -> Boolean = { true }
    override suspend fun readFirmware(target: RobotVersionTarget): String { reads += target; return version(target) }
    override suspend fun readActivated() = activation()
}

class RobotInformationControllerTest {
    @Test fun moduleReportsSelectTargetsAndRebindingRetiresEveryOldSnapshot(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = RobotInformationController(scope)
        val first = InformationFixture(); val health = MutableStateFlow(RobotHealth())
        try {
            controller.attach(first, health)
            assertTrue(first.reads.isEmpty())
            assertEquals(setOf(RobotVersionTarget.SYSTEM, RobotVersionTarget.SYSTEM_CAN, RobotVersionTarget.PROGRAM_RUNTIME), controller.state.value.versions.keys)
            health.value = RobotHealth(modules = listOf(WorkingRobotDevice(0x104, emptyList()), WorkingRobotDevice(0xabcd, emptyList())))
            assertEquals(4, controller.state.value.versions.size)
            controller.refresh(); assertIs<InformationValue.Value<String>>(controller.state.value.versions[RobotVersionTarget.CAMERA])
            first.systemStatus.value = RobotSystemStatus(0, 0, false); assertEquals(RobotSystemStatus(0, 0, false), controller.state.value.usage)
            health.value = RobotHealth(modules = emptyList()); assertFalse(RobotVersionTarget.CAMERA in controller.state.value.versions)
            val second = InformationFixture(); controller.attach(second, MutableStateFlow(RobotHealth()))
            first.systemStatus.value = RobotSystemStatus(123, 456, false)
            assertNull(controller.state.value.usage); assertEquals(InformationValue.Unread, controller.state.value.activation)
            assertTrue(controller.state.value.versions.values.all { it == InformationValue.Unread })
            controller.clear(); second.systemStatus.value = RobotSystemStatus(1, 2, false)
            assertEquals(RobotInformationState(), controller.state.value)
            scope.cancel(); controller.attach(first, health); assertFalse(controller.state.value.connected)
        } finally { scope.cancel() }
    }

    @Test fun failedFieldsDoNotEraseSuccessfulReadsAndRetryIsIndependent(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = InformationFixture()
        val controller = RobotInformationController(scope)
        try {
            service.version = { if (it == RobotVersionTarget.SYSTEM_CAN) throw RobotRequestException(RequestProblem.REJECTED) else "01.02.03.04" }
            service.activation = { throw RobotRequestException(RequestProblem.TIMED_OUT) }
            controller.attach(service, MutableStateFlow(RobotHealth())); controller.refresh()
            assertIs<InformationValue.Value<String>>(controller.state.value.versions[RobotVersionTarget.SYSTEM])
            assertIs<InformationValue.Failed>(controller.state.value.versions[RobotVersionTarget.SYSTEM_CAN])
            assertIs<InformationValue.Failed>(controller.state.value.activation)
            service.version = { "02.00.00.00" }; controller.readVersion(RobotVersionTarget.SYSTEM_CAN)
            service.activation = { false }; controller.readActivation()
            assertEquals(InformationValue.Value(false), controller.state.value.activation)
            assertEquals(InformationValue.Value("02.00.00.00"), controller.state.value.versions[RobotVersionTarget.SYSTEM_CAN])
            assertEquals(InformationValue.Value("01.02.03.04"), controller.state.value.versions[RobotVersionTarget.SYSTEM])
            assertEquals(4, service.reads.size)
        } finally { scope.cancel() }
    }

    @Test fun batchesBoundConcurrencyDuplicateClicksDoNotQueueAndCancellationCanBeRetried(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = InformationFixture(); val controller = RobotInformationController(scope)
        var running = 0; var maximum = 0; var waiting = true
        suspend fun read(): String {
            running++; maximum = maxOf(maximum, running)
            try { if (waiting) awaitCancellation(); return "01.00.00.00" } finally { running-- }
        }
        service.version = { read() }; service.activation = { read(); true }
        try {
            controller.attach(service, MutableStateFlow(RobotHealth(modules = RobotVersionTarget.entries.mapNotNull { it.componentId }.distinct().map { WorkingRobotDevice(it, emptyList()) })))
            controller.refresh(); controller.refresh(); controller.readVersion(RobotVersionTarget.SYSTEM)
            assertEquals(4, running); assertEquals(4, service.reads.size)
            assertTrue(controller.state.value.busy)
            controller.cancelQueries()
            assertEquals(0, running); assertFalse(controller.state.value.busy)
            assertTrue(controller.state.value.versions.values.all { it == InformationValue.Unread })
            waiting = false; controller.refresh()
            assertTrue(controller.state.value.versions.values.all { it is InformationValue.Value })
            assertEquals(InformationValue.Value(true), controller.state.value.activation)
            assertEquals(4, maximum)
        } finally { scope.cancel() }
    }

    @Test fun delayedQueriesCannotReinsertRemovedModulesOrPublishAfterCancelAndRebind(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = InformationFixture(); val controller = RobotInformationController(scope)
        val health = MutableStateFlow(RobotHealth(modules = listOf(WorkingRobotDevice(0x104, emptyList()))))
        var gate = CompletableDeferred<Unit>()
        service.version = { withContext(NonCancellable) { gate.await() }; "old" }
        try {
            controller.attach(service, health); controller.readVersion(RobotVersionTarget.CAMERA)
            health.value = RobotHealth(); health.value = RobotHealth(modules = listOf(WorkingRobotDevice(0x104, emptyList())))
            gate.complete(Unit)
            assertEquals(InformationValue.Unread, controller.state.value.versions[RobotVersionTarget.CAMERA])
            gate = CompletableDeferred(); controller.readVersion(RobotVersionTarget.SYSTEM); controller.cancelQueries(); gate.complete(Unit)
            assertEquals(InformationValue.Unread, controller.state.value.versions[RobotVersionTarget.SYSTEM])
            gate = CompletableDeferred(); controller.readVersion(RobotVersionTarget.SYSTEM)
            controller.attach(InformationFixture(), MutableStateFlow(RobotHealth())); gate.complete(Unit)
            assertEquals(InformationValue.Unread, controller.state.value.versions[RobotVersionTarget.SYSTEM])
        } finally { scope.cancel() }
    }
}
