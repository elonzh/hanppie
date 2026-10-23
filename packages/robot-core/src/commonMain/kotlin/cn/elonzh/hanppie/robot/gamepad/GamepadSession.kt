package cn.elonzh.hanppie.robot.gamepad

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.RobotLock
import cn.elonzh.hanppie.robot.session.robotNanoTime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GamepadPhase { DISCONNECTED, CONNECTING, CONNECTED }
enum class GamepadProblem { NOT_FOUND, PERMISSION_DENIED, OPEN_FAILED, LINK_LOST, TIMEOUT, INVALID_RESPONSE, REJECTED }
data class GamepadButtonEvent(val connection: Long, val number: Long, val receivedAtMillis: Long, val report: GamepadReport)
data class GamepadState(val connection: Long = 0, val reportNumber: Long = 0, val phase: GamepadPhase = GamepadPhase.DISCONNECTED,
    val accessories: List<GamepadAccessory> = emptyList(), val selected: GamepadAccessory? = null,
    val report: GamepadReport? = null, val reportAgeMillis: Long? = null,
    val firmware: String? = null, val activated: Boolean? = null, val activationRead: Boolean = false,
    val querying: Boolean = false, val problem: GamepadProblem? = null) {
    val reportFresh get() = report != null && reportAgeMillis != null && reportAgeMillis in 0..250
}

/** Independent accessory ownership and read-only queries. Connecting never enables robot control. */
class GamepadSession(private val connector: GamepadConnector, private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { robotNanoTime() / 1_000_000 },
    private val requestTimeoutMillis: Long = 1_500) : AutoCloseable {
    private val lock = RobotLock()
    private val mutable = MutableStateFlow(GamepadState())
    val state = mutable.asStateFlow()
    private val edges = MutableSharedFlow<GamepadButtonEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val buttonEvents = edges.asSharedFlow()
    private var reportNumber = 0L
    val available get() = connector.available
    private var epoch = 0L
    private var closed = false
    private var foreground = true
    private var job: Job? = null
    private var transport: GamepadTransport? = null
    private var sequence = 0
    private var reportAt: Long? = null
    private data class Pending(val epoch: Long, val sequence: Int, val id: Int, val reply: CompletableDeferred<ByteArray>)
    private var pending: Pending? = null

    fun refresh() {
        val devices = try { connector.accessories() } catch (_: Exception) { emptyList() }
        lock.withLock { if (!closed) mutable.value = state.value.copy(accessories = devices) }
    }

    fun connect(id: String) {
        disconnect()
        refresh()
        val launch = lock.withLock {
            if (closed || !foreground || !available) return@withLock null
            val device = state.value.accessories.singleOrNull { it.id == id }
            if (device == null) { mutable.value = state.value.copy(problem = GamepadProblem.NOT_FOUND); return@withLock null }
            val generation = epoch
            mutable.value = state.value.copy(connection = generation, phase = GamepadPhase.CONNECTING, selected = device, problem = null)
            scope.launch(start = CoroutineStart.LAZY) { runLink(generation, device.id) }.also { job = it }
        }
        launch?.start()
    }

    private suspend fun runLink(generation: Long, id: String) {
        var opened: GamepadTransport? = null
        try {
            val wire = withTimeout(30_000) { connector.open(id).also { opened = it } }
            val admitted = lock.withLock {
                if (closed || generation != epoch) false else {
                    transport = wire; sequence = 0
                    mutable.value = state.value.copy(phase = GamepadPhase.CONNECTED)
                    true
                }
            }
            if (!admitted) return
            val stream = GamepadFrameStream()
            coroutineScope {
                launch { while (isActive) { delay(100); updateAge() } }
                while (isActive) {
                    val bytes = wire.read() ?: throw IllegalStateException("Accessory stream ended")
                    if (bytes.isEmpty()) { delay(1); continue }
                    for (frame in stream.add(bytes)) receive(generation, frame)
                }
            }
        } catch (_: TimeoutCancellationException) {
            lock.withLock { if (generation == epoch && !closed)
                mutable.value = GamepadState(accessories = state.value.accessories, problem = GamepadProblem.OPEN_FAILED) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            lock.withLock { if (generation == epoch && !closed) {
                mutable.value = GamepadState(accessories = state.value.accessories,
                    problem = if (error is GamepadPermissionDenied) GamepadProblem.PERMISSION_DENIED
                    else if (state.value.phase == GamepadPhase.CONNECTING) GamepadProblem.OPEN_FAILED else GamepadProblem.LINK_LOST)
            } }
        } finally {
            runCatching { opened?.close() }
            lock.withLock { if (generation == epoch) {
                transport = null; reportAt = null
                pending?.reply?.cancel(); pending = null
                if (state.value.phase != GamepadPhase.DISCONNECTED)
                    mutable.value = GamepadState(accessories = state.value.accessories)
            } }
        }
    }

    private fun receive(generation: Long, frame: DussFrame) = lock.withLock {
        if (closed || generation != epoch) return@withLock
        GamepadProtocol.report(frame)?.let {
            val previous = state.value.report
            val at = nowMillis(); reportAt = at
            mutable.value = state.value.copy(reportNumber = ++reportNumber, report = it, reportAgeMillis = 0)
            if (previous == null || previous.buttons != it.buttons || previous.mouseConnected != it.mouseConnected)
                edges.tryEmit(GamepadButtonEvent(generation, reportNumber, at, it))
        }
        val query = pending ?: return@withLock
        if (frame.valid && frame.sender == GamepadProtocol.NODE && frame.receiver == Protocol.HOST_MOBILE &&
            frame.attr == Protocol.ATTR_ACK && frame.set == Protocol.CMDSET_COMMON && frame.id == query.id &&
            frame.sequence == query.sequence && query.epoch == generation) query.reply.complete(frame.payload)
    }

    fun accepts(event: GamepadButtonEvent): Boolean = lock.withLock {
        !closed && state.value.phase == GamepadPhase.CONNECTED && epoch == event.connection &&
            state.value.reportNumber >= event.number && nowMillis() - event.receivedAtMillis in 0..250
    }

    /** Exact monotonic age for a control frame; UI observation updates are deliberately less frequent. */
    fun snapshot(): GamepadState = lock.withLock {
        state.value.copy(reportAgeMillis = reportAt?.let { (nowMillis() - it).coerceAtLeast(0) })
    }

    fun updateAge() = lock.withLock {
        reportAt?.let { mutable.value = state.value.copy(reportAgeMillis = (nowMillis() - it).coerceAtLeast(0)) }
    }

    fun readFirmware() = query(1, byteArrayOf())
    fun readActivation() = query(0x32, byteArrayOf(0x11))

    private fun query(id: Int, payload: ByteArray) {
        val ticket = lock.withLock {
            val wire = transport
            if (closed || !foreground || wire == null || state.value.querying || sequence >= 65535) return@withLock null
            val request = Pending(epoch, ++sequence, id, CompletableDeferred())
            pending = request
            mutable.value = state.value.copy(querying = true, problem = null)
            wire to request
        } ?: return
        scope.launch {
            val (wire, request) = ticket
            try {
                val answer = withTimeout(requestTimeoutMillis) {
                    wire.write(Protocol.duss(receiver = GamepadProtocol.NODE, sequence = request.sequence,
                        attr = Protocol.ATTR_NEED_ACK, set = Protocol.CMDSET_COMMON, id = id, payload = payload))
                    request.reply.await()
                }
                if (answer.isEmpty()) throw IllegalArgumentException("Missing result")
                val problem = if (answer.u8(0) != 0) GamepadProblem.REJECTED else null
                val version = if (problem == null && id == 1) GamepadProtocol.version(answer) else null
                val activation = if (problem == null && id == 0x32) GamepadProtocol.activated(answer) else null
                lock.withLock { if (epoch == request.epoch && !closed) {
                    mutable.value = state.value.copy(firmware = version ?: state.value.firmware,
                        activated = if (id == 0x32 && problem == null) activation else state.value.activated,
                        activationRead = state.value.activationRead || id == 0x32 && problem == null, problem = problem)
                } }
            } catch (_: TimeoutCancellationException) { failQuery(request, GamepadProblem.TIMEOUT) }
            catch (error: CancellationException) { throw error }
            catch (_: IllegalArgumentException) { failQuery(request, GamepadProblem.INVALID_RESPONSE) }
            catch (_: Exception) { failQuery(request, GamepadProblem.LINK_LOST) }
            finally { lock.withLock { if (pending === request) {
                pending = null; mutable.value = state.value.copy(querying = false)
            } } }
        }
    }

    private fun failQuery(request: Pending, problem: GamepadProblem) = lock.withLock {
        if (!closed && epoch == request.epoch) mutable.value = state.value.copy(problem = problem)
    }

    fun disconnect() {
        val previous = lock.withLock {
            epoch++
            val owned = job to transport; job = null; transport = null; reportAt = null
            pending?.reply?.cancel(); pending = null
            mutable.value = GamepadState(accessories = state.value.accessories)
            owned
        }
        runCatching { previous.second?.close() }
        previous.first?.cancel()
    }
    fun setForeground(value: Boolean) {
        lock.withLock { foreground = value }
        if (!value) disconnect()
    }
    override fun close() { lock.withLock { closed = true }; disconnect() }
}
