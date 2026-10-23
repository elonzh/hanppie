package cn.elonzh.hanppie.ui.robot.arm

import cn.elonzh.hanppie.robot.extensions.arm.*
import cn.elonzh.hanppie.robot.session.RobotTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

internal class ArmServiceFixture : RobotArmService {
    override val state = MutableStateFlow(RobotArmState(armOnline = true, clawOnline = true, servoIds = setOf(1, 2, 3),
        binding = ArmServoBinding(1, 2), loadModeRaw = 0, position = ArmPosition(0, 100, 120), positionFresh = true,
        positionAgeSeconds = 0, clawStatusRaw = 1, clawFresh = true, clawAgeSeconds = 0))
    val calls = CopyOnWriteArrayList<String>()
    val inputs = CopyOnWriteArrayList<Triple<Double, Double, Double>>()
    var onObserve: suspend () -> Unit = {}
    var onRead: suspend () -> Unit = {}
    var onWrite: suspend () -> Unit = {}
    var failMove = false
    private var lease: ArmControlLease? = null
    override suspend fun setObserving(enabled: Boolean) {
        calls += "observe:$enabled"
        if (enabled) {
            state.value = state.value.copy(busy = true, observation = ArmObservation.STARTING)
            try { onObserve(); state.value = state.value.copy(observation = ArmObservation.ACTIVE) }
            finally { state.value = state.value.copy(busy = false) }
        } else { stopControl(); state.value = state.value.copy(observation = ArmObservation.OFF) }
    }
    override suspend fun readBinding() {
        calls += "read"; state.value = state.value.copy(busy = true)
        try { onRead() } finally { state.value = state.value.copy(busy = false) }
    }
    override suspend fun setBinding(binding: ArmServoBinding, expected: ArmServoBinding) {
        check(state.value.binding == expected); calls += "bind:$binding"
        state.value = state.value.copy(busy = true, binding = null, requestedBinding = binding, bindingAcknowledged = null, bindingMatchesRequest = null)
        try { onWrite(); state.value = state.value.copy(binding = binding, bindingAcknowledged = true, bindingMatchesRequest = true) }
        finally { state.value = state.value.copy(busy = false) }
    }
    override suspend fun setLoadMode(mode: ArmLoadMode, expectedBinding: ArmServoBinding, expectedModeRaw: Int?) {
        check(state.value.binding == expectedBinding && state.value.loadModeRaw == expectedModeRaw)
        calls += "mode:$mode"; state.value = state.value.copy(busy = true, requestedLoadMode = mode, loadModeAcknowledged = null, loadModeMatchesRequest = null)
        try { onWrite(); state.value = state.value.copy(loadModeAcknowledged = true) }
        finally { state.value = state.value.copy(busy = false) }
    }
    override suspend fun identifyServo(id: Int) { calls += "identify:$id"; state.value = state.value.copy(identifyingServo = id) }
    override fun stopIdentification() { state.value = state.value.copy(identifyingServo = null) }
    override fun beginControl(): ArmControlLease {
        check(state.value.observation == ArmObservation.ACTIVE)
        calls += "begin"
        return object : ArmControlLease {}.also { lease = it; state.value = state.value.copy(controlArmed = true) }
    }
    override fun move(lease: ArmControlLease, x: Double, y: Double, claw: Double) {
        check(this.lease === lease && state.value.controlArmed)
        if (failMove) throw ArmOperationException(ArmProblem.REPORT_STALE)
        inputs += Triple(x, y, claw); state.value = state.value.copy(controlActive = x != 0.0 || y != 0.0 || claw != 0.0)
    }
    override fun stopControl() { lease = null; state.value = state.value.copy(controlArmed = false, controlActive = false) }
}

class RobotArmControllerTest {
    private val origin = RobotTarget("192.168.2.1", "12345678")
    private fun controller(scope: CoroutineScope, hold: () -> Boolean = { true },
        change: suspend (RobotArmService, suspend () -> Unit) -> Unit = { _, action -> action() }) =
        RobotArmController(scope, change, { true }, { hold() })

    @Test fun visibleSurfaceStartsReadOnlyObservationAndClosingItStopsInputsAndIdentification(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val service = ArmServiceFixture(); val c = controller(scope)
        try {
            c.attach(service, origin); assertTrue(service.calls.isEmpty())
            c.setActive(true); assertEquals(listOf("observe:true", "read"), service.calls.toList())
            c.identifyServo(2); assertEquals(2, service.state.value.identifyingServo)
            val pressed = c.press(ArmDirection.FORWARD); assertNotNull(pressed); assertTrue(service.state.value.controlActive)
            c.setActive(false); c.pauseAndJoin()
            assertTrue(c.state.value.held.isEmpty()); assertFalse(service.state.value.controlArmed)
            assertNull(service.state.value.identifyingServo); assertEquals("observe:false", service.calls.last())
        } finally { c.clear(); scope.cancel() }
    }
    @Test fun bindingModeAndConnectionChangesRejectStaleConfirmations(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val old = ArmServiceFixture(); val fresh = ArmServiceFixture(); val c = controller(scope)
        try {
            c.attach(old, origin); c.setActive(true); val snapshot = c.state.value; val expected = snapshot.task.binding!!
            old.state.value = old.state.value.copy(binding = ArmServoBinding(2, 1))
            c.setBinding(ArmServoBinding.Uninstalled, expected, snapshot.connection)
            assertFalse(old.calls.any { it.startsWith("bind:") }); assertNotNull(c.state.value.error)
            c.attach(fresh, origin.copy(ip = "192.168.2.2"))
            c.setBinding(ArmServoBinding.Uninstalled, expected, snapshot.connection)
            assertFalse(fresh.calls.any { it.startsWith("bind:") })
            val next = c.state.value; fresh.state.value = fresh.state.value.copy(loadModeRaw = 1)
            c.setLoadMode(ArmLoadMode.ON_LOAD, next.task.binding!!, next.task.loadModeRaw, next.connection)
            assertFalse(fresh.calls.any { it.startsWith("mode:") })
        } finally { c.clear(); scope.cancel() }
    }
    @Test fun controlsCombineWhileHeldAndAnOldReleaseCannotStopANewerGesture(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val service = ArmServiceFixture(); val c = controller(scope)
        try {
            c.attach(service, origin); c.setActive(true)
            val forward = c.press(ArmDirection.FORWARD)!!; val up = c.press(ArmDirection.UP)!!
            assertEquals(Triple(1.0, 1.0, 0.0), service.inputs.last()); assertEquals(1, service.calls.count { it == "begin" })
            c.release(forward); assertEquals(Triple(0.0, 1.0, 0.0), service.inputs.last())
            c.release(up); assertFalse(service.state.value.controlActive)
            val open = c.press(ArmDirection.OPEN)!!; assertEquals(1.0, service.inputs.last().third)
            c.release(up); assertTrue(service.state.value.controlActive); c.release(open)
            val close = c.press(ArmDirection.CLOSE)!!; assertEquals(-1.0, service.inputs.last().third); c.release(close)
        } finally { c.clear(); scope.cancel() }
    }
    @Test fun nativeRevocationAndFocusLossClearHeldStateAndNeverResumeIt(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val service = ArmServiceFixture(); var canHold = true
        val c = controller(scope, { canHold })
        try {
            c.attach(service, origin); c.setActive(true); val first = c.press(ArmDirection.UP)!!
            service.state.value = service.state.value.copy(controlArmed = false, controlActive = false)
            assertTrue(c.state.value.held.isEmpty()); val count = service.inputs.size
            delay(70); assertEquals(count, service.inputs.size)
            c.release(first); c.press(ArmDirection.OPEN); canHold = false
            withTimeout(1_000) { while (service.state.value.controlArmed) delay(5) }
            val stopped = service.inputs.size; canHold = true; delay(70)
            assertEquals(stopped, service.inputs.size); assertTrue(c.state.value.held.isEmpty())
            assertNotNull(c.press(ArmDirection.DOWN))
        } finally { c.clear(); scope.cancel() }
    }
    @Test fun failedMovementEndsTheGestureAndShowsTheTypedRecoveryMessage(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val service = ArmServiceFixture(); val c = controller(scope)
        try {
            c.attach(service, origin); c.setActive(true); service.failMove = true
            assertNull(c.press(ArmDirection.UP)); assertTrue(c.state.value.held.isEmpty())
            assertEquals(armProblemMessage(ArmProblem.REPORT_STALE), c.state.value.error)
            service.failMove = false; delay(70); assertTrue(service.inputs.isEmpty())
        } finally { c.clear(); scope.cancel() }
    }
    @Test fun leavingDuringAWritePreservesAnUnverifiedHintAndNeverReplaysOnReconnect(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val old = ArmServiceFixture().apply { onWrite = { awaitCancellation() } }
        val fresh = ArmServiceFixture(); val c = controller(scope)
        try {
            c.attach(old, origin); c.setActive(true)
            c.setBinding(ArmServoBinding(2, 1), old.state.value.binding!!); assertTrue(c.state.value.changing)
            c.setActive(false); c.pauseAndJoin()
            assertEquals(origin.ip, c.state.value.interruptedAddress); assertFalse(c.state.value.busy)
            c.detach(); c.attach(fresh, origin); c.setActive(true)
            assertFalse(fresh.calls.any { it.startsWith("bind:") || it.startsWith("mode:") })
            assertEquals(origin.ip, c.state.value.interruptedAddress)
            c.acknowledgePreviousChange(); assertNull(c.state.value.interruptedAddress)
        } finally { c.clear(); scope.cancel() }
    }
    @Test fun cancelledObservationAndLateOldResultsCannotReplaceTheNewBinding(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val old = ArmServiceFixture().apply { onObserve = { entered.complete(Unit); withContext(NonCancellable) { release.await() } } }
        val fresh = ArmServiceFixture().apply { state.value = state.value.copy(binding = ArmServoBinding(3, 1)) }; val c = controller(scope)
        try {
            c.attach(old, origin); c.setActive(true); entered.await()
            c.detach(); c.attach(fresh, origin.copy(ip = "192.168.2.3")); c.setActive(true)
            release.complete(Unit); yield()
            assertEquals(ArmServoBinding(3, 1), c.state.value.task.binding)
            assertEquals(ArmObservation.ACTIVE, fresh.state.value.observation)
            assertEquals("observe:false", old.calls.last())
            assertFalse(fresh.calls.any { it.startsWith("bind:") })
        } finally { release.complete(Unit); c.clear(); scope.cancel() }
    }
    @Test fun onlyClawNeedsNoArmBindingReadAndHiddenControlsCannotWriteOrMove(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = ArmServiceFixture().apply { state.value = state.value.copy(armOnline = false, binding = null, servoIds = emptySet()) }
        val c = controller(scope)
        try {
            c.attach(service, origin); assertNull(c.press(ArmDirection.OPEN))
            c.setActive(true); assertEquals(listOf("observe:true"), service.calls.toList())
            assertNotNull(c.press(ArmDirection.OPEN)); c.setActive(false); c.pauseAndJoin()
            val count = service.calls.size
            c.setLoadMode(ArmLoadMode.ON_LOAD, ArmServoBinding(1, 2), 0)
            c.identifyServo(1); assertNull(c.press(ArmDirection.OPEN)); assertEquals(count, service.calls.size)
        } finally { c.clear(); scope.cancel() }
    }
}
