package cn.elonzh.hanppie.robot.maintenance

import cn.elonzh.hanppie.robot.product.RobotProductProtocol
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

enum class AddressingPhase { IDLE, STARTING, ADDRESSING, DISCOVERING, VERIFYING, ENDING, UNCERTAIN, FINISHED, CLOSED }
enum class AddressingProblem { RESULT_UNKNOWN, MULTIPLE_MODULES, ARM_CHANGED, ARM_RESERVATIONS, WHEEL_REPORT, SKIP_RACED }

data class RobotAddressingState(
    val kind: ModuleAddressingKind? = null,
    val phase: AddressingPhase = AddressingPhase.IDLE,
    val busy: Boolean = false,
    val problem: AddressingProblem? = null,
    val inventoryKnown: Boolean = false,
    val inventoryAgeSeconds: Long = 0,
    val onlineIds: Set<Int> = emptySet(),
    val occupiedIds: Set<Int> = emptySet(),
    val reservedIds: Set<Int> = emptySet(),
    val assignedIds: Set<Int> = emptySet(),
    val candidateId: Int? = null,
    val waitingForQuiet: Boolean = false,
    val wheelStateCode: Int? = null,
    val wheelPercentRaw: Int? = null,
    val wheelTestReady: Boolean = false,
    val wheelHeld: AddressingWheel? = null,
    val wheelDriving: Boolean = false,
    val testedWheels: Set<AddressingWheel> = emptySet(),
    val verifiedWheels: Set<AddressingWheel> = emptySet(),
    val armorSteps: Int = 0,
    val skippedArmorSteps: Set<Int> = emptySet(),
    val identifyingId: Int? = null,
    val identifiedIds: Set<Int> = emptySet(),
    val verifiedIds: Set<Int> = emptySet(),
    val nativeEndAcknowledged: Boolean = false,
) {
    val blocksControl: Boolean get() = kind != null && phase !in setOf(AddressingPhase.IDLE, AddressingPhase.FINISHED, AddressingPhase.CLOSED)
    val wheelPercent: Int? get() = wheelPercentRaw?.takeIf { it in 0..100 }
    val wheelReportedComplete: Boolean get() = wheelStateCode == 1 && wheelPercentRaw == 100
    val selectableIds: Set<Int> get() = when (kind) {
        ModuleAddressingKind.SERVO -> (1..3).toSet()
        ModuleAddressingKind.TOF -> (1..4).toSet()
        else -> emptySet()
    } - (occupiedIds - setOfNotNull(candidateId)) - reservedIds - assignedIds
}

interface RobotAddressingService {
    val state: StateFlow<RobotAddressingState>
    suspend fun begin(kind: ModuleAddressingKind)
    suspend fun assign(id: Int, candidate: Int)
    suspend fun skipArmor(step: Int)
    /** Ends native discovery/encoding before the user checks the physical modules. Never starts motion. */
    suspend fun prepareVerification()
    suspend fun identify(id: Int)
    fun holdWheel(wheel: AddressingWheel?)
    fun renewWheel()
    fun confirmWheel(wheel: AddressingWheel)
    /** Records the operator's observation; it is not inferred from a command acknowledgement. */
    fun confirmModule(id: Int)
    suspend fun end()
    /** Explicit cleanup of a task from an interrupted connection; never replays its start or assignment. */
    suspend fun recover(kind: ModuleAddressingKind)
}

/** One native task per connection. [lock] is also the session's send lock, so a late tick cannot undo a stop. */
internal class AddressingCommands(
    private val lock: RobotLock,
    private val inventory: () -> RobotHealth,
    request: suspend (Int, Int, Int, ByteArray) -> ByteArray,
    private val send: (Int, Int, Int, ByteArray) -> Unit,
    private val nowMillis: () -> Long = { robotNanoTime() / 1_000_000 },
) : RobotAddressingService {
    private val protocol = ModuleAddressingProtocol(request)
    private val access = Mutex()
    private val mutable = MutableStateFlow(RobotAddressingState())
    override val state = mutable.asStateFlow()
    private var closed = false
    private var acceptingReports = false
    private var acceptingInventory = false
    private var armPresent = false
    private val armorSignals = linkedSetOf<ArmorAddressingSignal>()
    private var skipping = false
    private var skipRaced = false
    private var wheelArmed = false
    private var wheelLease = 0L
    private var wheelHardLimit = 0L
    private var zeroRemaining = 0
    private var light: Pair<ModuleAddressingKind, Int>? = null
    private var lightUntil = 0L
    private var inventoryRevision = 0L

    override suspend fun begin(kind: ModuleAddressingKind) = task {
        lock.withLock {
            check(!state.value.blocksControl) { "请先结束当前编址任务" }
            val health = inventory()
            if (kind in setOf(ModuleAddressingKind.SERVO, ModuleAddressingKind.TOF, ModuleAddressingKind.SENSOR_ADAPTER))
                check(health.modules != null) { "尚未收到工作模块报告" }
            check(light == null) { "请等待识别灯结束" }
            armPresent = health.modules.orEmpty().any { it.deviceId == 0x031e }
            acceptingReports = false; acceptingInventory = false; armorSignals.clear()
            skipping = false; skipRaced = false; quietObserved = false; zeroRemaining = 0
            mutable.value = RobotAddressingState(kind, AddressingPhase.STARTING, busy = true,
                inventoryKnown = health.modules != null, inventoryAgeSeconds = health.moduleReportAgeSeconds,
                onlineIds = onlineIds(health, kind), occupiedIds = onlineIds(health, kind))
        }
        var mayHaveWritten = false
        try {
            if (kind == ModuleAddressingKind.SERVO && armPresent) {
                val ids = protocol.armServoIds()
                if (ids.main !in 1..3 || ids.secondary !in 1..3 || ids.main == ids.secondary) {
                    lock.withLock { ensureOpen(); mutable.value = state.value.copy(problem = AddressingProblem.ARM_RESERVATIONS) }
                    error("机械臂占用编号未知，请先核对机械臂")
                }
                lock.withLock { ensureOpen(); mutable.value = state.value.copy(reservedIds = setOf(ids.main, ids.secondary)) }
            }
            currentCoroutineContext().ensureActive()
            lock.withLock {
                ensureOpen()
                check(kind != ModuleAddressingKind.SERVO || armPresent == inventory().modules.orEmpty().any { it.deviceId == 0x031e }) {
                    "机械臂连接已变化，请重新开始"
                }
            }
            if (kind != ModuleAddressingKind.SENSOR_ADAPTER) {
                mayHaveWritten = true
                protocol.begin(kind) { lock.withLock { ensureOpen(); acceptingReports = true; acceptingInventory = true } }
            }
            currentCoroutineContext().ensureActive()
            lock.withLock {
                ensureOpen()
                if (state.value.phase != AddressingPhase.UNCERTAIN) mutable.value = state.value.copy(
                    phase = when (kind) {
                        ModuleAddressingKind.SERVO, ModuleAddressingKind.TOF -> AddressingPhase.DISCOVERING
                        ModuleAddressingKind.SENSOR_ADAPTER -> AddressingPhase.VERIFYING
                        ModuleAddressingKind.ARMOR -> if (state.value.armorSteps == 4) AddressingPhase.VERIFYING else AddressingPhase.ADDRESSING
                        ModuleAddressingKind.WHEELS -> AddressingPhase.ADDRESSING
                    }, waitingForQuiet = kind in setOf(ModuleAddressingKind.SERVO, ModuleAddressingKind.TOF) && !quietObserved)
            }
        } catch (error: Exception) {
            lock.withLock {
                if (!closed) mutable.value = state.value.copy(phase = if (mayHaveWritten) AddressingPhase.UNCERTAIN else AddressingPhase.FINISHED,
                    problem = state.value.problem ?: if (mayHaveWritten) AddressingProblem.RESULT_UNKNOWN else null)
            }
            throw error
        }
    }

    private var quietObserved = false

    override suspend fun assign(id: Int, candidate: Int) = task {
        val kind = lock.withLock {
            val s = state.value
            check(s.phase == AddressingPhase.DISCOVERING && s.candidateId == candidate && id in s.selectableIds) { "请先逐个接入模块并选择未占用编号" }
            acceptingInventory = false
            checkNotNull(s.kind)
        }
        try {
            protocol.assign(kind, id)
            currentCoroutineContext().ensureActive()
            lock.withLock {
                ensureOpen()
                mutable.value = state.value.copy(assignedIds = state.value.assignedIds + id, candidateId = null)
                if (state.value.onlineIds.size > 1) uncertainLocked(AddressingProblem.MULTIPLE_MODULES)
                check(state.value.phase != AddressingPhase.UNCERTAIN) { "模块连接已变化，请先结束任务" }
            }
            delay(300)
            lock.withLock { ensureOpen(); quietObserved = false; mutable.value = state.value.copy(waitingForQuiet = true) }
            protocol.begin(kind) { lock.withLock { ensureOpen(); acceptingInventory = true } }
        } catch (error: Exception) { uncertain(); throw error }
    }

    override suspend fun skipArmor(step: Int) = task {
        lock.withLock {
            check(state.value.kind == ModuleAddressingKind.ARMOR && state.value.phase == AddressingPhase.ADDRESSING && state.value.armorSteps == step) { "当前不能跳过装甲" }
            skipping = true; skipRaced = false
        }
        try {
            protocol.skipArmor()
            currentCoroutineContext().ensureActive()
            lock.withLock {
                ensureOpen()
                check(!skipRaced) { "跳过期间收到敲击报告，步骤未确认，请结束后重新核对" }
                val next = state.value.armorSteps + 1
                mutable.value = state.value.copy(armorSteps = next, skippedArmorSteps = state.value.skippedArmorSteps + next,
                    phase = if (next == 4) AddressingPhase.VERIFYING else AddressingPhase.ADDRESSING)
            }
        } catch (error: Exception) { uncertain(); throw error }
        finally { lock.withLock { skipping = false } }
    }

    override suspend fun prepareVerification() = task {
        val before = lock.withLock { inventoryRevision }
        val kind = lock.withLock {
            val s = state.value
            check(s.kind in setOf(ModuleAddressingKind.WHEELS, ModuleAddressingKind.SERVO, ModuleAddressingKind.TOF)) { "此任务没有此核对步骤" }
            check(if (s.kind == ModuleAddressingKind.WHEELS) s.phase == AddressingPhase.ADDRESSING && s.wheelReportedComplete
                else s.phase == AddressingPhase.DISCOVERING) { "尚未满足核对条件" }
            acceptingReports = false; acceptingInventory = false
            checkNotNull(s.kind)
        }
        try {
            protocol.end(kind)
            if (kind == ModuleAddressingKind.WHEELS) { protocol.enterWheelTestMode(); protocol.stopWheelTest() }
            currentCoroutineContext().ensureActive()
            lock.withLock {
                ensureOpen(); check(state.value.phase != AddressingPhase.UNCERTAIN) { "模块连接已变化，请结束任务" }
                mutable.value = state.value.copy(phase = AddressingPhase.VERIFYING, candidateId = null,
                    waitingForQuiet = false, nativeEndAcknowledged = true, wheelTestReady = kind == ModuleAddressingKind.WHEELS,
                    inventoryKnown = if (kind == ModuleAddressingKind.WHEELS) state.value.inventoryKnown else inventoryRevision > before,
                    onlineIds = if (kind == ModuleAddressingKind.WHEELS || inventoryRevision > before) state.value.onlineIds else emptySet())
            }
        } catch (error: Exception) { uncertain(); throw error }
    }

    override suspend fun identify(id: Int) = task {
        val kind = lock.withLock {
            val s = state.value
            check(s.phase == AddressingPhase.VERIFYING && s.inventoryKnown && s.kind in setOf(ModuleAddressingKind.SERVO, ModuleAddressingKind.TOF) && id in s.onlineIds) {
                "请先结束编号并等待模块上线"
            }
            check(light == null) { "识别灯尚未结束" }
            checkNotNull(s.kind).also {
                light = it to id; lightUntil = nowMillis() + 3_000
                mutable.value = s.copy(identifyingId = id)
            }
        }
        // Even a lost acknowledgement leaves a timed restoration attempt armed on the same connection.
        protocol.setIdentificationLight(kind, id, true)
        currentCoroutineContext().ensureActive()
        lock.withLock { ensureOpen(); mutable.value = state.value.copy(identifiedIds = state.value.identifiedIds + id) }
    }

    override fun holdWheel(wheel: AddressingWheel?) = lock.withLock {
        ensureOpen()
        if (wheel == null) { stopWheelLocked(); return@withLock }
        val s = state.value
        check(s.kind == ModuleAddressingKind.WHEELS && s.phase == AddressingPhase.VERIFYING && s.wheelTestReady && !s.busy) { "请先进入轮位核对" }
        if (s.wheelHeld != null) return@withLock
        val now = nowMillis(); wheelLease = now + 250; wheelHardLimit = now + 1_000; wheelArmed = true; zeroRemaining = 0
        mutable.value = s.copy(wheelHeld = wheel)
    }
    override fun renewWheel() = lock.withLock {
        if (closed || !wheelArmed) return@withLock
        val now = nowMillis()
        if (now >= wheelLease || now >= wheelHardLimit) { wheelArmed = false; return@withLock }
        wheelLease = now + 250
    }
    override fun confirmWheel(wheel: AddressingWheel) = lock.withLock {
        ensureOpen(); val s = state.value
        check(s.phase == AddressingPhase.VERIFYING && !s.busy && !s.wheelDriving && s.wheelHeld == null && wheel in s.testedWheels) { "请先停止测试并核对实际转动的车轮" }
        mutable.value = s.copy(verifiedWheels = s.verifiedWheels + wheel)
    }
    override fun confirmModule(id: Int) = lock.withLock {
        ensureOpen(); val s = state.value
        check(s.phase == AddressingPhase.VERIFYING && !s.busy && id in s.onlineIds &&
            (s.kind == ModuleAddressingKind.SENSOR_ADAPTER || id in s.identifiedIds && s.identifyingId == null)) {
            "请先核对该在线模块及指示灯"
        }
        mutable.value = s.copy(verifiedIds = s.verifiedIds + id)
    }

    override suspend fun end() = task {
        val kind = lock.withLock { state.value.kind.takeIf { state.value.blocksControl } } ?: return@task
        finish(kind)
    }
    override suspend fun recover(kind: ModuleAddressingKind) = task {
        lock.withLock {
            check(!state.value.blocksControl) { "请先结束当前任务" }
            mutable.value = RobotAddressingState(kind, AddressingPhase.ENDING, busy = true)
        }
        finish(kind)
    }
    private suspend fun finish(kind: ModuleAddressingKind) {
        currentCoroutineContext().ensureActive()
        val commands = lock.withLock {
            ensureOpen(); acceptingReports = false; acceptingInventory = false; runCatching { stopWheelLocked() }
            mutable.value = state.value.copy(phase = AddressingPhase.ENDING, problem = null)
            listOfNotNull(light?.let { identificationLightCommand(it.first, it.second, false) }) + addressingEndCommands(kind)
        }
        var failure: Exception? = null
        // A rejected encoder stop must not prevent wheel zero and FPV release from being attempted.
        withContext(NonCancellable) {
            for (command in commands) try { protocol.execute(command) } catch (error: Exception) { if (failure == null) failure = error }
        }
        lock.withLock {
            if (!closed) {
                if (failure == null) light = null
                if (failure == null) zeroRemaining = 0
                mutable.value = state.value.copy(phase = if (failure == null) AddressingPhase.FINISHED else AddressingPhase.UNCERTAIN,
                    identifyingId = light?.second, problem = if (failure == null) null else AddressingProblem.RESULT_UNKNOWN,
                    wheelTestReady = false, nativeEndAcknowledged = failure == null && kind != ModuleAddressingKind.SENSOR_ADAPTER)
            }
        }
        failure?.let { throw it }
    }

    /** Called after the health store consumes this frame, while holding the same session lock. */
    fun receive(frame: DussFrame) = lock.withLock {
        if (closed) return@withLock
        if (frame.valid && frame.sender == 0x28 && frame.receiver == 2 && frame.attr == 0 && RobotProductProtocol.capabilities(frame) != null)
            observeInventoryLocked()
        if (!acceptingReports) return@withLock
        val s = state.value
        if (s.kind == ModuleAddressingKind.WHEELS) wheelEncodingReport(frame)?.let { report ->
            mutable.value = state.value.copy(wheelStateCode = report.state, wheelPercentRaw = report.percentRaw)
            if (report.state != 1 || report.percent == null) uncertainLocked(AddressingProblem.WHEEL_REPORT)
        }
        if (s.kind == ModuleAddressingKind.ARMOR && s.phase in setOf(AddressingPhase.STARTING, AddressingPhase.ADDRESSING))
            armorAddressingSignal(frame)?.takeIf { it.raw != 0 }?.let { signal ->
                if (!armorSignals.add(signal)) return@let
                if (armorSignals.size > 64) armorSignals.remove(armorSignals.first())
                if (skipping) { skipRaced = true; uncertainLocked(AddressingProblem.SKIP_RACED); return@let }
                val next = (state.value.armorSteps + 1).coerceAtMost(4)
                mutable.value = state.value.copy(armorSteps = next,
                    phase = if (next == 4 && s.phase != AddressingPhase.STARTING) AddressingPhase.VERIFYING else state.value.phase)
            }
    }

    private fun observeInventoryLocked() {
        inventoryRevision++
        val s = state.value; val kind = s.kind ?: return; val health = inventory()
        val ids = onlineIds(health, kind)
        mutable.value = s.copy(inventoryKnown = health.modules != null, inventoryAgeSeconds = health.moduleReportAgeSeconds, onlineIds = ids,
            verifiedIds = s.verifiedIds.intersect(ids), identifiedIds = s.identifiedIds.intersect(ids))
        if (kind == ModuleAddressingKind.SERVO && s.blocksControl && armPresent != health.modules.orEmpty().any { it.deviceId == 0x031e }) {
            uncertainLocked(AddressingProblem.ARM_CHANGED); return
        }
        if (!acceptingInventory || s.phase !in setOf(AddressingPhase.STARTING, AddressingPhase.DISCOVERING)) return
        if (ids.isEmpty()) { quietObserved = true; mutable.value = state.value.copy(waitingForQuiet = false, candidateId = null) }
        else if (quietObserved) {
            if (ids.size > 1) uncertainLocked(AddressingProblem.MULTIPLE_MODULES)
            else mutable.value = state.value.copy(candidateId = ids.single(), waitingForQuiet = false)
        }
    }

    /** Only this tick emits a positive wheel command. Renewing a lease cannot restart an expired press. */
    fun tick(fresh: Boolean) = lock.withLock {
        if (closed) return@withLock
        val now = nowMillis(); val s = state.value
        if (s.kind != null) mutable.value = s.copy(inventoryAgeSeconds = inventory().moduleReportAgeSeconds)
        if (light != null && now >= lightUntil) {
            val current = checkNotNull(light)
            emit(identificationLightCommand(current.first, current.second, false))
            light = null; mutable.value = state.value.copy(identifyingId = null)
        }
        if (!fresh || now >= wheelLease || now >= wheelHardLimit) wheelArmed = false
        val wheel = state.value.wheelHeld
        val driving = wheelArmed && fresh && state.value.phase == AddressingPhase.VERIFYING && state.value.wheelTestReady && wheel != null
        if (driving && !state.value.wheelDriving) {
            mutable.value = state.value.copy(wheelDriving = true, testedWheels = state.value.testedWheels + checkNotNull(wheel))
            send(3, 0x3f, 0x20, wheelAddressingTestPayload(wheel))
        } else if (!driving && state.value.wheelDriving) {
            mutable.value = state.value.copy(wheelDriving = false); zeroRemaining = 3
        }
        if (zeroRemaining > 0) { send(3, 0x3f, 0x20, wheelAddressingTestPayload(null)); zeroRemaining-- }
    }
    fun stopWheel() = lock.withLock { if (!closed) stopWheelLocked() }
    private fun stopWheelLocked() {
        val wasMoving = state.value.wheelDriving
        wheelArmed = false; wheelLease = 0; wheelHardLimit = 0
        mutable.value = state.value.copy(wheelHeld = null, wheelDriving = false)
        if (wasMoving || state.value.wheelTestReady) { zeroRemaining = 2; send(3, 0x3f, 0x20, wheelAddressingTestPayload(null)) }
    }
    /** Invoked before the socket is retired. Every cleanup is an attempt, never an acknowledged result. */
    fun close() = lock.withLock {
        if (closed) return@withLock
        val s = state.value
        val commands = listOfNotNull(light?.let { identificationLightCommand(it.first, it.second, false) }) +
            if (s.blocksControl) addressingEndCommands(checkNotNull(s.kind)) else emptyList()
        closed = true; protocol.close(); wheelArmed = false; acceptingReports = false; acceptingInventory = false; light = null
        mutable.value = s.copy(phase = AddressingPhase.CLOSED, busy = false, wheelHeld = null, wheelDriving = false,
            identifyingId = null, problem = if (s.blocksControl) AddressingProblem.RESULT_UNKNOWN else s.problem)
        commands.forEach { runCatching { emit(it) } }
    }
    private fun emit(command: AddressingCommand) = send(command.node, command.set, command.id, command.payload)
    private fun ensureOpen() { if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED) }
    private fun uncertain() = lock.withLock { if (!closed) uncertainLocked(AddressingProblem.RESULT_UNKNOWN) }
    private fun uncertainLocked(problem: AddressingProblem) {
        wheelArmed = false; acceptingReports = false; acceptingInventory = false
        mutable.value = state.value.copy(phase = AddressingPhase.UNCERTAIN, problem = state.value.problem ?: problem, candidateId = null)
    }
    private suspend fun task(action: suspend () -> Unit) {
        check(access.tryLock()) { "编址请求正在进行" }
        try {
            lock.withLock { ensureOpen(); mutable.value = state.value.copy(busy = true) }
            action()
        } finally {
            lock.withLock { if (!closed) mutable.value = state.value.copy(busy = false) }
            access.unlock()
        }
    }
}

private fun onlineIds(health: RobotHealth, kind: ModuleAddressingKind): Set<Int> = health.modules.orEmpty().mapNotNull { device ->
    when (kind) {
        ModuleAddressingKind.SERVO -> device.deviceId.takeIf { it in 0x0318..0x031b }?.let { it - 0x0317 }
        ModuleAddressingKind.TOF -> device.deviceId.takeIf { it in 0x1201..0x1204 }?.let { it - 0x1200 }
        ModuleAddressingKind.SENSOR_ADAPTER -> device.deviceId.takeIf { it in 0x1601..0x1606 }?.let { it - 0x1600 }
        else -> null
    }
}.toSet()
