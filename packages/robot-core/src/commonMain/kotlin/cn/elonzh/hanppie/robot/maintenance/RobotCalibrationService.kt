package cn.elonzh.hanppie.robot.maintenance

import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

enum class CalibrationKind { CHASSIS_AND_GIMBAL, GIMBAL, ARM }
enum class CalibrationPhase { IDLE, STARTING, CHASSIS, WAITING_LEVEL, GIMBAL, ARM_ACKNOWLEDGED, SUCCEEDED, FAILED, CANCELING, CANCELED, UNCERTAIN, CLOSED }
enum class CalibrationProblem { REQUEST_UNKNOWN, REPORT_UNKNOWN, REPORT_STALE, GIMBAL_TIMEOUT, STOP_UNCONFIRMED, MODULE_CHANGED }

data class RobotCalibrationState(
    val kind: CalibrationKind? = null,
    val phase: CalibrationPhase = CalibrationPhase.IDLE,
    val busy: Boolean = false,
    val problem: CalibrationProblem? = null,
    val imuStatusRaw: Int? = null,
    val imuProgressRaw: Int? = null,
    val imuSideRaw: Int? = null,
    val finishedMaskRaw: Int? = null,
    val gimbalStatusRaw: Int? = null,
    val gimbalProgressRaw: Int? = null,
    val reportAgeSeconds: Long? = null,
    val levelReady: Boolean = false,
    val needsGimbal: Boolean = false,
    val requestAcknowledged: Boolean = false,
    val stopAcknowledged: Boolean = false,
    val stopRequested: Boolean = false,
    val observingInterrupted: Boolean = false,
    val gimbalMayBeActive: Boolean = false,
    val restartConfirmedByOperator: Boolean = false,
) {
    val imuSide: ImuCalibrationSide? get() = ImuCalibrationSide.fromWire(imuSideRaw)
    val imuProgress: Int? get() = imuProgressRaw?.takeIf { it in 0..100 }
    val gimbalProgress: Int? get() = gimbalProgressRaw?.takeIf { it in 0..100 }
    val finishedSides: Set<ImuCalibrationSide> get() = ImuCalibrationSide.entries.filterTo(mutableSetOf()) {
        (finishedMaskRaw ?: 0) and it.mask != 0
    }
    val canStopImu: Boolean get() = blocksControl && kind == CalibrationKind.CHASSIS_AND_GIMBAL && !gimbalMayBeActive
    val blocksControl: Boolean get() = kind != null && phase !in setOf(CalibrationPhase.IDLE, CalibrationPhase.ARM_ACKNOWLEDGED,
        CalibrationPhase.SUCCEEDED, CalibrationPhase.FAILED, CalibrationPhase.CANCELED, CalibrationPhase.CLOSED)
}

interface RobotCalibrationService {
    val state: StateFlow<RobotCalibrationState>
    suspend fun begin(kind: CalibrationKind)
    /** Combined calibration requires a fresh, stable horizontal report before starting the moving gimbal. */
    suspend fun startGimbal()
    /** A chassis stop cannot be presented as a gimbal cancel or as undoing the arm's zero write. */
    suspend fun cancel()
    /** Explicitly repeats the one-shot zero write after the operator restores the arm's calibration pose. */
    suspend fun retryArm()
    /** Records an operator-confirmed physical restart after reconnecting; it is not a native stop ACK. */
    fun confirmRestarted()
    /** Observes a previously interrupted task; it never replays a calibration command. */
    suspend fun observeInterrupted(previous: RobotCalibrationState)
}

/** Session-owned task rules. Admission against other robot tasks belongs to the session boundary. */
internal class CalibrationCommands(
    private val lock: RobotLock,
    private val inventory: () -> RobotHealth,
    request: suspend (Int, Int, Int, ByteArray) -> ByteArray,
    private val send: (Int, Int, Int, ByteArray) -> Unit,
    private val nowMillis: () -> Long = { robotNanoTime() / 1_000_000 },
) : RobotCalibrationService {
    private val protocol = CalibrationProtocol(request)
    private val access = Mutex()
    private val mutable = MutableStateFlow(RobotCalibrationState())
    override val state = mutable.asStateFlow()
    private var closed = false
    private var acceptImu = false
    private var acceptGimbal = false
    private var imuStarted = false
    private var gimbalStarted = false
    private var lastReport: Long? = null
    private var levelSince: Long? = null
    private var gimbalSince: Long? = null

    override suspend fun begin(kind: CalibrationKind) = task {
        lock.withLock {
            check(!state.value.blocksControl) { "请先结束当前校准任务" }
            val modules = checkNotNull(inventory().modules) { "请先等待工作模块报告" }
            val gimbal = modules.any { it.deviceId == RobotComponent.GIMBAL.deviceId }
            check(kind != CalibrationKind.GIMBAL || gimbal) { "云台未在线" }
            check(kind != CalibrationKind.ARM || modules.any { it.deviceId == RobotComponent.ARM.deviceId }) { "机械臂未在线" }
            acceptImu = false; acceptGimbal = false; imuStarted = false; gimbalStarted = false
            lastReport = null; levelSince = null; gimbalSince = null
            mutable.value = RobotCalibrationState(kind, CalibrationPhase.STARTING, busy = true,
                needsGimbal = kind == CalibrationKind.CHASSIS_AND_GIMBAL && gimbal)
        }
        try {
            when (kind) {
                CalibrationKind.CHASSIS_AND_GIMBAL -> {
                    protocol.stopImu()
                    lock.withLock { ensureOpen(); acceptImu = true; mutable.value = state.value.copy(phase = CalibrationPhase.CHASSIS) }
                    protocol.startImu()
                }
                CalibrationKind.GIMBAL -> {
                    lock.withLock { armGimbalLocked() }
                    protocol.startGimbal()
                }
                CalibrationKind.ARM -> protocol.calibrateArm()
            }
            currentCoroutineContext().ensureActive()
            lock.withLock {
                ensureOpen()
                mutable.value = state.value.copy(requestAcknowledged = true,
                    phase = if (kind == CalibrationKind.ARM) CalibrationPhase.ARM_ACKNOWLEDGED else state.value.phase)
            }
        } catch (error: Exception) { unknown(CalibrationProblem.REQUEST_UNKNOWN); throw error }
    }

    override suspend fun startGimbal() = task {
        lock.withLock {
            val s = state.value
            check(s.kind == CalibrationKind.CHASSIS_AND_GIMBAL && s.phase == CalibrationPhase.WAITING_LEVEL &&
                !s.stopRequested && s.levelReady) { "请将机器人放回水平面并等待稳定报告" }
            // Recheck the clock when the action is executed, not only when a UI button was enabled.
            check(lastReport?.let { nowMillis() - it <= 3_000 } == true && levelSince?.let { nowMillis() - it >= 2_000 } == true)
            if (inventory().modules?.any { it.deviceId == RobotComponent.GIMBAL.deviceId } != true) {
                unknownLocked(CalibrationProblem.MODULE_CHANGED)
                error("云台连接已变化，请核对机器人")
            }
            armGimbalLocked()
        }
        try {
            protocol.startGimbal(); currentCoroutineContext().ensureActive()
            lock.withLock { ensureOpen(); mutable.value = state.value.copy(requestAcknowledged = true) }
        } catch (error: Exception) { unknown(CalibrationProblem.REQUEST_UNKNOWN); throw error }
    }

    override suspend fun cancel() = task {
        val canStop = lock.withLock {
            val s = state.value
            if (!s.blocksControl) null else {
                mutable.value = s.copy(stopRequested = true, levelReady = false)
                s.kind == CalibrationKind.CHASSIS_AND_GIMBAL && !s.gimbalMayBeActive
            }
        } ?: return@task
        check(canStop) {
            unknown(CalibrationProblem.STOP_UNCONFIRMED)
            "没有已确认的独立取消指令，请等待机内校准终态"
        }
        lock.withLock { mutable.value = state.value.copy(phase = CalibrationPhase.CANCELING); acceptImu = false }
        try {
            protocol.stopImu(); currentCoroutineContext().ensureActive()
            lock.withLock {
                ensureOpen(); mutable.value = state.value.copy(phase = CalibrationPhase.CANCELED, stopAcknowledged = true, problem = null)
            }
        } catch (error: Exception) { unknown(CalibrationProblem.STOP_UNCONFIRMED); throw error }
    }

    override suspend fun retryArm() = task {
        lock.withLock {
            val s = state.value
            check(s.kind == CalibrationKind.ARM && s.phase == CalibrationPhase.UNCERTAIN) { "没有待确认的机械臂校准" }
            check(inventory().modules?.any { it.deviceId == RobotComponent.ARM.deviceId } == true) { "机械臂未在线" }
            mutable.value = s.copy(phase = CalibrationPhase.STARTING, problem = null, requestAcknowledged = false)
        }
        try {
            protocol.calibrateArm(); currentCoroutineContext().ensureActive()
            lock.withLock { ensureOpen(); mutable.value = state.value.copy(phase = CalibrationPhase.ARM_ACKNOWLEDGED, requestAcknowledged = true) }
        } catch (error: Exception) { unknown(CalibrationProblem.REQUEST_UNKNOWN); throw error }
    }

    override fun confirmRestarted() = lock.withLock {
        ensureOpen()
        val s = state.value
        check(s.observingInterrupted && s.phase == CalibrationPhase.UNCERTAIN && !s.busy && s.imuStatusRaw != 3 && s.gimbalStatusRaw != 1) { "只可在重新连接原机器人后确认物理重启" }
        finishLocked(CalibrationPhase.CANCELED)
        mutable.value = state.value.copy(restartConfirmedByOperator = true, stopAcknowledged = false)
    }

    override suspend fun observeInterrupted(previous: RobotCalibrationState) = lock.withLock {
        ensureOpen(); check(!state.value.blocksControl && !state.value.busy) { "请先结束当前校准任务" }
        val kind = checkNotNull(previous.kind)
        check(previous.blocksControl || previous.phase == CalibrationPhase.CLOSED && previous.problem != null)
        acceptImu = kind == CalibrationKind.CHASSIS_AND_GIMBAL && !previous.gimbalMayBeActive
        acceptGimbal = previous.gimbalMayBeActive
        imuStarted = false; gimbalStarted = false; lastReport = null; levelSince = null; gimbalSince = null
        mutable.value = previous.copy(phase = CalibrationPhase.UNCERTAIN, busy = false, problem = CalibrationProblem.REQUEST_UNKNOWN,
            observingInterrupted = true, stopRequested = true, levelReady = false, reportAgeSeconds = null,
            requestAcknowledged = false, stopAcknowledged = false, imuStatusRaw = null, imuProgressRaw = null,
            imuSideRaw = null, finishedMaskRaw = null, gimbalStatusRaw = null, gimbalProgressRaw = null)
    }

    private fun armGimbalLocked() {
        ensureOpen(); acceptImu = false; acceptGimbal = true; gimbalStarted = false
        lastReport = null; gimbalSince = nowMillis(); levelSince = null
        mutable.value = state.value.copy(phase = CalibrationPhase.GIMBAL, levelReady = false, reportAgeSeconds = null,
            requestAcknowledged = false, gimbalMayBeActive = true, gimbalStatusRaw = null, gimbalProgressRaw = null)
    }

    fun receive(frame: DussFrame) = lock.withLock {
        if (closed) return@withLock
        if (!state.value.blocksControl) {
            if (!state.value.restartConfirmedByOperator) return@withLock
            val imuBusy = imuCalibrationReport(frame)?.status == ImuCalibrationStatus.IN_PROGRESS
            val gimbalBusy = gimbalCalibrationReport(frame)?.status == GimbalCalibrationStatus.IN_PROGRESS
            if (!imuBusy && !gimbalBusy) return@withLock
            // A contradictory device report takes priority over the earlier operator observation.
            acceptImu = imuBusy; acceptGimbal = gimbalBusy
            mutable.value = state.value.copy(phase = CalibrationPhase.UNCERTAIN, problem = CalibrationProblem.REPORT_UNKNOWN,
                gimbalMayBeActive = gimbalBusy, restartConfirmedByOperator = false)
        }
        val s = state.value
        if (acceptImu) imuCalibrationReport(frame)?.let { report ->
            val now = nowMillis(); lastReport = now
            mutable.value = state.value.copy(imuStatusRaw = report.statusRaw, imuProgressRaw = report.progressRaw,
                imuSideRaw = report.sideRaw, finishedMaskRaw = report.finishedMaskRaw, reportAgeSeconds = 0)
            if (report.status == ImuCalibrationStatus.WAITING || report.status == ImuCalibrationStatus.IN_PROGRESS) imuStarted = true
            when (report.status) {
                ImuCalibrationStatus.FINISHED -> if (s.observingInterrupted) finishLocked(CalibrationPhase.CANCELED)
                    else if (imuStarted) {
                    if (s.needsGimbal) {
                        mutable.value = state.value.copy(phase = CalibrationPhase.WAITING_LEVEL, problem = null)
                        if (report.side == ImuCalibrationSide.HORIZONTAL) { if (levelSince == null) levelSince = now }
                        else levelSince = null
                    } else finishLocked(CalibrationPhase.SUCCEEDED)
                }
                ImuCalibrationStatus.FAILED -> finishLocked(CalibrationPhase.FAILED)
                ImuCalibrationStatus.UNKNOWN -> unknownLocked(CalibrationProblem.REPORT_UNKNOWN)
                else -> Unit
            }
            if (report.side != ImuCalibrationSide.HORIZONTAL || report.status != ImuCalibrationStatus.FINISHED) {
                levelSince = null; mutable.value = state.value.copy(levelReady = false)
            }
        }
        if (acceptGimbal) gimbalCalibrationReport(frame)?.let { report ->
            lastReport = nowMillis()
            mutable.value = state.value.copy(gimbalStatusRaw = report.statusRaw, gimbalProgressRaw = report.progressRaw, reportAgeSeconds = 0)
            when (report.status) {
                GimbalCalibrationStatus.IN_PROGRESS -> gimbalStarted = true
                GimbalCalibrationStatus.IDLE -> if (s.observingInterrupted) finishLocked(CalibrationPhase.CANCELED)
                    else if (gimbalStarted) finishLocked(CalibrationPhase.SUCCEEDED)
                GimbalCalibrationStatus.FAILED -> finishLocked(CalibrationPhase.FAILED)
                GimbalCalibrationStatus.UNKNOWN -> unknownLocked(CalibrationProblem.REPORT_UNKNOWN)
            }
        }
    }

    fun tick() = lock.withLock {
        if (closed || !state.value.blocksControl) return@withLock
        val now = nowMillis()
        val report = lastReport
        mutable.value = state.value.copy(reportAgeSeconds = report?.let { (now - it).coerceAtLeast(0) / 1_000 },
            levelReady = state.value.phase == CalibrationPhase.WAITING_LEVEL && !state.value.stopRequested &&
                report != null && now - report <= 3_000 && levelSince?.let { now - it >= 2_000 } == true)
        if (state.value.phase == CalibrationPhase.GIMBAL && gimbalSince?.let { now - it >= 100_000 } == true)
            unknownLocked(CalibrationProblem.GIMBAL_TIMEOUT)
        else if (report != null && now - report > 3_000) unknownLocked(CalibrationProblem.REPORT_STALE)
    }

    private fun finishLocked(phase: CalibrationPhase) {
        acceptImu = false; acceptGimbal = false
        mutable.value = state.value.copy(phase = phase, problem = null, levelReady = false)
    }
    private fun unknown(problem: CalibrationProblem) = lock.withLock { if (!closed) unknownLocked(problem) }
    private fun unknownLocked(problem: CalibrationProblem) {
        levelSince = null
        mutable.value = state.value.copy(phase = CalibrationPhase.UNCERTAIN, problem = problem, levelReady = false)
    }
    private fun ensureOpen() { if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED) }
    fun close() = lock.withLock {
        if (closed) return@withLock
        val s = state.value
        val stop = s.blocksControl && s.kind == CalibrationKind.CHASSIS_AND_GIMBAL && !s.gimbalMayBeActive
        closed = true; protocol.close(); acceptImu = false; acceptGimbal = false
        mutable.value = s.copy(phase = CalibrationPhase.CLOSED, busy = false, levelReady = false,
            problem = if (s.blocksControl) CalibrationProblem.REQUEST_UNKNOWN else s.problem)
        if (stop) runCatching { send(3, 3, 0xf9, imuCalibrationCommand(false)) }
    }
    private suspend fun task(action: suspend () -> Unit) {
        check(access.tryLock()) { "校准请求正在进行" }
        try {
            lock.withLock { ensureOpen(); mutable.value = state.value.copy(busy = true) }
            action()
        } finally {
            lock.withLock { if (!closed) mutable.value = state.value.copy(busy = false) }
            access.unlock()
        }
    }
}
