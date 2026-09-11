package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import cn.elonzh.hanppie.robot.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ConsoleModel(
    val speech: SpeechEngine,
    val voiceInput: SpeechInput = NoSpeechInput(),
    private val speakerInput: SpeakerInput = NoSpeakerInput(),
    private val robotNetwork: () -> RobotNetwork = { RobotNetwork.Default },
    private val settingsStore: SettingsStore? = null,
    private val prepareNetwork: () -> Unit = {},
) {
    val replySpeaker = ReplySpeaker(speech)
    val autoReadReplies = MutableStateFlow(false)
    val controlSettings = MutableStateFlow(ControlSettings())
    var voicePageActive = false
    val isForeground: Boolean get() = acceptingWork
    val state = MutableStateFlow(ConsoleState())
    private fun defaultModelSettings() = ModelSettings(
        endpoint = System.getenv("HANPPIE_LLM_ENDPOINT") ?: ModelSettings().endpoint,
        model = System.getenv("HANPPIE_LLM_MODEL") ?: ModelSettings().model,
        apiKey = System.getenv("HANPPIE_LLM_API_KEY") ?: "",
    )
    val modelSettings = MutableStateFlow(defaultModelSettings())
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
                    controlSettings.value = it.control
                }
            } catch (_: Exception) {
                settingsMessage.value = uiText(Res.string.settings_load_failed)
            } finally { settingsBusy.value = false }
        }
    }
    fun saveSettings() {
        val snapshot = SavedSettings(modelSettings.value, autoReadReplies.value, controlSettings.value)
        persistSettings(snapshot, Res.string.settings_saved)
    }
    fun restoreDefaultSettings() {
        val defaults = SavedSettings()
        modelSettings.value = defaultModelSettings()
        autoReadReplies.value = defaults.autoRead
        controlSettings.value = defaults.control
        // Environment overrides stay effective at runtime but are never copied into persistent storage by reset.
        persistSettings(defaults, Res.string.default_settings_restored)
    }
    private fun persistSettings(snapshot: SavedSettings, success: org.jetbrains.compose.resources.StringResource) {
        val store = settingsStore
        if (store == null) {
            settingsMessage.value = uiText(success)
            return
        }
        if (!settingsBusy.compareAndSet(false, true)) return
        settingsMessage.value = null
        settingsScope.launch {
            try {
                // Saving a blank key intentionally clears the previously stored credential.
                if (snapshot.model.apiKey.isNotBlank()) snapshot.model.validate()
                store.save(snapshot)
                settingsMessage.value = uiText(success)
            } catch (_: Exception) {
                settingsMessage.value = uiText(Res.string.settings_save_failed)
            } finally { settingsBusy.value = false }
        }
    }
    private data class MediaRequest(val session: AppSession, val start: Boolean, val audio: Boolean)
    private data class LedRequest(val session: AppSession, val color: RobotLedColor?)
    private val mediaRequests = kotlinx.coroutines.channels.Channel<MediaRequest>(16)
    private val ledRequests = kotlinx.coroutines.channels.Channel<LedRequest>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val mediaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        mediaScope.launch {
            for (request in mediaRequests) if (request.session.connected) {
                runCatching { request.session.media(request.start, request.audio) }
                    .onFailure { log(tr(Res.string.media_request_failed_value,it.message)) }
            }
        }
        mediaScope.launch {
            for (request in ledRequests) if (request.session === session && request.session.connected) {
                runCatching {
                    val color = request.color
                    request.session.setLed(color?.red ?: 0, color?.green ?: 0, color?.blue ?: 0, color != null)
                }.onFailure { log(tr(Res.string.led_request_failed_value, it.message)) }
            }
        }
    }
    @Volatile private var session: AppSession? = null
    private var lab: LabController? = null
    @Volatile private var desiredTarget: RobotTarget? = null
    @Volatile private var connectionRevision = 0L
    private var reconnectJob: Job? = null
    val remoteEnabled = MutableStateFlow(false)
    val remoteInput = MutableStateFlow(List(5) { 0.0 })
    val cameraYaw = MutableStateFlow<Double?>(null)
    val gelSelected = MutableStateFlow(false)
    val driveGear = MutableStateFlow(3)
    val talking = MutableStateFlow(false)
    val talkBusy = MutableStateFlow(false)
    fun shiftGear(delta: Int) { driveGear.update { (it + delta).coerceIn(1, DriveSpeed.gearCount) } }
    fun selectGear(gear: Int) { driveGear.value = gear.coerceIn(1, DriveSpeed.gearCount) }
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
    fun haltRemote() {
        cancelPushToTalk()
        runCatching { session?.halt() }
        remoteEnabled.value = false
        remoteInput.value = List(5) { 0.0 }
        cameraYaw.value = null
    }
    fun leaveRemote() {
        haltRemote()
        val current = session
        scope.launch { runCatching { current?.exitRemote() }.onFailure { log(tr(Res.string.leaving_remote_control_value,it.message)) } }
    }
    fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) {
        if (remoteEnabled.value && acceptingWork && !chat.state.value.running && !state.value.busy) {
            remoteInput.value = listOf(x,y,z,pitch,yaw)
            cameraYaw.value = session?.cameraYaw
            runCatching { session?.drive(x, y, z, pitch, yaw, cameraRelative = true) }.onFailure {
                haltRemote()
                val message = tr(Res.string.remote_stopped_connection_unresponsive)
                log(message)
                state.update { current -> current.copy(error = message) }
            }
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
    fun beginPushToTalk() {
        if (!acceptingWork || !remoteEnabled.value || talkBusy.value || !talking.compareAndSet(false, true)) return
        runCatching { speakerInput.start() }.onFailure { error ->
            talking.value = false
            val message = error.message ?: error.javaClass.simpleName
            log(message)
            state.update { it.copy(error = message) }
        }
    }
    fun endPushToTalk() {
        if (!talking.compareAndSet(true, false)) return
        val current = session
        talkBusy.value = true
        scope.launch {
            try {
                val encoded = speakerInput.finish()
                val packets = checkNotNull(current) { tr(Res.string.robot_is_not_connected) }.playSpeaker(encoded)
                log(tr(Res.string.push_to_talk_sent_packets_value, packets))
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                log(message)
                state.update { it.copy(error = message) }
            } finally { talkBusy.value = false }
        }
    }
    private fun cancelPushToTalk() {
        if (talking.compareAndSet(true, false)) runCatching { speakerInput.cancel() }
    }
    fun startMedia(audio: Boolean) {
        val current = session ?: return
        if (acceptingWork && mediaRequests.trySend(MediaRequest(current, true, audio)).isFailure) log(tr(Res.string.media_request_queue_is_full))
    }
    fun stopMedia() {
        videoSink = null; audioSink = null
        session?.let { if (mediaRequests.trySend(MediaRequest(it, false, false)).isFailure) log(tr(Res.string.media_stop_queue_is_full)) }
    }
    fun setRemoteLed(color: RobotLedColor?) {
        val current = session ?: return
        if (acceptingWork && ledRequests.trySend(LedRequest(current, color)).isFailure)
            log(tr(Res.string.led_request_failed_value, tr(Res.string.request_queue_is_closed)))
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
        reconnectJob?.cancelAndJoin()
        connectionRevision++
        val revision = connectionRevision
        desiredTarget = null
        prepareNetwork()
        state.update { ConsoleState(busy = true, statusMessage = uiText(Res.string.connecting_to_value,target.ip), logs = it.logs, devices = it.devices) }
        val network = robotNetwork()
        val candidate = newSession(target, network)
        session = candidate
        try {
            candidate.connect()
            candidate.safetyStop()
            currentCoroutineContext().ensureActive()
            check(revision == connectionRevision) { tr(Res.string.connection_cancelled) }
            session = candidate
            lab = LabController(candidate, target, ::log, network)
            desiredTarget = target
            state.update { it.copy(connected = true, connectedAddress = target.ip, reconnecting = false,
                statusMessage = uiText(Res.string.connected_to_value,target.ip), error = null) }
        } catch (error: Exception) {
            candidate.close()
            if (session === candidate) session = null
            if (revision == connectionRevision) {
                desiredTarget = null
                state.update { it.copy(reconnecting = false, statusMessage = uiText(Res.string.connection_failed)) }
            }
            throw error
        }
    }

    private fun newSession(target: RobotTarget, network: RobotNetwork): AppSession {
        lateinit var candidate: AppSession
        candidate = AppSession(target, ::receive, ::log, network) { reason -> connectionLost(candidate, target, reason) }
        candidate.onVideo = { videoSink?.invoke(it) }
        candidate.onAudio = { audioSink?.invoke(it) }
        return candidate
    }

    private fun connectionLost(candidate: AppSession, target: RobotTarget, reason: String) {
        remoteEnabled.value = false
        remoteInput.value = List(5) { 0.0 }
        cameraYaw.value = null
        scope.launch {
            if (session !== candidate || desiredTarget != target) return@launch
            session = null
            lab = null
            candidate.close()
            log(reason)
            state.update { it.lost(reason).copy(busy = false, reconnecting = acceptingWork) }
            if (acceptingWork) reconnect(target, connectionRevision)
        }
    }

    private fun reconnect(target: RobotTarget, revision: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            var retryDelay = 500L
            while (isActive && acceptingWork && desiredTarget == target && connectionRevision == revision) {
                delay(retryDelay)
                attempt++
                state.update { it.copy(reconnecting = true,
                    statusMessage = uiText(Res.string.reconnecting_attempt_value, attempt), error = null) }
                var candidate: AppSession? = null
                try {
                    prepareNetwork()
                    val network = robotNetwork()
                    candidate = newSession(target, network)
                    session = candidate
                    candidate.connect()
                    candidate.safetyStop()
                    ensureActive()
                    check(acceptingWork && desiredTarget == target && connectionRevision == revision)
                    lab = LabController(candidate, target, ::log, network)
                    state.update { it.copy(connected = true, connectedAddress = target.ip, reconnecting = false,
                        statusMessage = uiText(Res.string.reconnected_to_value, target.ip), error = null) }
                    log(tr(Res.string.reconnected_to_value, target.ip))
                    return@launch
                } catch (error: CancellationException) {
                    candidate?.close()
                    if (session === candidate) session = null
                    throw error
                } catch (error: Exception) {
                    candidate?.close()
                    if (session === candidate) session = null
                    val nextDelay = (retryDelay * 2).coerceAtMost(8000L)
                    val seconds = (nextDelay / 1000L).coerceAtLeast(1L).toInt()
                    state.update { it.copy(connected = false, connectedAddress = null, reconnecting = true,
                        statusMessage = uiText(Res.string.reconnect_failed_retrying_value, seconds), error = error.message) }
                    retryDelay = nextDelay
                }
            }
        }
    }

    fun disconnect() = work {
        connectionRevision++
        desiredTarget = null
        reconnectJob?.cancelAndJoin()
        reconnectJob = null
        haltRemote()
        session?.close(); session = null; lab = null
        state.update { it.copy(connected = false, connectedAddress = null, reconnecting = false,
            statusMessage = uiText(Res.string.disconnected), error = null, uploadedSource = null,
            scriptMessage = uiText(Res.string.session_ended_robot_state_unknown), battery = null,
            signalQuality = null, values = emptyList(), gimbal = null) }
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
        val signalQuality = Telemetry.wifiSignalQuality(frame)
        val values = motion?.let {
            listOf(tr(Res.string.heading_like_uncalibrated) to it.headingLike.toString()) +
                it.raw.mapIndexed { index, value -> "raw[$index] / offset ${26 + index * 4}" to value.toString() }
        }
        val message = Telemetry.labMessage(frame)?.let { "type=${it.type} level=${it.level} ${it.text}" }
        state.update { old -> old.copy(packets = old.packets + 1,
            frames = (old.frames + line).takeLast(250),
            battery = if (motion == null) old.battery else motion.batteryPercent,
            signalQuality = signalQuality ?: old.signalQuality,
            gimbal = gimbal ?: old.gimbal,
            scriptMessages = if (message == null) old.scriptMessages else (old.scriptMessages + message).takeLast(200),
            values = values ?: old.values) }
    }

    fun clearLogs() { state.update { it.copy(logs = emptyList(), frames = emptyList()) } }
    suspend fun pauseConnection() {
        connectionRevision++
        desiredTarget = null
        reconnectJob?.cancelAndJoin()
        reconnectJob = null
        chat.cancelAndJoin()
        scope.coroutineContext[Job]?.children?.toList()?.forEach { it.cancelAndJoin() }
        session?.close(); session = null; lab = null
        state.update { it.copy(connected = false, connectedAddress = null, reconnecting = false, busy = false, statusMessage = uiText(Res.string.disconnected), uploadedSource = null,
            executionUncertain = false, battery = null, signalQuality = null, values = emptyList(), gimbal = null,
            scriptMessage = uiText(Res.string.connection_closed_robot_state_unknown)) }
    }
    fun close() { connectionRevision++; desiredTarget = null; reconnectJob?.cancel(); cancelPushToTalk(); speakerInput.close(); voiceInput.close(); replySpeaker.close(); chat.close(); session?.close(); speech.close(); mediaRequests.close(); ledRequests.close(); mediaScope.cancel(); settingsScope.cancel(); scope.cancel() }
}
