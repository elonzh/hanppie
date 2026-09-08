package cn.elonzh.hanppie.robot

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RobotTarget(val ip: String, val appId: String, val localIp: String = "0.0.0.0",
                       val localPort: Int = 10609, val remotePort: Int = 10607) {
    init {
        require(ip.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }) {
            "请指定机器人 IPv4 地址"
        }
        require(Regex("[0-9a-fA-F]{8}").matches(appId)) { "AppID 必须是 8 位十六进制字符" }
        require(localPort in 0..65535 && remotePort in 1..65535)
    }
}

/** One owner per robot connection. Blocking UDP is confined to IO/a dedicated receive thread. */
class AppSession(private val target: RobotTarget,
                 private val onFrame: (DussFrame) -> Unit = {},
                 private val onLog: (String) -> Unit = {},
                 private val network: RobotNetwork = RobotNetwork.Default,
                 private val onLost: (String) -> Unit = {}) : AutoCloseable, LabChannel {
    private val random = SecureRandom()
    private val envelope = AppEnvelope(random.nextInt(65535) + 1, random.nextInt(8192) * 8)
    private val destination = InetAddress.getByName(target.ip)
    private var sequence = 10072
    private val txLock = Any()
    private val active = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var receiver: Thread? = null
    @Volatile private var mode = "000300"
    @Volatile private var labRunning = false
    @Volatile private var remote = false
    private val modeMutex = Mutex()
    private var controlPayload = RemoteControl.velocity(0.0,0.0,0.0)
    private var gimbalPayload = RemoteControl.gimbalVelocity(0.0, 0.0)
    private var gimbalInput = 0.0 to 0.0
    private var chassisYawInput = 0.0
    private var cameraInput = 0.0 to 0.0
    private var cameraRelative = false
    @Volatile private var yawSample: Pair<Double, Long>? = null
    val cameraYaw: Double? get() = yawSample?.takeIf { System.nanoTime() - it.second < 500_000_000 }?.first
    private var inputDeadline = 0L
    private var triggerDeadline = 0L
    @Volatile var onVideo: ((ByteArray) -> Unit)? = null
    @Volatile var onAudio: ((ByteArray) -> Unit)? = null

    suspend fun enterRemote() = withContext(Dispatchers.IO) {
        modeMutex.withLock {
        check(!labRunning) { "请先停止 Lab 脚本" }
        if (!remote) {
            mode = "0b0300"
            for (c in remoteSetup + remoteEffects) {
                if (c.control) sendNeutral() else send(c.receiver, c.attr, c.set, c.id, c.payload.hexBytes(), flags = c.flags.hexBytes())
                Thread.sleep(10)
            }
            send(0xc3, 0x40, 0x3f, 0x19, byteArrayOf(1))
            send(0xc3, 0x40, 0x3f, 0x28, byteArrayOf(0))
            remote = true
            halt()
        }
        }
    }
    fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double, cameraRelative: Boolean = false) = synchronized(txLock) {
        check(remote && active.get()) { "遥控未启用" }
        controlPayload = RemoteControl.velocity(x, y, z)
        chassisYawInput = z
        gimbalInput = pitch to yaw
        this.cameraRelative = cameraRelative
        cameraInput = x to y
        gimbalPayload = RemoteControl.gimbalVelocity(pitch, yaw)
        inputDeadline = System.nanoTime() + 250_000_000
    }
    fun halt() = synchronized(txLock) {
        inputDeadline = 0; triggerDeadline = 0; controlPayload = RemoteControl.velocity(0.0,0.0,0.0); gimbalPayload = RemoteControl.gimbalVelocity(0.0, 0.0)
        cameraInput = 0.0 to 0.0
        gimbalInput = 0.0 to 0.0; chassisYawInput = 0.0
        if (active.get()) sendNeutral()
    }
    /** S1 rm_module.Gun.set_cmd_fire routes 3f:51 to hdvt_uav_id (900 -> 0x09), not EP's 0x17. */
    fun fireGelOnce(): Int = synchronized(txLock) {
        check(remote && active.get() && !labRunning) { "遥控未启用" }
        send(9, 0x40, 0x3f, 0x51, byteArrayOf(1))
    }
    suspend fun exitRemote() = withContext(Dispatchers.IO) {
        modeMutex.withLock {
        if (!remote) return@withLock
        halt()
        if (remote && connected) for (c in remoteExit) {
            if (c.control) sendNeutral() else send(c.receiver, c.attr, c.set, c.id, c.payload.hexBytes(), flags = c.flags.hexBytes())
            Thread.sleep(10)
        }
        if (connected) send(0xc3,0x40,0x3f,0x19,byteArrayOf(0))
        remote = false; normalMode()
        }
    }
    fun fireInfrared() = synchronized(txLock) {
        check(remote && active.get()) { "遥控未启用" }
        triggerDeadline = System.nanoTime() + 120_000_000
    }
    fun media(start: Boolean, audio: Boolean = false) {
        if (start) send(1, 0x40, 2, 0x18, "0403000000".hexBytes())
        for (control in if (start) listOf(1, 2) else listOf(2, 1))
            send(1, 0x40, 0x3f, 0xd2, byteArrayOf(control.toByte(), if (start) 1 else 0, 0))
        if (start && audio) send(1, 0x40, 0x3f, 0x1e, byteArrayOf(1))
    }
    @Volatile var lastReceivedNanos: Long = 0; private set
    override val connected: Boolean get() = active.get()

    companion object {
        suspend fun discover(timeoutMillis: Long = 3000, localIp: String = "0.0.0.0", network: RobotNetwork = RobotNetwork.Default): List<DiscoveredRobot> =
            withContext(Dispatchers.IO) {
                require(timeoutMillis in 1..30000)
                val found = linkedMapOf<String, DiscoveredRobot>()
                network.datagram().use { socket ->
                    socket.reuseAddress = true
                    socket.broadcast = true
                    socket.bind(InetSocketAddress(localIp, 45678))
                    socket.soTimeout = 200
                    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
                    while (System.nanoTime() < deadline) {
                        val packet = DatagramPacket(ByteArray(2048), 2048)
                        try {
                            socket.receive(packet)
                            Protocol.broadcast(packet.data.copyOf(packet.length))?.let {
                                if (packet.address.hostAddress == it.ip) found[it.ip] = it
                            }
                        } catch (_: SocketTimeoutException) { /* bounded passive discovery */ }
                    }
                }
                found.values.toList()
            }
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        check(socket == null) { "会话已经打开" }
        try {
            claimIdentity()
            coroutineContext.ensureActive()
            val udp = network.datagram()
            socket = udp
            udp.receiveBufferSize = 4 * 1024 * 1024
            udp.bind(InetSocketAddress(target.localIp, target.localPort))
            udp.connect(destination, target.remotePort)
            udp.soTimeout = 200
            val deadline = System.nanoTime() + 5_000_000_000
            var acknowledged = false
            while (System.nanoTime() < deadline && !acknowledged) {
                coroutineContext.ensureActive()
                sendPacket(envelope.preconnect())
                val packet = DatagramPacket(ByteArray(65535), 65535)
                try {
                    udp.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    if (data.size >= 4) {
                        if (data.u16(2) == envelope.session) {
                            synchronized(txLock) { envelope.observe(data) }
                            acknowledged = true
                        } else envelope.session = ((data.u16(2) + 1) and 65535).coerceAtLeast(1)
                    }
                } catch (_: SocketTimeoutException) { /* retry preconnect only */ }
            }
            check(acknowledged) { "机器人未确认 App 会话，请检查网络或关闭其他控制端" }
            udp.soTimeout = 5
            lastReceivedNanos = System.nanoTime()
            active.set(true)
            receiver = Thread(::receiveLoop, "hanppie-app-receiver").apply { isDaemon = true; start() }
            for (command in connectionSetup) {
                coroutineContext.ensureActive()
                check(connected) { "初始化期间连接中断" }
                if (command.control) sendNeutral() else send(command.receiver, 0x40, command.set,
                    command.id, command.payload.hexBytes(), flags = command.flags.hexBytes())
                Thread.sleep(if (command.control) 20 else 6)
            }
            onLog("App 会话已建立：${target.ip}:${target.remotePort}")
            coroutineContext.ensureActive()
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun claimIdentity() {
        network.datagram().use { udp ->
            udp.reuseAddress = true
            udp.broadcast = true
            udp.bind(InetSocketAddress(target.localIp, 45678))
            udp.soTimeout = 200
            val claim = target.appId.lowercase().toByteArray(Charsets.US_ASCII)
            val deadline = System.nanoTime() + 4_000_000_000
            var nextSend = 0L
            while (System.nanoTime() < deadline) {
                if (System.nanoTime() >= nextSend) {
                    udp.send(DatagramPacket(claim, claim.size, destination, 56789))
                    nextSend = System.nanoTime() + 1_000_000_000
                }
                val packet = DatagramPacket(ByteArray(2048), 2048)
                try {
                    udp.receive(packet)
                    if (packet.address != destination) continue
                    val robot = Protocol.broadcast(packet.data.copyOf(packet.length)) ?: continue
                    if (robot.ip != target.ip) continue
                    if (robot.appId.equals(target.appId, true)) return
                    check(robot.pairing || robot.appId == "00000000") { "机器人 AppID 与目标不一致" }
                } catch (_: SocketTimeoutException) { /* established robots may stop broadcasting */ }
            }
            onLog("未收到身份广播，使用指定目标进行会话握手")
        }
    }

    override fun send(receiver: Int, attr: Int, set: Int, id: Int, payload: ByteArray,
             sender: Int, flags: ByteArray): Int = synchronized(txLock) {
        check(active.get()) { "机器人未连接" }
        val seq = sequence
        sequence = (sequence + 1) and 65535
        sendPacket(envelope.direct(Protocol.duss(sender, receiver, attr, set, id, payload, seq), flags))
        seq
    }

    private fun sendNeutral() = synchronized(txLock) {
        val seq = sequence
        sequence = (sequence + 1) and 65535
        val fresh = remote && System.nanoTime() < inputDeadline
        val payload = if (remote && System.nanoTime() < triggerDeadline) "0000042000010840000230".hexBytes()
            else Protocol.neutral
        sendPacket(envelope.control(Protocol.duss(2, 9, 0, 1, 4, payload, seq)))
        if (remote) {
            var velocity = if (fresh) controlPayload else RemoteControl.velocity(0.0,0.0,0.0)
            var gimbal = if(fresh) gimbalPayload else RemoteControl.gimbalVelocity(0.0,0.0)
            if (fresh && cameraRelative) {
                val angle = cameraYaw
                val body = angle?.let { RemoteControl.cameraVelocity(cameraInput.first, cameraInput.second, it) }
                    ?: (0.0 to 0.0)
                val follow = RemoteControl.follow(angle, gimbalInput.second)
                velocity = RemoteControl.velocity(body.first, body.second, (chassisYawInput + follow.chassisYaw).coerceIn(-60.0,60.0))
                gimbal = RemoteControl.gimbalVelocity(gimbalInput.first, follow.gimbalYaw)
            }
            // S1 ChassisCtrl.stop uses wheel RPM zero, not body velocity zero.
            // Ignore the sign bit of each float so -0.0 also enters the stop path.
            if (velocity.indices.all { index -> velocity[index] == 0.toByte() || (index % 4 == 3 && velocity[index] == 0x80.toByte()) })
                send(0xc3,0x40,0x3f,0x20,ByteArray(8))
            else send(0xc3,0,0x3f,0x21,velocity)
            send(4, 0, 4, 0x0c, gimbal)
        }
    }

    private fun sendPacket(data: ByteArray) {
        val udp = checkNotNull(socket) { "会话已关闭" }
        udp.send(DatagramPacket(data, data.size, destination, target.remotePort))
    }

    override fun labMode(running: Boolean) { mode = "020302"; labRunning = running }
    fun normalMode() { mode = "000300"; labRunning = false }

    private fun receiveLoop() {
        var nextControl = 0L
        var nextKeepalive = 0L
        val buffer = ByteArray(65535)
        try {
            while (active.get()) {
                val udp = socket ?: break
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udp.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    if (data.size >= 12 && data.u16(2) == envelope.session) {
                        lastReceivedNanos = System.nanoTime()
                        if (data.size > 20 && data.u8(6) == 2) {
                            onVideo?.invoke(data.copyOfRange(20, data.size))
                        } else {
                        synchronized(txLock) { envelope.observe(data) }
                        Protocol.frames(data).filter { it.valid }.forEach {
                            Telemetry.gimbalYaw(it)?.let { yaw -> yawSample = yaw to System.nanoTime() }
                            if (it.set == 0x3f && it.id == 0x1d) onAudio?.invoke(it.payload) else onFrame(it)
                        }
                        }
                    }
                } catch (_: SocketTimeoutException) { /* control ticker */ }
                val now = System.nanoTime()
                check(now - lastReceivedNanos < 5_000_000_000) { "5 秒未收到机器人数据，连接已失效" }
                if (now >= nextControl) { sendNeutral(); nextControl = now + 20_000_000 }
                if (now >= nextKeepalive && !labRunning) {
                    send(9, 0, 0x3f, 4, mode.hexBytes())
                    if (mode == "000300") send(7, 0x40, 7, 0x17)
                    nextKeepalive = now + 800_000_000
                }
            }
        } catch (error: Exception) {
            if (active.get()) runCatching { halt() }
            if (active.getAndSet(false)) onLost(error.message ?: error.javaClass.simpleName)
        } finally { socket?.close() }
    }

    override fun close() {
        if (active.get()) runCatching { halt() }
        if (active.get() && remote) runCatching { send(0xc3,0x40,0x3f,0x19,byteArrayOf(0)) }
        active.set(false)
        socket?.close()
        if (Thread.currentThread() !== receiver) receiver?.join(1500)
        receiver = null
        socket = null
    }
}
