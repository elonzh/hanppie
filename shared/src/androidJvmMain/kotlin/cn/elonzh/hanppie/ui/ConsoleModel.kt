package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import cn.elonzh.hanppie.robot.*
import java.io.InputStream
import java.io.OutputStream
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
    scriptStore: ScriptStore = MemoryScriptStore(),
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
    val scriptLibrary = ScriptLibrary(scriptStore)
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settingsBusy = MutableStateFlow(settingsStore != null)
    val settingsMessage = MutableStateFlow<UiText?>(null)
    val robotFilesState = MutableStateFlow(RobotFilesState())
    init {
        scope.launch { scriptLibrary.load() }
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
    @Volatile private var robotFiles: RobotFileSystem? = null
    private var robotFileJob: Job? = null
    @Volatile private var desiredTarget: RobotTarget? = null
    @Volatile private var connectionRevision = 0L
    private var reconnectJob: Job? = null
    val foregroundState = MutableStateFlow(true)
    private val remoteStateLock = Any()
    @Volatile private var remoteRevision = 0L
    val remoteEnabled = MutableStateFlow(false)
    val remoteInput = MutableStateFlow(List(5) { 0.0 })
    val cameraYaw = MutableStateFlow<Double?>(null)
    val gelSelected = MutableStateFlow(false)
    val driveGear = MutableStateFlow(3)
    val talking = MutableStateFlow(false)
    val microphoneReady = MutableStateFlow(false)
    private val talkStateLock = Any()
    private var talkGeneration = 0L
    val talkBusy = MutableStateFlow(false)
    fun shiftGear(delta: Int) { driveGear.update { (it + delta).coerceIn(1, DriveSpeed.gearCount) } }
    fun selectGear(gear: Int) { driveGear.value = gear.coerceIn(1, DriveSpeed.gearCount) }
    fun switchAmmo() { gelSelected.value = !gelSelected.value }
    fun fireSelected() { if (gelSelected.value) fireGel() else fire() }
    @Volatile var videoSink: ((ByteArray) -> Unit)? = null
    @Volatile var audioSink: ((ByteArray) -> Unit)? = null
    fun enableRemote() {
        val target = session
        val revision = remoteRevision
        work {
            if (revision != remoteRevision || !isForeground || session !== target) return@work
            check(!state.value.scriptRunPhase.mayBeExecuting) { tr(Res.string.stop_the_lab_script_first) }
            checkNotNull(target).enterRemote()
            synchronized(remoteStateLock) {
                if (revision != remoteRevision || !isForeground || session !== target) {
                    target.halt()
                } else {
                    lab?.invalidateMode()
                    remoteEnabled.value = true
                }
            }
        }
    }
    fun haltRemote() = synchronized(remoteStateLock) {
        remoteRevision++
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
        check(!state.value.scriptRunPhase.mayBeExecuting) { tr(Res.string.stop_the_lab_script_first) }
        val sequence = checkNotNull(session).fireGelOnce()
        log(tr(Res.string.gel_fire_command_sent_seq_value_physical_firing_unconfirmed,sequence))
    }
    fun beginPushToTalk() {
        val generation = synchronized(talkStateLock) {
            if (!acceptingWork || !remoteEnabled.value || talkBusy.value || !talking.compareAndSet(false, true)) return
            microphoneReady.value = false
            ++talkGeneration
        }
        runCatching { speakerInput.start {
            synchronized(talkStateLock) {
                if (generation == talkGeneration && talking.value) microphoneReady.value = true
            }
        } }.onFailure { error ->
            synchronized(talkStateLock) { talking.value = false; microphoneReady.value = false }
            val message = error.message ?: error.javaClass.simpleName
            log(message)
            state.update { it.copy(error = message) }
        }
    }
    fun endPushToTalk() {
        val ready = synchronized(talkStateLock) {
            if (!talking.compareAndSet(true, false)) return
            talkGeneration++
            microphoneReady.value.also { microphoneReady.value = false }
        }
        if (!ready) { speakerInput.cancel(); return }
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
        val cancel = synchronized(talkStateLock) {
            talkGeneration++
            microphoneReady.value = false
            talking.compareAndSet(true, false)
        }
        if (cancel) runCatching { speakerInput.cancel() }
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

    fun refreshRobotFiles(path: String = robotFilesState.value.path) = fileWork(uiText(Res.string.refreshing_robot_files)) { files ->
        val normalized = RobotFileSystem.normalizePath(path)
        val entries = files.list(normalized)
        robotFilesState.value.copy(path = normalized, entries = entries, selectedPath = null,
            message = uiText(Res.string.file_count_value, entries.size))
    }

    fun selectRobotFile(entry: RobotFileEntry?) {
        if (robotFilesState.value.busy) return
        robotFilesState.update { it.copy(selectedPath = entry?.path) }
    }

    fun openRobotDirectory(entry: RobotFileEntry) {
        if (entry.isDirectory) refreshRobotFiles(entry.path)
    }

    fun openRobotParentDirectory() {
        val path = robotFilesState.value.path
        if (path != "/") refreshRobotFiles(path.substringBeforeLast('/', "").ifEmpty { "/" })
    }

    fun uploadRobotFile(directory: String, name: String, source: () -> InputStream) =
        fileWork(uiText(Res.string.uploading_file_value, name)) { files ->
            val uploaded = source().use { files.upload(directory, name, it) }
            val entries = files.list(directory)
            robotFilesState.value.copy(path = directory, entries = entries, selectedPath = uploaded.path,
                message = if (uploaded.name == name) uiText(Res.string.file_uploaded_value, uploaded.name)
                else uiText(Res.string.file_uploaded_as_value, uploaded.name))
        }

    fun downloadRobotFile(entry: RobotFileEntry, destination: () -> OutputStream) =
        fileWork(uiText(Res.string.downloading_file_value, entry.name)) { files ->
            check(entry.isRegularFile) { tr(Res.string.only_regular_files_can_be_downloaded) }
            destination().use { files.download(entry.path, it) }
            robotFilesState.value.copy(selectedPath = entry.path,
                message = uiText(Res.string.file_downloaded_value, entry.name))
        }

    fun openRobotFile(entry: RobotFileEntry, destination: () -> OutputStream, onDownloaded: () -> Unit) =
        fileWork(uiText(Res.string.opening_file_value, entry.name)) { files ->
            check(entry.isRegularFile) { tr(Res.string.only_regular_files_can_be_downloaded) }
            destination().use { files.download(entry.path, it) }
            onDownloaded()
            robotFilesState.value.copy(selectedPath = entry.path,
                message = uiText(Res.string.file_ready_to_open_value, entry.name))
        }

    fun createRobotDirectory(name: String) = fileWork(uiText(Res.string.creating_folder_value, name)) { files ->
        val current = robotFilesState.value.path
        val created = files.createDirectory(current, name)
        robotFilesState.value.copy(entries = files.list(current), selectedPath = created.path,
            message = uiText(Res.string.folder_created_value, created.name))
    }

    fun renameRobotFile(entry: RobotFileEntry, newName: String) =
        fileWork(uiText(Res.string.renaming_file_value, entry.name)) { files ->
            val current = robotFilesState.value.path
            val renamed = files.rename(entry.path, newName)
            robotFilesState.value.copy(entries = files.list(current), selectedPath = renamed,
                message = uiText(Res.string.file_renamed_value, newName))
        }

    fun deleteRobotFile(entry: RobotFileEntry) = fileWork(uiText(Res.string.deleting_file_value, entry.name)) { files ->
        val current = robotFilesState.value.path
        files.delete(entry)
        robotFilesState.value.copy(entries = files.list(current), selectedPath = null,
            message = uiText(Res.string.file_deleted_value, entry.name))
    }

    private fun fileWork(operation: UiText, action: suspend (RobotFileSystem) -> RobotFilesState) {
        if (!acceptingWork || !state.value.connected) {
            robotFilesState.update { it.copy(error = tr(Res.string.robot_is_not_connected)) }
            return
        }
        val files = robotFiles ?: run {
            robotFilesState.update { it.copy(error = tr(Res.string.robot_file_service_unavailable)) }
            return
        }
        if (robotFileJob?.isActive == true) return
        robotFilesState.update { it.copy(busy = true, operation = operation, error = null, message = null) }
        robotFileJob = scope.launch {
            try {
                val next = action(files)
                if (robotFiles === files && state.value.connected) robotFilesState.value = next.copy(busy = false, operation = null, error = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (robotFiles === files) robotFilesState.update {
                    it.copy(busy = false, operation = null, error = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    private fun clearRobotFiles() {
        val files = robotFiles
        robotFiles = null
        robotFileJob?.cancel()
        robotFileJob = null
        files?.close()
        robotFilesState.value = RobotFilesState()
    }
    @Volatile private var acceptingWork = true
    val chat = ChatAgent(
        status = { state.value.let { "${it.status}; battery=${it.battery}; script=${it.scriptStatus}; messages=${it.scriptMessages.takeLast(12)}" } },
        execute = { source -> agentOperation {
            check(!state.value.scriptRunPhase.mayBeExecuting) { tr(Res.string.stop_the_script_with_unknown_state_first) }
            haltRemote()
            session?.exitRemote()
            startScript(source, "Hanppie-Agent")
            tr(Res.string.script_started_progress_and_completion_will_be_reported)
        } },
        stopRobot = { agentOperation {
            checkNotNull(lab) { tr(Res.string.robot_is_not_connected) }.stop()
            state.update { it.copy(scriptRunPhase = ScriptRunPhase.STOPPED,
                scriptMessage = uiText(Res.string.script_stopped), scriptFinishedAtEpochMillis = System.currentTimeMillis()) }
            tr(Res.string.stop_command_sent_robot_stop_is_unconfirmed)
        } },
    )

    private suspend fun agentOperation(block: suspend () -> String): String {
        check(acceptingWork && state.value.connected) { tr(Res.string.robot_disconnected_or_app_not_in_foreground) }
        val snapshot = state.value
        check(!snapshot.busy && state.compareAndSet(snapshot, snapshot.copy(busy = true))) { tr(Res.string.another_operation_is_in_progress) }
        return try { block() } finally { state.update { it.copy(busy = false) } }
    }

    fun setForeground(foreground: Boolean) {
        acceptingWork = foreground
        foregroundState.value = foreground
        if (!foreground) { haltRemote(); voiceInput.cancel(); replySpeaker.stop() }
        else desiredTarget?.let { target ->
            if (!state.value.connected && session == null && reconnectJob?.isActive != true) {
                reconnect(target, connectionRevision)
            }
        }
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
            robotFiles = RobotFileSystem(target, network)
            robotFilesState.value = RobotFilesState()
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
            clearRobotFiles()
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
                    robotFiles = RobotFileSystem(target, network)
                    robotFilesState.value = RobotFilesState()
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
        clearRobotFiles()
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

    fun runScript(source: String, title: String) = work {
        haltRemote(); session?.exitRemote()
        startScript(source, title)
    }

    private suspend fun startScript(source: String, title: String) {
        val controller = checkNotNull(lab) { tr(Res.string.robot_is_not_connected) }
        val startedAt = System.currentTimeMillis()
        state.update { it.copy(scriptRunId = null, scriptTitle = title,
            scriptRunPhase = ScriptRunPhase.UPLOADING, scriptStartedAtEpochMillis = startedAt,
            scriptFinishedAtEpochMillis = null, scriptMessage = uiText(Res.string.uploading), scriptMessages = emptyList()) }
        val upload = try {
            controller.upload(source, title)
        } catch (error: Exception) {
            state.update { it.copy(scriptRunPhase = ScriptRunPhase.FAILED,
                scriptFinishedAtEpochMillis = System.currentTimeMillis(), scriptMessage = uiText(Res.string.upload_failed)) }
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

    fun stop() = work(allowDuringChat = true) {
        chat.cancelAndJoin()
        state.update { it.copy(scriptRunPhase = ScriptRunPhase.STOPPING, scriptMessage = uiText(Res.string.stopping_script)) }
        try {
            checkNotNull(lab) { tr(Res.string.robot_is_not_connected) }.stop()
            state.update { it.copy(scriptRunPhase = ScriptRunPhase.STOPPED,
                scriptMessage = uiText(Res.string.script_stopped), scriptFinishedAtEpochMillis = System.currentTimeMillis()) }
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
                val accepted = lab?.complete(event.runId) == true
                if (!accepted) return@launch
                val phase = if (event.type == LabRunEventType.COMPLETED) ScriptRunPhase.COMPLETED else ScriptRunPhase.FAILED
                val message = if (phase == ScriptRunPhase.COMPLETED) uiText(Res.string.script_completed) else
                    uiText(Res.string.script_failed_value, event.text.ifBlank { tr(Res.string.unknown_error) })
                state.update { current -> if (current.scriptRunId == event.runId) current.copy(
                    scriptRunPhase = phase, scriptMessage = message,
                    scriptFinishedAtEpochMillis = System.currentTimeMillis()) else current }
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                log(tr(Res.string.error_value, message))
                state.update { current -> if (current.scriptRunId == event.runId) current.copy(
                    scriptRunPhase = ScriptRunPhase.UNKNOWN,
                    scriptMessage = uiText(Res.string.completed_marker_received_but_cleanup_failed)) else current }
            }
        }
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
        clearRobotFiles()
        state.update {
            val uncertain = it.scriptRunPhase.mayBeExecuting
            it.copy(connected = false, connectedAddress = null, reconnecting = false, busy = false,
                statusMessage = uiText(Res.string.disconnected), battery = null, signalQuality = null,
                values = emptyList(), gimbal = null,
                scriptRunPhase = if (uncertain) ScriptRunPhase.UNKNOWN else it.scriptRunPhase,
                scriptMessage = if (uncertain) uiText(Res.string.connection_closed_robot_state_unknown) else it.scriptMessage)
        }
    }
    fun close() { connectionRevision++; desiredTarget = null; reconnectJob?.cancel(); clearRobotFiles(); cancelPushToTalk(); speakerInput.close(); voiceInput.close(); replySpeaker.close(); chat.close(); session?.close(); speech.close(); mediaRequests.close(); ledRequests.close(); mediaScope.cancel(); settingsScope.cancel(); scope.cancel() }
}
