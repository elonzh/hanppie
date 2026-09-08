package cn.elonzh.hanppie.desktop

import cn.elonzh.hanppie.robot.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ConsoleModel(val speech: SpeechEngine, val voiceInput: SpeechInput = NoSpeechInput(), private val robotNetwork: () -> RobotNetwork = { RobotNetwork.Default }, private val prepareNetwork: () -> Unit = {}) {
    val replySpeaker = ReplySpeaker(speech)
    val autoReadReplies = MutableStateFlow(false)
    var voicePageActive = false
    val isForeground: Boolean get() = acceptingWork
    val state = MutableStateFlow(ConsoleState())
    val modelSettings = MutableStateFlow(ModelSettings(
        endpoint = System.getenv("HANPPIE_LLM_ENDPOINT") ?: ModelSettings().endpoint,
        model = System.getenv("HANPPIE_LLM_MODEL") ?: ModelSettings().model,
        apiKey = System.getenv("HANPPIE_LLM_API_KEY") ?: "",
    ))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class MediaRequest(val session: AppSession, val start: Boolean, val audio: Boolean)
    private val mediaRequests = kotlinx.coroutines.channels.Channel<MediaRequest>(16)
    private val mediaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        mediaScope.launch {
            for (request in mediaRequests) if (request.session.connected) {
                runCatching { request.session.media(request.start, request.audio) }
                    .onFailure { log(tr("媒体请求失败：{0}",it.message)) }
            }
        }
    }
    private var session: AppSession? = null
    private var lab: LabController? = null
    val remoteEnabled = MutableStateFlow(false)
    val remoteInput = MutableStateFlow(List(5) { 0.0 })
    val cameraYaw = MutableStateFlow<Double?>(null)
    val gelSelected = MutableStateFlow(false)
    val driveGear = MutableStateFlow(2)
    fun shiftGear(delta: Int) { driveGear.update { (it + delta).coerceIn(1, DriveSpeed.gears.size) } }
    fun switchAmmo() { gelSelected.value = !gelSelected.value }
    fun fireSelected() { if (gelSelected.value) fireGel() else fire() }
    @Volatile var videoSink: ((ByteArray) -> Unit)? = null
    @Volatile var audioSink: ((ByteArray) -> Unit)? = null
    fun enableRemote() = work {
        check(!state.value.executionUncertain) { tr("请先停止 Lab 脚本") }
        checkNotNull(session).enterRemote()
        lab?.invalidateMode()
        state.update { it.copy(uploadedSource = null, scriptStatus = tr("遥控模式；需重新上传脚本")) }
        remoteEnabled.value = true
    }
    fun haltRemote() { runCatching { session?.halt() }; remoteEnabled.value = false; remoteInput.value = List(5) { 0.0 }; cameraYaw.value = null }
    fun leaveRemote() {
        haltRemote()
        val current = session
        scope.launch { runCatching { current?.exitRemote() }.onFailure { log(tr("退出遥控：{0}",it.message)) } }
    }
    fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) {
        if (remoteEnabled.value && acceptingWork && !chat.state.value.running && !state.value.busy) {
            remoteInput.value = listOf(x,y,z,pitch,yaw)
            cameraYaw.value = session?.cameraYaw
            runCatching { session?.drive(x, y, z, pitch, yaw, cameraRelative = true) }.onFailure { haltRemote() }
        }
    }
    fun fire() {
        if (remoteEnabled.value && acceptingWork && !chat.state.value.running && !state.value.busy)
            runCatching { session?.fireInfrared() }.onFailure { state.update { s -> s.copy(error = it.message) } }
    }
    fun fireGel() = work {
        check(remoteEnabled.value) { tr("请先启用遥控") }
        check(!state.value.executionUncertain) { tr("请先停止 Lab 脚本") }
        val sequence = checkNotNull(session).fireGelOnce()
        log(tr("水弹单发直控命令已发送 seq={0}；未确认物理发射，不自动重试",sequence))
    }
    fun startMedia(audio: Boolean) {
        val current = session ?: return
        if (acceptingWork && mediaRequests.trySend(MediaRequest(current, true, audio)).isFailure) log(tr("媒体请求队列已满"))
    }
    fun stopMedia() {
        videoSink = null; audioSink = null
        session?.let { if (mediaRequests.trySend(MediaRequest(it, false, false)).isFailure) log(tr("媒体停止队列已满")) }
    }
    @Volatile private var acceptingWork = true
    val chat = ChatAgent(
        status = { state.value.let { "${it.status}; battery=${it.battery}; script=${it.scriptStatus}; messages=${it.scriptMessages.takeLast(12)}" } },
        execute = { source -> agentOperation {
            check(!state.value.executionUncertain) { tr("请先停止状态未确认的脚本") }
            haltRemote()
            session?.exitRemote()
            val controller = checkNotNull(lab) { tr("机器人未连接") }
            check(!state.value.executionUncertain) { tr("请先停止状态未确认的脚本") }
            state.update { it.copy(uploadedSource = null, scriptStatus = tr("正在上传")) }
            controller.upload(source, "Hanppie-Agent")
            state.update { it.copy(uploadedSource = source, executionUncertain = true, scriptStatus = tr("正在发送启动命令；结果待确认")) }
            controller.start()
            state.update { it.copy(scriptStatus = tr("启动命令已发送（未确认完成）")) }
            tr("上传已确认，启动命令已发送；尚未确认动作执行或完成。")
        } },
        stopRobot = { agentOperation {
            checkNotNull(lab) { tr("机器人未连接") }.stop()
            state.update { it.copy(executionUncertain = false, scriptStatus = tr("停止命令已发送")) }
            tr("停止命令已发送；未获得机内停止确认。")
        } },
    )

    private suspend fun agentOperation(block: suspend () -> String): String {
        check(acceptingWork && state.value.connected) { tr("机器人未连接或应用不在前台") }
        val snapshot = state.value
        check(!snapshot.busy && state.compareAndSet(snapshot, snapshot.copy(busy = true))) { tr("设备正在执行其他操作") }
        return try { block() } finally { state.update { it.copy(busy = false,
            scriptStatus = if (it.scriptStatus == tr("正在上传")) tr("上传未确认") else it.scriptStatus) } }
    }

    fun setForeground(foreground: Boolean) {
        acceptingWork = foreground
        if (!foreground) { haltRemote(); voiceInput.cancel(); replySpeaker.stop() }
    }

    fun log(message: String) {
        val line = "${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))}  $message"
        state.update { it.copy(logs = (it.logs + line).takeLast(500)) }
    }

    private fun work(allowDuringChat: Boolean = false, block: suspend () -> Unit) {
        if (chat.state.value.running && !allowDuringChat) {
            state.update { it.copy(error = tr("请先取消当前对话，再切换连接或手动操作")) }; return
        }
        val snapshot = state.value
        if (!acceptingWork || snapshot.busy || !state.compareAndSet(snapshot, snapshot.copy(busy = true, error = null))) return
        scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                log(tr("错误：{0}",message))
                state.update { it.copy(error = message) }
                state.update { if (it.scriptStatus == tr("正在上传")) it.copy(scriptStatus = tr("上传失败")) else it }
            } finally { state.update { it.copy(busy = false) } }
        }
    }

    fun discover() = work {
        prepareNetwork()
        log(tr("监听局域网设备广播…"))
        val devices = AppSession.discover(network = robotNetwork())
        state.update { it.copy(devices = devices) }
        log(tr("发现 {0} 台设备；已连接设备可能不广播，可手动填写目标",devices.size))
    }

    fun connect(ip: String, appId: String) = work {
        check(session == null) { tr("请先断开当前连接") }
        val target = RobotTarget(ip.trim(), appId.trim())
        prepareNetwork()
        state.update { ConsoleState(busy = true, status = tr("正在连接 {0}",target.ip), logs = it.logs, devices = it.devices) }
        val network = robotNetwork()
        val candidate = AppSession(target, ::receive, ::log, network) { reason ->
            remoteEnabled.value = false
            log(reason)
            state.update { it.lost(reason) }
        }
        session = candidate
        candidate.onVideo = { videoSink?.invoke(it) }
        candidate.onAudio = { audioSink?.invoke(it) }
        try {
            candidate.connect()
            session = candidate
            lab = LabController(candidate, target, ::log, network)
            state.update { it.copy(connected = true, status = tr("已连接 {0}",target.ip)) }
        } catch (error: Exception) {
            candidate.close()
            session = null
            state.update { it.copy(status = tr("连接失败")) }
            throw error
        }
    }

    fun disconnect() = work {
        haltRemote()
        session?.close(); session = null; lab = null
        state.update { it.copy(connected = false, status = tr("未连接"), uploadedSource = null, scriptStatus = tr("会话已结束；机内状态未知"), battery = null, values = emptyList()) }
        log(tr("连接已关闭；断开连接不代表任意机内脚本已经停止"))
    }

    fun upload(source: String) = work {
        haltRemote(); session?.exitRemote()
        state.update { it.copy(uploadedSource = null, scriptStatus = tr("正在上传")) }
        checkNotNull(lab) { tr("机器人未连接") }.upload(source, "Hanppie-Desktop")
        state.update { it.copy(uploadedSource = source, scriptStatus = tr("上传已确认，尚未启动")) }
    }
    fun start() = work {
        state.update { it.copy(executionUncertain = true, scriptStatus = tr("正在发送启动命令；结果待确认")) }
        checkNotNull(lab) { tr("机器人未连接") }.start()
        state.update { it.copy(scriptStatus = tr("启动命令已发送（未确认完成）")) }
    }
    fun stop() = work(allowDuringChat = true) {
        chat.cancelAndJoin()
        checkNotNull(lab) { tr("机器人未连接") }.stop()
        state.update { it.copy(scriptStatus = tr("停止命令已发送"), executionUncertain = false) }
    }

    internal fun receive(frame: DussFrame) {
        val line = "seq=${frame.sequence} ${frame.sender.toString(16)}→${frame.receiver.toString(16)} " +
            "${frame.set.toString(16)}:${frame.id.toString(16)} attr=${frame.attr.toString(16)} ${frame.payload.hex()}"
        val motion = Telemetry.motion(frame)
        val values = motion?.let {
            listOf(tr("heading_like（未标定）") to it.headingLike.toString()) +
                it.raw.mapIndexed { index, value -> "raw[$index] / offset ${26 + index * 4}" to value.toString() }
        }
        val message = Telemetry.labMessage(frame)?.let { "type=${it.type} level=${it.level} ${it.text}" }
        state.update { old -> old.copy(packets = old.packets + 1,
            frames = (old.frames + line).takeLast(250),
            battery = if (motion == null) old.battery else motion.batteryPercent,
            scriptMessages = if (message == null) old.scriptMessages else (old.scriptMessages + message).takeLast(200),
            values = values ?: old.values) }
    }

    fun clearLogs() { state.update { it.copy(logs = emptyList(), frames = emptyList()) } }
    suspend fun pauseConnection() {
        chat.cancelAndJoin()
        scope.coroutineContext[Job]?.children?.toList()?.forEach { it.cancelAndJoin() }
        session?.close(); session = null; lab = null
        state.update { it.copy(connected = false, busy = false, status = tr("未连接"), uploadedSource = null,
            executionUncertain = false, battery = null, values = emptyList(), scriptStatus = tr("连接已关闭；机内状态未知")) }
    }
    fun close() { voiceInput.close(); replySpeaker.close(); chat.close(); session?.close(); speech.close(); mediaRequests.close(); mediaScope.cancel(); scope.cancel() }
}
