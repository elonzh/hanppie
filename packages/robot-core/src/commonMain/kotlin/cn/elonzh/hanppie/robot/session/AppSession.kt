package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.lab.LabChannel
import cn.elonzh.hanppie.robot.media.SpeakerAudio
import cn.elonzh.hanppie.robot.media.VideoResolution
import cn.elonzh.hanppie.robot.protocol.AppEnvelope
import cn.elonzh.hanppie.robot.protocol.DiscoveredRobot
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.connectionSetup
import cn.elonzh.hanppie.robot.protocol.hexBytes
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.protocol.u16
import cn.elonzh.hanppie.robot.product.RobotModel
import cn.elonzh.hanppie.robot.product.RobotProduct
import cn.elonzh.hanppie.robot.product.RobotProductProtocol
import cn.elonzh.hanppie.robot.remote.RemoteControl
import cn.elonzh.hanppie.robot.remote.remoteEffects
import cn.elonzh.hanppie.robot.remote.remoteExit
import cn.elonzh.hanppie.robot.remote.remoteSetup
import cn.elonzh.hanppie.robot.telemetry.Telemetry
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One owner per robot connection. Blocking UDP is confined to an IO coroutine. */
@OptIn(ExperimentalAtomicApi::class)
class AppSession(private val target: RobotTarget,
                 private val onFrame: (DussFrame) -> Unit = {},
                 private val onLog: (String) -> Unit = {},
                 private val network: RobotTransport = RobotTransport.Default,
                 private val onLost: (String) -> Unit = {}) : AutoCloseable, LabChannel {
    private val random = kotlin.random.Random.Default
    private val envelope = AppEnvelope(random.nextInt(65535) + 1, random.nextInt(8192) * 8)
    private val destination = target.ip
    private var sequence = 10072
    private val txLock = RobotLock()
    private val active = AtomicBoolean(false)
    private var socket: RobotDatagram? = null
    private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var receiver: Job? = null
    @Volatile private var mode = Protocol.MODE_NORMAL
    @Volatile private var labRunning = false
    @Volatile private var remote = false
    @Volatile var product: RobotProduct = RobotProduct()
        private set
    private val modeMutex = Mutex()
    private var controlPayload = RemoteControl.velocity(0.0,0.0,0.0)
    private var gimbalPayload = RemoteControl.gimbalVelocity(0.0, 0.0)
    private var gimbalInput = 0.0 to 0.0
    private var chassisYawInput = 0.0
    private var cameraInput = 0.0 to 0.0
    private var cameraRelative = false
    @Volatile private var yawSample: Pair<Double, Long>? = null
    val cameraYaw: Double? get() = yawSample?.takeIf { robotNanoTime() - it.second < 500_000_000 }?.first
    private var inputDeadline = 0L
    private var triggerDeadline = 0L
    @Volatile var onVideo: ((ByteArray) -> Unit)? = null
    @Volatile var onAudio: ((ByteArray) -> Unit)? = null

    suspend fun enterRemote() = withContext(Dispatchers.IO) {
        modeMutex.withLock {
        check(!labRunning) { "请先停止 Lab 脚本" }
        if (!remote) {
            mode = Protocol.MODE_REMOTE
            for (c in remoteSetup + remoteEffects) {
                if (c.control) sendNeutral() else send(c.receiver, c.attr, c.set, c.id, c.payload.hexBytes(), flags = c.flags.hexBytes())
                delay(10)
            }
            send(Protocol.HOST_CHASSIS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_WORK_MODE_SET, byteArrayOf(1))
            send(Protocol.HOST_CHASSIS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SPEED_MODE_SET, byteArrayOf(0))
            remote = true
            halt()
        }
        }
    }
    fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double, cameraRelative: Boolean = false) = txLock.withLock {
        check(remote && active.load()) { "遥控未启用" }
        if (robotNanoTime() - lastReceivedNanos >= 500_000_000) {
            clearMotionInput()
            runCatching { sendNeutral(forceActuatorStop = true) }
            error("500 毫秒未收到机器人数据，遥控已安全停止")
        }
        controlPayload = RemoteControl.velocity(x, y, z)
        chassisYawInput = z
        gimbalInput = pitch to yaw
        this.cameraRelative = cameraRelative
        cameraInput = x to y
        gimbalPayload = RemoteControl.gimbalVelocity(pitch, yaw)
        inputDeadline = robotNanoTime() + 250_000_000
    }
    fun halt() = txLock.withLock {
        clearMotionInput()
        if (active.load()) sendNeutral()
    }
    /** Best-effort stop for explicit disconnect, link loss, and a newly restored session. */
    fun safetyStop(repetitions: Int = 3) {
        require(repetitions in 1..10)
        txLock.withLock { clearMotionInput() }
        repeat(repetitions) { index ->
            txLock.withLock { if (active.load()) sendNeutral(forceActuatorStop = true) }
            if (index + 1 < repetitions) robotSleep(20)
        }
    }
    private fun clearMotionInput() {
        inputDeadline = 0; triggerDeadline = 0
        controlPayload = RemoteControl.velocity(0.0,0.0,0.0)
        gimbalPayload = RemoteControl.gimbalVelocity(0.0, 0.0)
        cameraInput = 0.0 to 0.0
        gimbalInput = 0.0 to 0.0; chassisYawInput = 0.0
    }
    /** The S1-verified route sends 3f:51 to hdvt_uav_id (900 -> 0x09), not EP's 0x17. */
    suspend fun fireGelOnce(onSent: (Int) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        var fireLedEnabled = false
        var blasterLedEnabled = false
        try {
            val fireSequence = txLock.withLock {
                check(remote && active.load() && !labRunning) { "遥控未启用" }
                send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_LED_COLOR_SET, RemoteControl.muzzleFireLed(true))
                fireLedEnabled = true
                send(Protocol.HOST_GUN, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_GUN_LED_SET, RemoteControl.blasterLed(true))
                blasterLedEnabled = true
                send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SHOOT_CMD, byteArrayOf(1))
            }
            onSent(fireSequence)
            delay(400)
            fireSequence
        } finally {
            if (fireLedEnabled || blasterLedEnabled) txLock.withLock {
                if (active.load()) runCatching {
                    if (blasterLedEnabled) send(Protocol.HOST_GUN, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_GUN_LED_SET, RemoteControl.blasterLed(false))
                    if (fireLedEnabled) send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_LED_COLOR_SET, RemoteControl.muzzleFireLed(false))
                }
            }
        }
    }

    /** Upload a bounded, length-prefixed Opus clip through the S1-verified speaker route. */
    suspend fun playSpeaker(encoded: ByteArray): Int = withContext(Dispatchers.IO) {
        require(encoded.isNotEmpty()) { "对讲录音为空" }
        require(encoded.size <= 0xffff) { "对讲录音过长" }
        val chunks = encoded.asList().chunked(SpeakerAudio.chunkBytes).map { part -> part.toByteArray() }
        txLock.withLock {
            check(remote && active.load() && !labRunning) { "遥控未启用" }
            send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_AUDIO_TRANSFER, SpeakerAudio.start(chunks.size, encoded.size))
            robotSleep(55)
            chunks.forEachIndexed { index, chunk ->
                send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NO_ACK, Protocol.CMDSET_COMMON, Protocol.CMD_FW_TRANSMIT, SpeakerAudio.block(chunk, index))
                if (index + 1 < chunks.size) robotSleep(6)
            }
            robotSleep(55)
            send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_AUDIO_TRANSFER,
                byteArrayOf(2) + robotMd5(encoded))
            robotSleep(107)
            send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_PLAY_SOUND_TASK, SpeakerAudio.playPayload)
        }
        chunks.size
    }
    suspend fun exitRemote() = withContext(Dispatchers.IO) {
        modeMutex.withLock {
        if (!remote) return@withLock
        halt()
        if (remote && connected) for (c in remoteExit) {
            if (c.control) sendNeutral() else send(c.receiver, c.attr, c.set, c.id, c.payload.hexBytes(), flags = c.flags.hexBytes())
            delay(10)
        }
        if (connected) send(Protocol.HOST_CHASSIS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_WORK_MODE_SET, byteArrayOf(0))
        remote = false; normalMode()
        }
    }
    fun fireInfrared() = txLock.withLock {
        check(remote && active.load()) { "遥控未启用" }
        triggerDeadline = robotNanoTime() + 120_000_000
    }
    fun setLed(red: Int, green: Int, blue: Int, enabled: Boolean = true) = txLock.withLock {
        check(active.load()) { "机器人未连接" }
        send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_LED_COLOR_SET, RemoteControl.led(red, green, blue, enabled))
    }
    fun setSpeakerVolume(volume: Int) = txLock.withLock {
        check(active.load()) { "机器人未连接" }
        val clamped = volume.coerceIn(0, 100)
        send(Protocol.HOST_CAMERA, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SET_SPEAKER_VOLUME, byteArrayOf(clamped.toByte()))
    }
    fun media(start: Boolean, audio: Boolean = false, resolution: VideoResolution = VideoResolution.R720P) {
        if (start) send(Protocol.HOST_CAMERA, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_CAMERA, Protocol.CMD_SET_VIDEO_FORMAT, resolution.payload)
        for (control in if (start) listOf(1, 2) else listOf(2, 1))
            send(Protocol.HOST_CAMERA, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_STREAM_CTRL, byteArrayOf(control.toByte(), if (start) 1 else 0, 0))
        if (start && audio) send(Protocol.HOST_CAMERA, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SET_AUDIO_STATUS, byteArrayOf(1))
    }
    @Volatile var lastReceivedNanos: Long = 0; private set
    override val connected: Boolean get() = active.load()

    companion object {
        suspend fun discover(timeoutMillis: Long = 3000, localIp: String = "0.0.0.0", network: RobotTransport = RobotTransport.Default): List<DiscoveredRobot> =
            withContext(Dispatchers.IO) {
                require(timeoutMillis in 1..30000)
                val found = linkedMapOf<String, DiscoveredRobot>()
                network.openDatagram().use { socket ->
                    socket.reuseAddress = true
                    socket.broadcast = true
                    socket.bind(localIp, Protocol.APP_PORT)
                    socket.soTimeout = 200
                    val deadline = robotNanoTime() + timeoutMillis * 1_000_000
                    while (robotNanoTime() < deadline) {
                        coroutineContext.ensureActive()
                                try {
                            val packet = socket.receive(2048) ?: continue
                            Protocol.broadcast(packet.bytes)?.let { decoded ->
                                // The official App routes by the UDP source. The embedded address may still
                                // contain the previous router lease while the robot changes network mode.
                                val sourceIp = packet.ip
                                val robot = decoded.copy(ip = sourceIp)
                                found["${robot.mac}/${robot.appId}"] = robot
                            }
                        } catch (_: RobotReceiveTimeout) { /* bounded passive discovery */ }
                    }
                }
                found.values.toList()
            }

        /** Waits for the first pairing broadcast. The App session must be established before the final ACK. */
        suspend fun waitForRouterPairing(
            appId: String,
            timeoutMillis: Long = 120_000,
            localIp: String = "0.0.0.0",
            network: RobotTransport = RobotTransport.Default,
        ): RouterPairing = withContext(Dispatchers.IO) {
            require(Regex("[0-9a-fA-F]{8}").matches(appId)) { "AppID 必须是 8 位十六进制字符" }
            require(timeoutMillis in 1..360_000)
            val normalizedAppId = appId.lowercase()
            network.openDatagram().use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(localIp, Protocol.APP_PORT)
                socket.soTimeout = 200
                val deadline = robotNanoTime() + timeoutMillis * 1_000_000
                while (robotNanoTime() < deadline) {
                    coroutineContext.ensureActive()
                        try {
                        val packet = socket.receive(2048) ?: continue
                        val decoded = Protocol.broadcast(packet.bytes) ?: continue
                        val sourceIp = packet.ip
                        val robot = decoded.copy(ip = sourceIp)
                        if (!robot.pairing) continue
                        if (!robot.appId.equals(normalizedAppId, ignoreCase = true)) continue
                        return@withContext RouterPairing(robot, packet.port)
                    } catch (_: RobotReceiveTimeout) { /* keep the bounded pairing window open */ }
                }
            }
            error("等待机器人扫描配网二维码超时，请重试")
        }

        /** Completes router pairing after the App session is live, matching the official App sequence. */
        suspend fun acknowledgeRouterPairing(
            pairing: RouterPairing,
            appId: String,
            localIp: String = "0.0.0.0",
            network: RobotTransport = RobotTransport.Default,
        ) = withContext(Dispatchers.IO) {
            require(Regex("[0-9a-fA-F]{8}").matches(appId)) { "AppID 必须是 8 位十六进制字符" }
            require(pairing.robot.appId.equals(appId, ignoreCase = true)) { "配网 AppID 与二维码不一致" }
            val acknowledgement = appId.lowercase().encodeToByteArray()
            network.openDatagram().use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(localIp, Protocol.APP_PORT)
                socket.send(acknowledgement, pairing.robot.ip, pairing.sourcePort)
            }
        }
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        check(socket == null) { "会话已经打开" }
        product = RobotProduct()
        try {
            claimIdentity()
            coroutineContext.ensureActive()
            val udp = network.openDatagram()
            socket = udp
            udp.receiveBufferSize = 4 * 1024 * 1024
            udp.bind(target.localIp, target.localPort)
            udp.soTimeout = 200
            val deadline = robotNanoTime() + target.sessionTimeoutMillis * 1_000_000
            var acknowledged = false
            while (robotNanoTime() < deadline && !acknowledged) {
                coroutineContext.ensureActive()
                sendPacket(envelope.preconnect())
                try {
                    val packet = udp.receive() ?: throw RobotReceiveTimeout()
                    if (packet.ip != destination) continue
                    val data = packet.bytes
                    if (data.size >= 4) {
                        if (data.u16(2) == envelope.session) {
                            txLock.withLock { envelope.observe(data) }
                            acknowledged = true
                        } else envelope.session = ((data.u16(2) + 1) and 65535).coerceAtLeast(1)
                    }
                } catch (_: RobotReceiveTimeout) { /* retry preconnect only */ }
            }
            check(acknowledged) { "机器人未确认 App 会话，请检查网络或关闭其他控制端" }
            udp.soTimeout = 5
            lastReceivedNanos = robotNanoTime()
            active.store(true)
            receiver = receiverScope.launch { receiveLoop() }
            for (command in connectionSetup) {
                coroutineContext.ensureActive()
                check(connected) { "初始化期间连接中断" }
                if (command.control) sendNeutral() else send(command.receiver, Protocol.ATTR_NEED_ACK, command.set,
                    command.id, command.payload.hexBytes(), flags = command.flags.hexBytes())
                delay(if (command.control) 20 else 6)
            }
            onLog("App 会话已建立：${target.ip}:${target.remotePort}")
            coroutineContext.ensureActive()
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private suspend fun claimIdentity() {
        network.openDatagram().use { udp ->
            udp.reuseAddress = true
            udp.broadcast = true
            udp.bind(target.localIp, Protocol.APP_PORT)
            udp.soTimeout = 200
            val claim = target.appId.lowercase().encodeToByteArray()
            val deadline = robotNanoTime() + target.identityTimeoutMillis * 1_000_000
            var nextSend = 0L
            var claimSent = false
            var routeFailure: RobotRouteException? = null
            while (robotNanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                if (robotNanoTime() >= nextSend) {
                    try {
                        udp.send(claim, destination, Protocol.ROBOT_APP_PORT)
                        claimSent = true
                        routeFailure = null
                        nextSend = robotNanoTime() + 1_000_000_000
                    } catch (error: RobotRouteException) {
                        routeFailure = error
                        nextSend = robotNanoTime() + 250_000_000
                    }
                }
                try {
                    val packet = udp.receive() ?: continue
                    if (packet.ip != destination) continue
                    val robot = Protocol.broadcast(packet.bytes) ?: continue
                    if (robot.appId.equals(target.appId, true)) return
                    // A robot switching from the official App can still advertise its previous AppID.
                    // Keep claiming our stable identity, then let the App-session handshake decide reachability.
                } catch (_: RobotReceiveTimeout) { /* established robots may stop broadcasting */ }
            }
            if (!claimSent) {
                throw RobotRouteException(
                    "无法访问机器人所在本地网络；请检查系统本地网络权限与当前网络路由",
                    routeFailure,
                )
            }
            onLog("未收到身份广播，使用指定目标进行会话握手")
        }
    }

    override fun send(receiver: Int, attr: Int, set: Int, id: Int, payload: ByteArray,
             sender: Int, flags: ByteArray): Int = txLock.withLock {
        check(active.load()) { "机器人未连接" }
        val seq = sequence
        sequence = (sequence + 1) and 65535
        sendPacket(envelope.direct(Protocol.duss(sender, receiver, attr, set, id, payload, seq), flags))
        seq
    }

    private fun sendNeutral(forceActuatorStop: Boolean = false) = txLock.withLock {
        val seq = sequence
        sequence = (sequence + 1) and 65535
        val fresh = remote && robotNanoTime() < inputDeadline
        val payload = if (remote && robotNanoTime() < triggerDeadline) "0000042000010840000230".hexBytes()
            else Protocol.neutral
        sendPacket(envelope.control(Protocol.duss(Protocol.HOST_MOBILE, Protocol.HOST_HDVT_UAV, Protocol.ATTR_NO_ACK, Protocol.CMDSET_SPECIAL, Protocol.CMD_SPECIAL_RM_CONTROL, payload, seq)))
        if (remote || forceActuatorStop) {
            var velocity = if (fresh) controlPayload else RemoteControl.velocity(0.0,0.0,0.0)
            var gimbal = if(fresh) gimbalPayload else RemoteControl.gimbalVelocity(0.0,0.0)
            if (fresh && cameraRelative) {
                val angle = cameraYaw
                val body = angle?.let { RemoteControl.cameraVelocity(cameraInput.first, cameraInput.second, it) }
                    ?: (0.0 to 0.0)
                val follow = RemoteControl.follow(angle, gimbalInput.second)
                velocity = RemoteControl.velocity(body.first, body.second, chassisYawInput + follow.chassisYaw)
                gimbal = RemoteControl.gimbalVelocity(gimbalInput.first, follow.gimbalYaw)
            }
            // S1 ChassisCtrl.stop uses wheel RPM zero, not body velocity zero.
            // Ignore the sign bit of each float so -0.0 also enters the stop path.
            if (velocity.indices.all { index -> velocity[index] == 0.toByte() || (index % 4 == 3 && velocity[index] == 0x80.toByte()) })
                send(Protocol.HOST_CHASSIS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_WHEEL_SPEED_SET, ByteArray(8))
            else send(Protocol.HOST_CHASSIS, Protocol.ATTR_NO_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SPEED_SET, velocity)
            send(Protocol.HOST_GIMBAL, Protocol.ATTR_NO_ACK, Protocol.CMDSET_GIMBAL, Protocol.CMD_GIMBAL_EXT_CTRL_ACCEL, gimbal)
        }
    }

    private fun sendPacket(data: ByteArray) {
        val udp = checkNotNull(socket) { "会话已关闭" }
        udp.send(data, destination, target.remotePort)
    }

    override fun labMode(running: Boolean) { mode = Protocol.MODE_LAB; labRunning = running }
    fun normalMode() { mode = Protocol.MODE_NORMAL; labRunning = false }

    private fun receiveLoop() {
        var nextControl = 0L
        var nextKeepalive = 0L
        try {
            while (active.load()) {
                val udp = socket ?: break
                try {
                    val packet = udp.receive() ?: throw RobotReceiveTimeout()
                    val data = packet.bytes
                    if (packet.ip == destination && data.size >= 12 && data.u16(2) == envelope.session) {
                        lastReceivedNanos = robotNanoTime()
                        if (data.size > 20 && data.u8(6) == 2) {
                            onVideo?.invoke(data.copyOfRange(20, data.size))
                        } else {
                        txLock.withLock { envelope.observe(data) }
                        Protocol.frames(data).filter { it.valid }.forEach { frame ->
                            Telemetry.gimbalYaw(frame)?.let { yaw -> yawSample = yaw to robotNanoTime() }
                            updateProduct(frame)
                            if (frame.set == Protocol.CMDSET_RM && frame.id == Protocol.CMD_RM_AUDIO_TO_APP) onAudio?.invoke(frame.payload) else onFrame(frame)
                        }
                        }
                    }
                } catch (_: RobotReceiveTimeout) { /* control ticker */ }
                val now = robotNanoTime()
                check(now - lastReceivedNanos < 5_000_000_000) { "5 秒未收到机器人数据，连接已失效" }
                if (now >= nextControl) { sendNeutral(); nextControl = now + 20_000_000 }
                if (now >= nextKeepalive && !labRunning) {
                    send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NO_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SPECIAL_CONTROL, mode.hexBytes())
                    if (mode == Protocol.MODE_NORMAL) send(Protocol.HOST_WIFI, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_WIFI, Protocol.CMD_WIFI_AP_KEEPALIVE)
                    nextKeepalive = now + 800_000_000
                }
            }
        } catch (error: Exception) {
            if (active.load()) runCatching { safetyStop() }
            if (active.exchange(false)) onLost(error.message ?: error::class.simpleName.orEmpty())
        } finally { socket?.close() }
    }

    private fun updateProduct(frame: DussFrame) {
        RobotProductProtocol.updated(product, frame)?.let { updated ->
            val previous = product
            product = updated
            if (updated.model != previous.model) {
                val name = when (updated.model) {
                    RobotModel.UNKNOWN -> "未知"
                    RobotModel.ROBOMASTER_S1 -> "RoboMaster S1"
                    RobotModel.ROBOMASTER_EP -> "RoboMaster EP"
                }
                onLog("机器人型号：$name")
            }
        }
    }

    override fun close() {
        if (active.load()) runCatching { safetyStop() }
        if (active.load() && remote) runCatching { send(Protocol.HOST_CHASSIS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_WORK_MODE_SET, byteArrayOf(0)) }
        active.store(false)
        socket?.close()
        receiverScope.cancel()
        receiver = null
        socket = null
        product = RobotProduct()
    }
}
