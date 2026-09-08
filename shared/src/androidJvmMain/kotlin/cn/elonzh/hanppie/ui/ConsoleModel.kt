package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import cn.elonzh.hanppie.robot.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ConsoleModel(val speech: SpeechEngine, val voiceInput: SpeechInput = NoSpeechInput(), private val robotNetwork: () -> RobotNetwork = { RobotNetwork.Default }, private val settingsStore: SettingsStore? = null, private val prepareNetwork: () -> Unit = {}) {
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
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settingsBusy = MutableStateFlow(settingsStore != null)
    val settingsMessage = MutableStateFlow<UiText?>(null)
    init {
        if (settingsStore != null) settingsScope.launch {
            try {
                settingsStore.load()?.let {
                    modelSettings.value = it.model.copy(
                        endpoint = System.getenv("HANPPIE_LLM_ENDPOINT") ?: it.model.endpoint,
                        model = System.getenv("HANPPIE_LLM_MODEL") ?: it.model.model,
                        apiKey = System.getenv("HANPPIE_LLM_API_KEY") ?: it.model.apiKey,
                    )
                    autoReadReplies.value = it.autoRead
                }
            } catch (_: Exception) {
                settingsMessage.value = uiText(Res.string.settings_load_failed)
            } finally { settingsBusy.value = false }
        }
    }
    fun saveSettings() {
        val store = settingsStore ?: return
        if (!settingsBusy.compareAndSet(false, true)) return
        val snapshot = SavedSettings(modelSettings.value, autoReadReplies.value)
        settingsMessage.value = null
        settingsScope.launch {
            try {
                // Saving a blank key intentionally clears the previously stored credential.
                if (snapshot.model.apiKey.isNotBlank()) snapshot.model.validate()
                store.save(snapshot)
                settingsMessage.value = uiText(Res.string.settings_saved)
            } catch (_: Exception) {
                settingsMessage.value = uiText(Res.string.settings_save_failed)
            } finally { settingsBusy.value = false }
        }
    }
    private data class MediaRequest(val session: AppSession, val start: Boolean, val audio: Boolean)
    private val mediaRequests = kotlinx.coroutines.channels.Channel<MediaRequest>(16)
    private val mediaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        mediaScope.launch {
            for (request in mediaRequests) if (request.session.connected) {
                runCatching { request.session.media(request.start, request.audio) }
                    .onFailure { log(tr(Res.string.media_request_failed_value,it.message)) }
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
        check(!state.value.executionUncertain) { tr(Res.string.stop_the_lab_script_first) }
        checkNotNull(session).enterRemote()
        lab?.invalidateMode()
        state.update { it.copy(uploadedSource = null, scriptMessage = uiText(Res.string.remote_control_upload_the_script_again_before_running)) }
        remoteEnabled.value = true
    }
    fun haltRemote() { runCatching { session?.halt() }; remoteEnabled.value = false; remoteInput.value = List(5) { 0.0 }; cameraYaw.value = null }
    fun leaveRemote() {
        haltRemote()
        val current = session
        scope.launch { runCatching { current?.exitRemote() }.onFailure { log(tr(Res.string.leaving_remote_control_value,it.message)) } }
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
        check(remoteEnabled.value) { tr(Res.string.enable_remote_control_first) }
        check(!state.value.executionUncertain) { tr(Res.string.stop_the_lab_script_first) }
        val sequence = checkNotNull(session).fireGelOnce()
        log(tr(Res.string.gel_fire_command_sent_seq_value_physical_firing_unconfirmed,sequence))
    }
    fun startMedia(audio: Boolean) {
        val current = session ?: return
        if (acceptingWork && mediaRequests.trySend(MediaRequest(current, true, audio)).isFailure) log(tr(Res.string.media_request_queue_is_full))
    }
    fun stopMedia() {
        videoSink = null; audioSink = null
        session?.let { if (mediaRequests.trySend(MediaRequest(it, false, false)).isFailure) log(tr(Res.string.media_stop_queue_is_full)) }
    }
    @Volatile private var acceptingWork = true
    val chat = ChatAgent(
        status = { state.value.let { "${it.status}; battery=${it.battery}; script=${it.scriptStatus}; messages=${it.scriptMessages.takeLast(12)}" } },
        execute = { source -> agentOperation {
            check(!state.value.executionUncertain) { tr(Res.string.stop_the_script_with_unknown_state_first) }
            haltRemote()
            session?.exitRemote()
            val controller = checkNotNull(lab) { tr(Res.string.robot_is_not_connected) }
            check(!state.value.executionUncertain) { tr(Res.string.stop_the_script_with_unknown_state_first) }
            state.update { it.copy(uploadedSource = null, scriptMessage = uiText(Res.string.uploading)) }
            controller.upload(source, "Hanppie-Agent")
            state.update { it.copy(uploadedSource = source, executionUncertain = true, scriptMessage = uiText(Res.string.sending_start_command_result_unconfirmed)) }
            controller.start()
            state.update { it.copy(scriptMessage = uiText(Res.string.start_command_sent_completion_unconfirmed)) }
            tr(Res.string.upload_confirmed_and_start_command_sent_action_execution_and)
        } },
        stopRobot = { agentOperation {
            checkNotNull(lab) { tr(Res.string.robot_is_not_connected) }.stop()
            state.update { it.copy(executionUncertain = false, scriptMessage = uiText(Res.string.stop_command_sent)) }
            tr(Res.string.stop_command_sent_robot_stop_is_unconfirmed)
        } },
    )

    private suspend fun agentOperation(block: suspend () -> String): String {
        check(acceptingWork && state.value.connected) { tr(Res.string.robot_disconnected_or_app_not_in_foreground) }
        val snapshot = state.value
        check(!snapshot.busy && state.compareAndSet(snapshot, snapshot.copy(busy = true))) { tr(Res.string.another_operation_is_in_progress) }
        return try { block() } finally { state.update { it.copy(busy = false,
            scriptMessage = if (it.scriptMessage.resource == Res.string.uploading) uiText(Res.string.upload_unconfirmed) else it.scriptMessage) } }
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
            state.update { it.copy(error = tr(Res.string.cancel_the_current_chat_before_changing_connections_or_using)) }; return
        }
        val snapshot = state.value
        if (!acceptingWork || snapshot.busy || !state.compareAndSet(snapshot, snapshot.copy(busy = true, error = null))) return
        scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                log(tr(Res.string.error_value,message))
                state.update { it.copy(error = message) }
                state.update { if (it.scriptMessage.resource == Res.string.uploading) it.copy(scriptMessage = uiText(Res.string.upload_failed)) else it }
            } finally { state.update { it.copy(busy = false) } }
        }
    }

    fun discover() = work {
        prepareNetwork()
        log(tr(Res.string.listening_for_robots_on_the_local_network))
        val devices = AppSession.discover(network = robotNetwork())
        state.update { it.copy(devices = devices) }
        log(tr(Res.string.found_value_robots_connected_robots_may_not_broadcast_enter,devices.size))
    }

    fun connect(ip: String, appId: String) = work {
        check(session == null) { tr(Res.string.disconnect_the_current_session_first) }
        val target = RobotTarget(ip.trim(), appId.trim())
        prepareNetwork()
        state.update { ConsoleState(busy = true, statusMessage = uiText(Res.string.connecting_to_value,target.ip), logs = it.logs, devices = it.devices) }
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
            state.update { it.copy(connected = true, connectedAddress = target.ip, statusMessage = uiText(Res.string.connected_to_value,target.ip)) }
        } catch (error: Exception) {
            candidate.close()
            session = null
            state.update { it.copy(statusMessage = uiText(Res.string.connection_failed)) }
            throw error
        }
    }

    fun disconnect() = work {
        haltRemote()
        session?.close(); session = null; lab = null
        state.update { it.copy(connected = false, connectedAddress = null, statusMessage = uiText(Res.string.disconnected), uploadedSource = null, scriptMessage = uiText(Res.string.session_ended_robot_state_unknown), battery = null, values = emptyList(), gimbal = null) }
        log(tr(Res.string.connection_closed_scripts_on_the_robot_may_still_be))
    }

    fun upload(source: String) = work {
        haltRemote(); session?.exitRemote()
        state.update { it.copy(uploadedSource = null, scriptMessage = uiText(Res.string.uploading)) }
        checkNotNull(lab) { tr(Res.string.robot_is_not_connected) }.upload(source, "Hanppie-Desktop")
        state.update { it.copy(uploadedSource = source, scriptMessage = uiText(Res.string.upload_confirmed_not_started)) }
    }
    fun start() = work {
        state.update { it.copy(executionUncertain = true, scriptMessage = uiText(Res.string.sending_start_command_result_unconfirmed)) }
        checkNotNull(lab) { tr(Res.string.robot_is_not_connected) }.start()
        state.update { it.copy(scriptMessage = uiText(Res.string.start_command_sent_completion_unconfirmed)) }
    }
    fun stop() = work(allowDuringChat = true) {
        chat.cancelAndJoin()
        checkNotNull(lab) { tr(Res.string.robot_is_not_connected) }.stop()
        state.update { it.copy(scriptMessage = uiText(Res.string.stop_command_sent), executionUncertain = false) }
    }

    internal fun receive(frame: DussFrame) {
        val line = "seq=${frame.sequence} ${frame.sender.toString(16)}→${frame.receiver.toString(16)} " +
            "${frame.set.toString(16)}:${frame.id.toString(16)} attr=${frame.attr.toString(16)} ${frame.payload.hex()}"
        val motion = Telemetry.motion(frame)
        val gimbal = Telemetry.gimbal(frame)
        val values = motion?.let {
            listOf(tr(Res.string.heading_like_uncalibrated) to it.headingLike.toString()) +
                it.raw.mapIndexed { index, value -> "raw[$index] / offset ${26 + index * 4}" to value.toString() }
        }
        val message = Telemetry.labMessage(frame)?.let { "type=${it.type} level=${it.level} ${it.text}" }
        state.update { old -> old.copy(packets = old.packets + 1,
            frames = (old.frames + line).takeLast(250),
            battery = if (motion == null) old.battery else motion.batteryPercent,
            gimbal = gimbal ?: old.gimbal,
            scriptMessages = if (message == null) old.scriptMessages else (old.scriptMessages + message).takeLast(200),
            values = values ?: old.values) }
    }

    fun clearLogs() { state.update { it.copy(logs = emptyList(), frames = emptyList()) } }
    suspend fun pauseConnection() {
        chat.cancelAndJoin()
        scope.coroutineContext[Job]?.children?.toList()?.forEach { it.cancelAndJoin() }
        session?.close(); session = null; lab = null
        state.update { it.copy(connected = false, connectedAddress = null, busy = false, statusMessage = uiText(Res.string.disconnected), uploadedSource = null,
            executionUncertain = false, battery = null, values = emptyList(), gimbal = null, scriptMessage = uiText(Res.string.connection_closed_robot_state_unknown)) }
    }
    fun close() { voiceInput.close(); replySpeaker.close(); chat.close(); session?.close(); speech.close(); mediaRequests.close(); mediaScope.cancel(); settingsScope.cancel(); scope.cancel() }
}
