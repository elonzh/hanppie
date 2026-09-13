@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.lab.LabRunEvent
import cn.elonzh.hanppie.robot.lab.LabRunEventType
import cn.elonzh.hanppie.robot.lab.LabRunProtocol
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.session.RobotLabSession
import cn.elonzh.hanppie.robot.session.RobotRuntime
import cn.elonzh.hanppie.robot.session.RobotSession
import cn.elonzh.hanppie.robot.session.RobotTarget
import cn.elonzh.hanppie.robot.telemetry.Telemetry
import cn.elonzh.hanppie.ui.chat.ChatAgent
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.i18n.DateTimeStyle
import cn.elonzh.hanppie.ui.i18n.formatLocalDateTime
import cn.elonzh.hanppie.ui.robot.files.RobotFilesController
import cn.elonzh.hanppie.ui.robot.remote.DriveSpeed
import cn.elonzh.hanppie.ui.robot.remote.NoSpeakerInput
import cn.elonzh.hanppie.ui.robot.remote.SpeakerInput
import cn.elonzh.hanppie.ui.scripts.ScriptLibrary
import cn.elonzh.hanppie.ui.scripts.ScriptRepository
import cn.elonzh.hanppie.ui.settings.ModelSettings
import cn.elonzh.hanppie.ui.settings.RobotLedColor
import cn.elonzh.hanppie.ui.settings.SettingsController
import cn.elonzh.hanppie.ui.settings.SettingsStore
import cn.elonzh.hanppie.ui.speech.NoSpeechInput
import cn.elonzh.hanppie.ui.speech.ReplySpeaker
import cn.elonzh.hanppie.ui.speech.SpeechEngine
import cn.elonzh.hanppie.ui.speech.SpeechInput
import io.ktor.client.HttpClient
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ConsoleModel(
    override val speech: SpeechEngine,
    override val voiceInput: SpeechInput = NoSpeechInput(),
    private val speakerInput: SpeakerInput = NoSpeakerInput(),
    private val robotRuntime: RobotRuntime,
    settingsStore: SettingsStore,
    scriptRepository: ScriptRepository,
    createAgentHttpClient: () -> HttpClient,
    private val runtimeDefaults: () -> ModelSettings = ::ModelSettings,
    private val applyModelOverrides: (ModelSettings) -> ModelSettings = { it },
    private val prepareNetwork: () -> Unit = {},
    private val clock: Clock = Clock.System,
) : ConsoleController {
    override val replySpeaker = ReplySpeaker(speech)
    override var voicePageActive = false
    override val isForeground: Boolean get() = acceptingWork.load()
    override val state = MutableStateFlow(ConsoleState())
    private val settings = SettingsController(
        store = settingsStore,
        runtimeDefaults = runtimeDefaults,
        applyEnvironmentOverrides = applyModelOverrides,
    )
    override val modelSettings = settings.model
    override val autoReadReplies = settings.autoRead
    override val controlSettings = settings.control
    override val settingsBusy = settings.busy
    override val settingsMessage = settings.message
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val scriptLibrary = ScriptLibrary(scriptRepository)
    override val robotFiles = RobotFilesController(scope) { acceptingWork.load() && state.value.connected }
    init {
        scope.launch { scriptLibrary.load() }
    }
    override fun saveSettings() = settings.save()
    override fun restoreDefaultSettings() = settings.restoreDefaults()
    private data class MediaRequest(val session: RobotSession, val start: Boolean, val audio: Boolean)
    private data class LedRequest(val session: RobotSession, val color: RobotLedColor?)
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
            for (request in ledRequests) if (request.session === session.load() && request.session.connected) {
                runCatching {
                    val color = request.color
                    request.session.setLed(color?.red ?: 0, color?.green ?: 0, color?.blue ?: 0, color != null)
                }.onFailure { log(tr(Res.string.led_request_failed_value, it.message)) }
            }
        }
    }
    private val session = AtomicReference<RobotSession?>(null)
    private val lab = AtomicReference<RobotLabSession?>(null)
    private val desiredTarget = AtomicReference<RobotTarget?>(null)
    private val connectionRevision = AtomicLong(0L)
    private var reconnectJob: Job? = null
    override val foregroundState = MutableStateFlow(true)
    private val remoteRevision = AtomicLong(0L)
    override val remoteEnabled = MutableStateFlow(false)
    override val remoteInput = MutableStateFlow(List(5) { 0.0 })
    override val cameraYaw = MutableStateFlow<Double?>(null)
    override val gelSelected = MutableStateFlow(false)
    override val driveGear = MutableStateFlow(3)
    override val talking = MutableStateFlow(false)
    override val microphoneReady = MutableStateFlow(false)
    private val talkGeneration = AtomicLong(0L)
    override val talkBusy = MutableStateFlow(false)
    override fun shiftGear(delta: Int) { driveGear.update { (it + delta).coerceIn(1, DriveSpeed.gearCount) } }
    override fun selectGear(gear: Int) { driveGear.value = gear.coerceIn(1, DriveSpeed.gearCount) }
    override fun switchAmmo() { gelSelected.value = !gelSelected.value }
    override fun fireSelected() { if (gelSelected.value) fireGel() else fire() }
    private val videoConsumer = AtomicReference<((ByteArray) -> Unit)?>(null)
    private val audioConsumer = AtomicReference<((ByteArray) -> Unit)?>(null)
    override var videoSink: ((ByteArray) -> Unit)?
        get() = videoConsumer.load()
        set(value) = videoConsumer.store(value)
    override var audioSink: ((ByteArray) -> Unit)?
        get() = audioConsumer.load()
        set(value) = audioConsumer.store(value)
    override fun enableRemote() {
        val target = session.load()
        val revision = remoteRevision.load()
        work {
            if (revision != remoteRevision.load() || !isForeground || session.load() !== target) return@work
            check(!state.value.scriptRunPhase.mayBeExecuting) { tr(Res.string.stop_the_lab_script_first) }
            checkNotNull(target).enterRemote()
            if (revision != remoteRevision.load() || !isForeground || session.load() !== target) {
                target.halt()
            } else {
                lab.load()?.invalidateMode()
                remoteEnabled.value = true
                if (revision != remoteRevision.load() || !isForeground || session.load() !== target) {
                    remoteEnabled.value = false
                    target.halt()
                }
            }
        }
    }
    override fun haltRemote() {
        remoteRevision.addAndFetch(1)
        cancelPushToTalk()
        runCatching { session.load()?.halt() }
        remoteEnabled.value = false
        remoteInput.value = List(5) { 0.0 }
        cameraYaw.value = null
    }
    override fun leaveRemote() {
        haltRemote()
        val current = session.load()
        scope.launch { runCatching { current?.exitRemote() }.onFailure { log(tr(Res.string.leaving_remote_control_value,it.message)) } }
    }
    override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) {
        if (remoteEnabled.value && acceptingWork.load() && !chat.state.value.running && !state.value.busy) {
            remoteInput.value = listOf(x,y,z,pitch,yaw)
            cameraYaw.value = session.load()?.cameraYaw
            runCatching { session.load()?.drive(x, y, z, pitch, yaw, cameraRelative = true) }.onFailure {
                haltRemote()
                val message = tr(Res.string.remote_stopped_connection_unresponsive)
                log(message)
                state.update { current -> current.copy(error = message) }
            }
        }
    }
    override fun fire() {
        if (remoteEnabled.value && acceptingWork.load() && !chat.state.value.running && !state.value.busy)
            runCatching { session.load()?.fireInfrared() }.onFailure { state.update { s -> s.copy(error = it.message) } }
    }
    override fun fireGel() = work {
        check(remoteEnabled.value) { tr(Res.string.enable_remote_control_first) }
        check(!state.value.scriptRunPhase.mayBeExecuting) { tr(Res.string.stop_the_lab_script_first) }
        val sequence = checkNotNull(session.load()).fireGelOnce()
        log(tr(Res.string.gel_fire_command_sent_seq_value_physical_firing_unconfirmed,sequence))
    }
    override fun beginPushToTalk() {
        if (!acceptingWork.load() || !remoteEnabled.value || talkBusy.value || !talking.compareAndSet(false, true)) return
        microphoneReady.value = false
        val generation = talkGeneration.addAndFetch(1)
        runCatching { speakerInput.start {
            if (generation == talkGeneration.load() && talking.value) microphoneReady.value = true
        } }.onFailure { error ->
            talking.value = false
            microphoneReady.value = false
            val message = error.message ?: error::class.simpleName ?: "Unknown error"
            log(message)
            state.update { it.copy(error = message) }
        }
    }
    override fun endPushToTalk() {
        if (!talking.compareAndSet(true, false)) return
        talkGeneration.addAndFetch(1)
        val ready = microphoneReady.value.also { microphoneReady.value = false }
        if (!ready) { speakerInput.cancel(); return }
        val current = session.load()
        talkBusy.value = true
        scope.launch {
            try {
                val encoded = speakerInput.finish()
                val packets = checkNotNull(current) { tr(Res.string.robot_is_not_connected) }.playSpeaker(encoded)
                log(tr(Res.string.push_to_talk_sent_packets_value, packets))
            } catch (error: Exception) {
                val message = error.message ?: error::class.simpleName ?: "Unknown error"
                log(message)
                state.update { it.copy(error = message) }
            } finally { talkBusy.value = false }
        }
    }
    private fun cancelPushToTalk() {
        talkGeneration.addAndFetch(1)
        microphoneReady.value = false
        val cancel = talking.compareAndSet(true, false)
        if (cancel) runCatching { speakerInput.cancel() }
    }
    override fun startMedia(audio: Boolean) {
        val current = session.load() ?: return
        if (acceptingWork.load() && mediaRequests.trySend(MediaRequest(current, true, audio)).isFailure) log(tr(Res.string.media_request_queue_is_full))
    }
    override fun stopMedia() {
        videoSink = null; audioSink = null
        session.load()?.let { if (mediaRequests.trySend(MediaRequest(it, false, false)).isFailure) log(tr(Res.string.media_stop_queue_is_full)) }
    }
    override fun setRemoteLed(color: RobotLedColor?) {
        val current = session.load() ?: return
        if (acceptingWork.load() && ledRequests.trySend(LedRequest(current, color)).isFailure)
            log(tr(Res.string.led_request_failed_value, tr(Res.string.request_queue_is_closed)))
    }

    private val acceptingWork = AtomicBoolean(true)
    override val chat = ChatAgent(
        status = { state.value.let { "${it.status}; battery=${it.battery}; script=${it.scriptStatus}; messages=${it.scriptMessages.takeLast(12)}" } },
        execute = { source -> agentOperation {
            check(!state.value.scriptRunPhase.mayBeExecuting) { tr(Res.string.stop_the_script_with_unknown_state_first) }
            haltRemote()
            session.load()?.exitRemote()
            startScript(source, "Hanppie-Agent")
            tr(Res.string.script_started_progress_and_completion_will_be_reported)
        } },
        stopRobot = { agentOperation {
            checkNotNull(lab.load()) { tr(Res.string.robot_is_not_connected) }.stop()
            state.update { it.copy(scriptRunPhase = ScriptRunPhase.STOPPED,
                scriptMessage = uiText(Res.string.script_stopped), scriptFinishedAtEpochMillis = clock.now().toEpochMilliseconds()) }
            tr(Res.string.stop_command_sent_robot_stop_is_unconfirmed)
        } },
        createHttpClient = createAgentHttpClient,
    )

    private suspend fun agentOperation(block: suspend () -> String): String {
        check(acceptingWork.load() && state.value.connected) { tr(Res.string.robot_disconnected_or_app_not_in_foreground) }
        val snapshot = state.value
        check(!snapshot.busy && state.compareAndSet(snapshot, snapshot.copy(busy = true))) { tr(Res.string.another_operation_is_in_progress) }
        return try { block() } finally { state.update { it.copy(busy = false) } }
    }

    override fun setForeground(foreground: Boolean) {
        acceptingWork.store(foreground)
        foregroundState.value = foreground
        if (!foreground) { haltRemote(); voiceInput.cancel(); replySpeaker.stop() }
        else desiredTarget.load()?.let { target ->
            if (!state.value.connected && session.load() == null && reconnectJob?.isActive != true) {
                reconnect(target, connectionRevision.load())
            }
        }
    }

    override fun log(message: String) {
        val line = "${formatLocalDateTime(clock.now().toEpochMilliseconds(), DateTimeStyle.LOG_TIME)}  $message"
        state.update { it.copy(logs = (it.logs + line).takeLast(500)) }
    }

    private fun work(allowDuringChat: Boolean = false, block: suspend () -> Unit) {
        if (chat.state.value.running && !allowDuringChat) {
            state.update { it.copy(error = tr(Res.string.cancel_the_current_chat_before_changing_connections_or_using)) }; return
        }
        val snapshot = state.value
        if (!acceptingWork.load() || snapshot.busy || !state.compareAndSet(snapshot, snapshot.copy(busy = true, error = null))) return
        scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message = error.message ?: error::class.simpleName ?: "Unknown error"
                log(tr(Res.string.error_value,message))
                state.update { it.copy(error = message) }
            } finally { state.update { it.copy(busy = false) } }
        }
    }

    override fun discover() = work {
        prepareNetwork()
        log(tr(Res.string.listening_for_robots_on_the_local_network))
        val devices = robotRuntime.discover()
        state.update { it.copy(devices = devices) }
        log(tr(Res.string.found_value_robots_connected_robots_may_not_broadcast_enter,devices.size))
    }

    override fun connect(ip: String, appId: String) = work {
        check(session.load() == null) { tr(Res.string.disconnect_the_current_session_first) }
        val target = RobotTarget(ip.trim(), appId.trim())
        reconnectJob?.cancelAndJoin()
        val revision = connectionRevision.addAndFetch(1)
        desiredTarget.store(null)
        prepareNetwork()
        state.update { ConsoleState(busy = true, statusMessage = uiText(Res.string.connecting_to_value,target.ip), logs = it.logs, devices = it.devices) }
        val candidate = newSession(target)
        session.store(candidate)
        try {
            candidate.connect()
            candidate.safetyStop()
            currentCoroutineContext().ensureActive()
            check(revision == connectionRevision.load()) { tr(Res.string.connection_cancelled) }
            session.store(candidate)
            lab.store(candidate.lab)
            robotFiles.attach(candidate.files)
            desiredTarget.store(target)
            state.update { it.copy(connected = true, connectedAddress = target.ip, reconnecting = false,
                statusMessage = uiText(Res.string.connected_to_value,target.ip), error = null) }
        } catch (error: Exception) {
            candidate.close()
            session.compareAndSet(candidate, null)
            if (revision == connectionRevision.load()) {
                desiredTarget.store(null)
                state.update { it.copy(reconnecting = false, statusMessage = uiText(Res.string.connection_failed)) }
            }
            throw error
        }
    }

    private fun newSession(target: RobotTarget): RobotSession {
        val candidate = robotRuntime.open(target, ::receive, ::log, ::connectionLost)
        candidate.onVideo = { videoConsumer.load()?.invoke(it) }
        candidate.onAudio = { audioConsumer.load()?.invoke(it) }
        return candidate
    }

    private fun connectionLost(candidate: RobotSession, reason: String) {
        remoteEnabled.value = false
        remoteInput.value = List(5) { 0.0 }
        cameraYaw.value = null
        scope.launch {
            val target = desiredTarget.load() ?: return@launch
            if (session.load() !== candidate) return@launch
            session.store(null)
            lab.store(null)
            robotFiles.clear()
            candidate.close()
            log(reason)
            state.update { it.lost(reason).copy(busy = false, reconnecting = acceptingWork.load()) }
            if (acceptingWork.load()) reconnect(target, connectionRevision.load())
        }
    }

    private fun reconnect(target: RobotTarget, revision: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            var retryDelay = 500L
            while (isActive && acceptingWork.load() && desiredTarget.load() == target && connectionRevision.load() == revision) {
                delay(retryDelay)
                attempt++
                state.update { it.copy(reconnecting = true,
                    statusMessage = uiText(Res.string.reconnecting_attempt_value, attempt), error = null) }
                var candidate: RobotSession? = null
                try {
                    prepareNetwork()
                    candidate = newSession(target)
                    session.store(candidate)
                    candidate.connect()
                    candidate.safetyStop()
                    ensureActive()
                    check(acceptingWork.load() && desiredTarget.load() == target && connectionRevision.load() == revision)
                    lab.store(candidate.lab)
                    robotFiles.attach(candidate.files)
                    state.update { it.copy(connected = true, connectedAddress = target.ip, reconnecting = false,
                        statusMessage = uiText(Res.string.reconnected_to_value, target.ip), error = null) }
                    log(tr(Res.string.reconnected_to_value, target.ip))
                    return@launch
                } catch (error: CancellationException) {
                    candidate?.close()
                    session.compareAndSet(candidate, null)
                    throw error
                } catch (error: Exception) {
                    candidate?.close()
                    session.compareAndSet(candidate, null)
                    val nextDelay = (retryDelay * 2).coerceAtMost(8000L)
                    val seconds = (nextDelay / 1000L).coerceAtLeast(1L).toInt()
                    state.update { it.copy(connected = false, connectedAddress = null, reconnecting = true,
                        statusMessage = uiText(Res.string.reconnect_failed_retrying_value, seconds), error = error.message) }
                    retryDelay = nextDelay
                }
            }
        }
    }

    override fun disconnect() = work {
        connectionRevision.addAndFetch(1)
        desiredTarget.store(null)
        reconnectJob?.cancelAndJoin()
        reconnectJob = null
        haltRemote()
        session.exchange(null)?.close(); lab.store(null)
        robotFiles.clear()
        state.update {
            val uncertain = it.scriptRunPhase.mayBeExecuting
            it.copy(connected = false, connectedAddress = null, reconnecting = false,
                statusMessage = uiText(Res.string.disconnected), error = null,
                scriptRunPhase = if (uncertain) ScriptRunPhase.UNKNOWN else it.scriptRunPhase,
                scriptMessage = if (uncertain) uiText(Res.string.session_ended_robot_state_unknown) else it.scriptMessage,
                battery = null, signalQuality = null, values = emptyList(), gimbal = null)
        }
        log(tr(Res.string.connection_closed_scripts_on_the_robot_may_still_be))
    }

    override fun runScript(source: String, title: String) = work {
        haltRemote(); session.load()?.exitRemote()
        startScript(source, title)
    }

    private suspend fun startScript(source: String, title: String) {
        val controller = checkNotNull(lab.load()) { tr(Res.string.robot_is_not_connected) }
        val startedAt = clock.now().toEpochMilliseconds()
        state.update { it.copy(scriptRunId = null, scriptTitle = title,
            scriptRunPhase = ScriptRunPhase.UPLOADING, scriptStartedAtEpochMillis = startedAt,
            scriptFinishedAtEpochMillis = null, scriptMessage = uiText(Res.string.uploading), scriptMessages = emptyList()) }
        val upload = try {
            controller.upload(source, title)
        } catch (error: Exception) {
            state.update { it.copy(scriptRunPhase = ScriptRunPhase.FAILED,
                scriptFinishedAtEpochMillis = clock.now().toEpochMilliseconds(), scriptMessage = uiText(Res.string.upload_failed)) }
            throw error
        }
        state.update { it.copy(scriptRunId = upload.runId, scriptRunPhase = ScriptRunPhase.STARTING,
            scriptMessage = uiText(Res.string.waiting_for_script_start)) }
        try {
            controller.start()
        } catch (error: Exception) {
            state.update { it.copy(scriptRunPhase = ScriptRunPhase.UNKNOWN,
                scriptMessage = uiText(Res.string.script_start_state_unknown)) }
            throw error
        }
    }

    override fun stop() = work(allowDuringChat = true) {
        chat.cancelAndJoin()
        state.update { it.copy(scriptRunPhase = ScriptRunPhase.STOPPING, scriptMessage = uiText(Res.string.stopping_script)) }
        try {
            checkNotNull(lab.load()) { tr(Res.string.robot_is_not_connected) }.stop()
            state.update { it.copy(scriptRunPhase = ScriptRunPhase.STOPPED,
                scriptMessage = uiText(Res.string.script_stopped), scriptFinishedAtEpochMillis = clock.now().toEpochMilliseconds()) }
        } catch (error: Exception) {
            state.update { it.copy(scriptRunPhase = ScriptRunPhase.UNKNOWN,
                scriptMessage = uiText(Res.string.script_stop_state_unknown)) }
            throw error
        }
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
        val message = Telemetry.labMessage(frame)
        val runEvent = message?.let { LabRunProtocol.decode(it.text) }
        val eventRunId = runEvent?.runId
        val eventType = runEvent?.type
        state.update { old -> old.copy(packets = old.packets + 1,
            frames = (old.frames + line).takeLast(250),
            battery = if (motion == null) old.battery else motion.batteryPercent,
            signalQuality = signalQuality ?: old.signalQuality,
            gimbal = gimbal ?: old.gimbal,
            scriptMessages = when {
                runEvent == null && message != null -> (old.scriptMessages + message.text).takeLast(200)
                else -> old.scriptMessages
            },
            scriptRunPhase = if (eventRunId == old.scriptRunId && eventType == LabRunEventType.STARTED)
                ScriptRunPhase.RUNNING else old.scriptRunPhase,
            scriptMessage = if (eventRunId == old.scriptRunId && eventType == LabRunEventType.STARTED)
                uiText(Res.string.script_running) else old.scriptMessage,
            values = values ?: old.values) }
        runEvent?.let { event ->
            if (event.runId == state.value.scriptRunId &&
                (event.type == LabRunEventType.COMPLETED || event.type == LabRunEventType.FAILED)) {
                finishReportedRun(event)
            }
        }
    }

    private fun finishReportedRun(event: LabRunEvent) {
        val snapshot = state.value
        if (snapshot.scriptRunId != event.runId || snapshot.scriptRunPhase == ScriptRunPhase.COMPLETING ||
            !snapshot.scriptRunPhase.mayBeExecuting) return
        if (!state.compareAndSet(snapshot, snapshot.copy(scriptRunPhase = ScriptRunPhase.COMPLETING,
                scriptMessage = uiText(Res.string.finishing_completed_script)))) return
        scope.launch {
            try {
                val accepted = lab.load()?.complete(event.runId) == true
                if (!accepted) return@launch
                val phase = if (event.type == LabRunEventType.COMPLETED) ScriptRunPhase.COMPLETED else ScriptRunPhase.FAILED
                val message = if (phase == ScriptRunPhase.COMPLETED) uiText(Res.string.script_completed) else
                    uiText(Res.string.script_failed_value, event.text.ifBlank { tr(Res.string.unknown_error) })
                state.update { current -> if (current.scriptRunId == event.runId) current.copy(
                    scriptRunPhase = phase, scriptMessage = message,
                    scriptFinishedAtEpochMillis = clock.now().toEpochMilliseconds()) else current }
            } catch (error: Exception) {
                val message = error.message ?: error::class.simpleName ?: "Unknown error"
                log(tr(Res.string.error_value, message))
                state.update { current -> if (current.scriptRunId == event.runId) current.copy(
                    scriptRunPhase = ScriptRunPhase.UNKNOWN,
                    scriptMessage = uiText(Res.string.completed_marker_received_but_cleanup_failed)) else current }
            }
        }
    }

    override fun clearLogs() { state.update { it.copy(logs = emptyList(), frames = emptyList()) } }
    override suspend fun pauseConnection() {
        connectionRevision.addAndFetch(1)
        desiredTarget.store(null)
        reconnectJob?.cancelAndJoin()
        reconnectJob = null
        chat.cancelAndJoin()
        scope.coroutineContext[Job]?.children?.toList()?.forEach { it.cancelAndJoin() }
        session.exchange(null)?.close(); lab.store(null)
        robotFiles.clear()
        state.update {
            val uncertain = it.scriptRunPhase.mayBeExecuting
            it.copy(connected = false, connectedAddress = null, reconnecting = false, busy = false,
                statusMessage = uiText(Res.string.disconnected), battery = null, signalQuality = null,
                values = emptyList(), gimbal = null,
                scriptRunPhase = if (uncertain) ScriptRunPhase.UNKNOWN else it.scriptRunPhase,
                scriptMessage = if (uncertain) uiText(Res.string.connection_closed_robot_state_unknown) else it.scriptMessage)
        }
    }
    override fun close() { connectionRevision.addAndFetch(1); desiredTarget.store(null); reconnectJob?.cancel(); robotFiles.close(); cancelPushToTalk(); speakerInput.close(); voiceInput.close(); replySpeaker.close(); chat.close(); session.exchange(null)?.close(); lab.store(null); speech.close(); mediaRequests.close(); ledRequests.close(); mediaScope.cancel(); settings.close(); scope.cancel() }
}
