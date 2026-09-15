@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.app

import ai.koog.agents.core.tools.ToolRegistry
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.agent.lab.LabApiCatalog
import cn.elonzh.hanppie.agent.provider.ModelConfigurationTester
import cn.elonzh.hanppie.agent.runtime.SessionHistory
import cn.elonzh.hanppie.agent.tools.DeleteLabScriptTool
import cn.elonzh.hanppie.agent.tools.ExecuteLabPythonTool
import cn.elonzh.hanppie.agent.tools.LabApiReferenceTool
import cn.elonzh.hanppie.agent.tools.ListLabScriptsTool
import cn.elonzh.hanppie.agent.tools.ReadLabScriptTool
import cn.elonzh.hanppie.agent.tools.RobotStatusTool
import cn.elonzh.hanppie.agent.tools.SaveLabScriptTool
import cn.elonzh.hanppie.agent.tools.StopLabTool
import cn.elonzh.hanppie.robot.lab.LabRunEvent
import cn.elonzh.hanppie.robot.lab.LabRunEventType
import cn.elonzh.hanppie.robot.lab.LabRunProtocol
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.DiscoveredRobot
import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.product.RobotProduct
import cn.elonzh.hanppie.robot.product.RobotProductProtocol
import cn.elonzh.hanppie.robot.session.RobotLabSession
import cn.elonzh.hanppie.robot.session.RobotRuntime
import cn.elonzh.hanppie.robot.session.RobotSession
import cn.elonzh.hanppie.robot.session.RobotTarget
import cn.elonzh.hanppie.robot.session.ROBOT_DIRECT_IP
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
import cn.elonzh.hanppie.ui.settings.ConnectionMode
import cn.elonzh.hanppie.ui.settings.ConnectionPreferences
import cn.elonzh.hanppie.ui.settings.RememberedRobot
import cn.elonzh.hanppie.ui.settings.RobotLedColor
import cn.elonzh.hanppie.ui.settings.SettingsController
import cn.elonzh.hanppie.ui.settings.SettingsStore
import cn.elonzh.hanppie.ui.speech.NoSpeechInput
import cn.elonzh.hanppie.ui.speech.SpeechInput
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val consoleLogger = KotlinLogging.logger {}

internal class ConsoleModel(
    override val voiceInput: SpeechInput = NoSpeechInput(),
    private val speakerInput: SpeakerInput = NoSpeakerInput(),
    private val robotRuntime: RobotRuntime,
    private val settingsStore: SettingsStore,
    scriptRepository: ScriptRepository,
    sessionHistory: SessionHistory,
    createAgentHttpClient: () -> HttpClient,
    private val runtimeDefaults: () -> ModelSettings = ::ModelSettings,
    private val applyModelOverrides: (ModelSettings) -> ModelSettings = { it },
    private val prepareNetwork: () -> Unit = {},
    private val clock: Clock = Clock.System,
    private val autoConnectOnStart: Boolean = true,
    private val scriptStartConfirmationTimeoutMillis: Long = 10_000,
) : ConsoleController {
    override var voicePageActive = false
    override val isForeground: Boolean get() = acceptingWork.load()
    override val state = MutableStateFlow(ConsoleState())
    private val settings = SettingsController(
        store = settingsStore,
        runtimeDefaults = runtimeDefaults,
        applyEnvironmentOverrides = applyModelOverrides,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val modelSettings = settings.model
    private val modelTester = ModelConfigurationTester(scope = scope,
        createHttpClient = createAgentHttpClient, clock = clock)
    override val modelCatalogState = modelTester.catalogState
    override val modelTestState = modelTester.state
    override val controlSettings = settings.control
    override val connectionPreferences = MutableStateFlow(ConnectionPreferences.fresh())
    override val settingsBusy = settings.busy
    override val settingsMessage = settings.message
    private val connectionPreferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val scriptLibrary = ScriptLibrary(scriptRepository)
    override val robotFiles = RobotFilesController(scope) { acceptingWork.load() && state.value.connected }
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { scriptLibrary.load() }
    }
    override fun saveSettings() = settings.save()
    override fun loadModelCatalog() = modelTester.loadCatalog(modelSettings.value)
    override fun testModelSettings() = modelTester.test(modelSettings.value)
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
    private val connectionRevision = AtomicLong(0L)
    private val connectionPreferencesMutex = Mutex()
    private val connectionPreferencesLoaded = AtomicBoolean(false)
    override val foregroundState = MutableStateFlow(true)
    private val remoteRevision = AtomicLong(0L)
    override val remoteEnabled = MutableStateFlow(false)
    override val remoteInput = MutableStateFlow(List(5) { 0.0 })
    override val cameraYaw = MutableStateFlow<Double?>(null)
    override val gelSelected = MutableStateFlow(false)
    private val firing = AtomicBoolean(false)
    private var firingJob: Job? = null
    private val gelFireMutex = Mutex()
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
        stopFiring()
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
    override fun startFiring() {
        if (!remoteEnabled.value || !acceptingWork.load() || chat.state.value.running || state.value.busy) return
        firing.store(true)
        if (firingJob?.isActive == true) return
        firingJob = scope.launch {
            try {
                while (firing.load() && remoteEnabled.value && acceptingWork.load() && !chat.state.value.running && !state.value.busy) {
                    val s = session.load() ?: break
                    if (gelSelected.value) {
                        val seq = gelFireMutex.withLock {
                            if (!firing.load() || !remoteEnabled.value) return@withLock null
                            runCatching { s.fireGelOnce() }.getOrNull()
                        }
                        if (seq != null) {
                            log(tr(Res.string.gel_fire_command_sent_seq_value_physical_firing_unconfirmed, seq))
                        } else {
                            break
                        }
                    } else {
                        runCatching { s.fireInfrared() }.onFailure { break }
                        delay(200)
                    }
                }
            } finally {
                firing.store(false)
            }
        }
    }
    override fun stopFiring() {
        firing.store(false)
        if (!gelSelected.value) {
            firingJob?.cancel()
        }
    }
    override fun fire() {
        if (remoteEnabled.value && acceptingWork.load() && !chat.state.value.running && !state.value.busy)
            runCatching { session.load()?.fireInfrared() }.onFailure { state.update { s -> s.copy(error = it.message) } }
    }
    override fun fireGel() {
        if (!remoteEnabled.value || !acceptingWork.load() || chat.state.value.running || state.value.busy) return
        scope.launch {
            val s = session.load() ?: return@launch
            gelFireMutex.withLock {
                runCatching {
                    val sequence = s.fireGelOnce()
                    log(tr(Res.string.gel_fire_command_sent_seq_value_physical_firing_unconfirmed, sequence))
                }.onFailure { error ->
                    val message = error.message ?: error::class.simpleName ?: "Unknown error"
                    log(tr(Res.string.error_value, message))
                    state.update { it.copy(error = message) }
                }
            }
        }
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
    private val agentTools = ToolRegistry {
        tool(RobotStatusTool {
            val snapshot = state.value
            RobotStatusTool.Result(
                connected = snapshot.connected,
                address = snapshot.connectedAddress,
                batteryPercent = snapshot.battery,
                signalQualityPercent = snapshot.signalQuality,
                script = RobotStatusTool.ScriptRun(
                    id = snapshot.scriptRunId,
                    title = snapshot.scriptTitle,
                    phase = snapshot.scriptRunPhase,
                    startedAtEpochMillis = snapshot.scriptStartedAtEpochMillis,
                    finishedAtEpochMillis = snapshot.scriptFinishedAtEpochMillis,
                    recentMessages = snapshot.scriptMessages.takeLast(12),
                ),
            )
        })
        tool(LabApiReferenceTool(LabApiCatalog::query))
        tool(ListLabScriptsTool {
            ListLabScriptsTool.Result(scriptLibrary.savedScripts().map { script ->
                ListLabScriptsTool.Script(
                    name = script.name,
                    sourceLength = script.source.length,
                    updatedAtEpochMillis = script.updatedAtEpochMillis,
                )
            })
        })
        tool(ReadLabScriptTool { name ->
            val script = scriptLibrary.read(name)
            ReadLabScriptTool.Result(
                name = script.name,
                source = script.source,
                createdAtEpochMillis = script.createdAtEpochMillis,
                updatedAtEpochMillis = script.updatedAtEpochMillis,
            )
        })
        tool(SaveLabScriptTool { originalName, name, source ->
            val script = scriptLibrary.save(originalName, name, source)
            SaveLabScriptTool.Result(
                name = script.name,
                created = originalName == null,
                sourceLength = script.source.length,
                updatedAtEpochMillis = script.updatedAtEpochMillis,
            )
        })
        tool(DeleteLabScriptTool { name ->
            val script = scriptLibrary.deleteByName(name)
            DeleteLabScriptTool.Result(script.name, DeleteLabScriptTool.Status.DELETED)
        })
        tool(ExecuteLabPythonTool { source -> agentOperation {
            check(!state.value.scriptRunPhase.mayBeExecuting) { tr(Res.string.stop_the_script_with_unknown_state_first) }
            haltRemote()
            session.load()?.exitRemote()
            val scriptRunId = startScript(source, "Hanppie-Agent")
            ExecuteLabPythonTool.Result(ExecuteLabPythonTool.Status.START_COMMAND_SENT, scriptRunId)
        } })
        tool(StopLabTool { agentOperation {
            stopScriptWithoutConfirmation()
            StopLabTool.Result(StopLabTool.Status.STOP_COMMAND_SENT)
        } })
    }
    override val chat = ChatAgent(
        toolRegistry = agentTools,
        createHttpClient = createAgentHttpClient,
        sessions = sessionHistory,
    )

    private suspend fun <T> agentOperation(block: suspend () -> T): T {
        check(acceptingWork.load() && state.value.connected) { tr(Res.string.robot_disconnected_or_app_not_in_foreground) }
        val snapshot = state.value
        check(!snapshot.busy && state.compareAndSet(snapshot, snapshot.copy(busy = true))) { tr(Res.string.another_operation_is_in_progress) }
        return try { block() } finally { state.update { it.copy(busy = false) } }
    }

    init {
        connectionPreferencesScope.launch {
            try {
                val loaded = settingsStore.loadConnection().normalized()
                connectionPreferences.value = loaded
                settingsStore.saveConnection(loaded)
            } catch (error: Exception) {
                log(tr(Res.string.connection_preferences_load_failed_value, error.message))
            }
            connectionPreferencesLoaded.store(true)
            if (autoConnectOnStart) discover()
        }
    }

    override fun setForeground(foreground: Boolean) {
        acceptingWork.store(foreground)
        foregroundState.value = foreground
        if (!foreground) { haltRemote(); voiceInput.cancel() }
    }

    override fun log(message: String) {
        val line = "${formatLocalDateTime(clock.now().toEpochMilliseconds(), DateTimeStyle.LOG_TIME)}  $message"
        state.update { it.copy(logs = (it.logs + line).takeLast(500)) }
    }

    private fun work(allowDuringChat: Boolean = false, connecting: Boolean = false, block: suspend () -> Unit) {
        if (chat.state.value.running && !allowDuringChat) {
            state.update { it.copy(error = tr(Res.string.cancel_the_current_chat_before_changing_connections_or_using)) }; return
        }
        val snapshot = state.value
        if (!acceptingWork.load() || snapshot.busy ||
            !state.compareAndSet(snapshot, snapshot.copy(busy = true, connecting = connecting, error = null))) return
        scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                consoleLogger.error(error) { "Console operation failed connecting=$connecting" }
                val message = error.message ?: error.toString()
                log(tr(Res.string.error_value,message))
                state.update { it.copy(error = message) }
            } finally { state.update { it.copy(busy = false, connecting = false) } }
        }
    }

    override fun discover() {
        work(connecting = true) { autoConnect() }
    }

    override fun pairRouter(ssid: String, password: String) {
        work(connecting = true) {
            awaitConnectionPreferences()
            check(session.load() == null) { tr(Res.string.disconnect_the_current_session_first) }
            val normalizedSsid = ssid
            val currentPreferences = connectionPreferences.value.normalized()
            val normalizedAppId = currentPreferences.appId
            // RouterProvisioning performs the byte-length and password validation before the UI reaches here.
            cn.elonzh.hanppie.robot.protocol.RouterProvisioning.encode(normalizedSsid, password, normalizedAppId)
            updateConnectionPreferences { it.copy(routerSsid = normalizedSsid, routerPassword = password) }
            val revision = connectionRevision.addAndFetch(1)
            prepareNetwork()
            state.update { it.copy(statusMessage = uiText(Res.string.waiting_for_robot_to_scan_qr), devices = emptyList()) }
            log(tr(Res.string.waiting_for_robot_to_scan_qr))
            val pairing = robotRuntime.waitForRouterPairing(normalizedAppId)
            val robot = pairing.robot
            state.update { it.copy(devices = listOf(robot)) }
            connectTarget(RobotTarget(robot.ip, normalizedAppId), revision, ConnectionMode.ROUTER, robot)
            try {
                robotRuntime.acknowledgeRouterPairing(pairing, normalizedAppId)
            } catch (error: Exception) {
                connectionRevision.addAndFetch(1)
                session.exchange(null)?.close()
                lab.store(null)
                robotFiles.clear()
                state.update { it.copy(connected = false, connectedAddress = null, connecting = false,
                    statusMessage = uiText(Res.string.connection_failed)) }
                throw error
            }
            log(tr(Res.string.router_pairing_completed))
        }
    }

    override fun connect(ip: String, appId: String) {
        work(connecting = true) {
            awaitConnectionPreferences()
            check(session.load() == null) { tr(Res.string.disconnect_the_current_session_first) }
            val revision = connectionRevision.addAndFetch(1)
            prepareNetwork()
            val target = RobotTarget(ip.trim(), appId.trim())
            val device = state.value.devices.firstOrNull { it.ip == target.ip }
            val remembered = connectionPreferences.value.robots.firstOrNull {
                it.ip == target.ip && it.appId.equals(target.appId, ignoreCase = true)
            }
            connectTarget(target, revision, remembered?.mode ?: ConnectionMode.UNKNOWN, device)
        }
    }

    private data class ConnectionCandidate(
        val target: RobotTarget,
        val mode: ConnectionMode,
        val robot: DiscoveredRobot? = null,
    )

    private suspend fun autoConnect() {
        awaitConnectionPreferences()
        check(session.load() == null) { tr(Res.string.disconnect_the_current_session_first) }
        val revision = connectionRevision.addAndFetch(1)
        prepareNetwork()
        state.update { it.copy(statusMessage = uiText(Res.string.automatically_finding_robot), devices = emptyList()) }
        log(tr(Res.string.listening_for_robots_on_the_local_network))
        val devices = robotRuntime.discover(timeoutMillis = 3_500)
        state.update { it.copy(devices = devices) }
        val preferences = connectionPreferences.value.normalized()
        val knownByMac = preferences.robots.filter { it.mac.isNotBlank() }.associateBy { it.mac.uppercase() }
        val recognized = devices.filter { device ->
            device.mac.uppercase() in knownByMac || preferences.robots.any { it.ip == device.ip }
        }
        val discoverableCandidates = when {
            recognized.isNotEmpty() -> recognized
            devices.size == 1 -> devices
            else -> emptyList()
        }
        val candidates = buildList {
            discoverableCandidates.sortedBy { device ->
                preferences.robots.indexOfFirst { saved -> saved.mac.equals(device.mac, ignoreCase = true) }
                    .let { if (it < 0) Int.MAX_VALUE else it }
            }.forEach { device ->
                val remembered = knownByMac[device.mac.uppercase()]
                    ?: preferences.robots.firstOrNull { it.ip == device.ip }
                add(ConnectionCandidate(
                    RobotTarget(device.ip, remembered?.appId ?: preferences.appId),
                    remembered?.mode ?: ConnectionMode.UNKNOWN,
                    device,
                ))
            }
            preferences.robots.firstOrNull()?.let { saved ->
                add(ConnectionCandidate(RobotTarget(saved.ip, saved.appId), saved.mode))
            }
            add(ConnectionCandidate(RobotTarget(ROBOT_DIRECT_IP, preferences.appId), ConnectionMode.DIRECT))
        }.distinctBy { it.target.ip }

        var lastFailure: Exception? = null
        for (candidate in candidates) {
            try {
                connectTarget(candidate.target, revision, candidate.mode, candidate.robot, automaticProbe = true)
                log(tr(Res.string.automatic_connection_succeeded_value, candidate.target.ip))
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        log(tr(Res.string.found_value_robots_connected_robots_may_not_broadcast_enter, devices.size))
        throw lastFailure ?: IllegalStateException(tr(Res.string.no_robot_found_automatic_connection_failed))
    }

    private suspend fun connectTarget(
        target: RobotTarget,
        revision: Long,
        mode: ConnectionMode,
        robot: DiscoveredRobot? = null,
        automaticProbe: Boolean = false,
    ) {
        state.update { ConsoleState(busy = true, connecting = true,
            statusMessage = uiText(Res.string.connecting_to_value,target.ip), logs = it.logs, devices = it.devices) }
        val attemptTarget = if (automaticProbe) target.forAutomaticProbe() else target
        val candidate = newSession(attemptTarget)
        session.store(candidate)
        try {
            candidate.connect()
            candidate.safetyStop()
            currentCoroutineContext().ensureActive()
            check(revision == connectionRevision.load()) { tr(Res.string.connection_cancelled) }
            session.store(candidate)
            lab.store(candidate.lab)
            robotFiles.attach(candidate.files)
            state.update { it.copy(connected = true, connectedAddress = target.ip, connecting = false,
                statusMessage = uiText(Res.string.connected_to_value,target.ip), error = null,
                robotProduct = candidate.product, devices = emptyList()) }
            runCatching {
                updateConnectionPreferences { preferences ->
                    val previous = preferences.robots.firstOrNull {
                        it.ip == target.ip && it.appId.equals(target.appId, ignoreCase = true)
                    }
                    preferences.remember(
                        RememberedRobot(
                            ip = target.ip,
                            appId = target.appId,
                            mac = robot?.mac ?: previous?.mac.orEmpty(),
                            mode = if (mode == ConnectionMode.UNKNOWN) previous?.mode ?: mode else mode,
                        ),
                    )
                }
            }.onFailure { log(tr(Res.string.connection_preferences_save_failed_value, it.message)) }
        } catch (error: Exception) {
            candidate.close()
            session.compareAndSet(candidate, null)
            if (revision == connectionRevision.load()) {
                state.update { it.copy(connecting = false, statusMessage = uiText(Res.string.connection_failed)) }
            }
            throw error
        }
    }

    private suspend fun updateConnectionPreferences(
        transform: (ConnectionPreferences) -> ConnectionPreferences,
    ): ConnectionPreferences = connectionPreferencesMutex.withLock {
        val updated = transform(connectionPreferences.value).normalized()
        settingsStore.saveConnection(updated)
        connectionPreferences.value = updated
        updated
    }

    private suspend fun awaitConnectionPreferences() {
        withTimeout(5_000) {
            while (!connectionPreferencesLoaded.load()) delay(10)
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
        stopFiring()
        remoteInput.value = List(5) { 0.0 }
        cameraYaw.value = null
        scope.launch {
            if (session.load() !== candidate) return@launch
            session.store(null)
            lab.store(null)
            robotFiles.clear()
            candidate.close()
            log(reason)
            state.update { it.lost(reason).copy(busy = false, connecting = false) }
        }
    }

    override fun disconnect() = work {
        connectionRevision.addAndFetch(1)
        haltRemote()
        session.exchange(null)?.close(); lab.store(null)
        robotFiles.clear()
        state.update {
            val uncertain = it.scriptRunPhase.mayBeExecuting
            it.copy(connected = false, connectedAddress = null, connecting = false,
                statusMessage = uiText(Res.string.disconnected), error = null,
                scriptRunPhase = if (uncertain) ScriptRunPhase.UNKNOWN else it.scriptRunPhase,
                scriptMessage = if (uncertain) uiText(Res.string.session_ended_robot_state_unknown) else it.scriptMessage,
                robotProduct = RobotProduct(), battery = null, signalQuality = null, values = emptyList(), gimbal = null)
        }
        log(tr(Res.string.connection_closed_scripts_on_the_robot_may_still_be))
    }

    override fun runScript(source: String, title: String) = work {
        haltRemote(); session.load()?.exitRemote()
        startScript(source, title)
    }

    private suspend fun startScript(source: String, title: String): String {
        val controller = checkNotNull(lab.load()) { tr(Res.string.robot_is_not_connected) }
        val startedAt = clock.now().toEpochMilliseconds()
        state.update { it.copy(scriptRunId = null, scriptTitle = title,
            scriptRunPhase = ScriptRunPhase.UPLOADING, scriptStartedAtEpochMillis = startedAt,
            scriptFinishedAtEpochMillis = null, scriptMessage = uiText(Res.string.uploading),
            scriptMessages = listOf(tr(Res.string.script_trace_uploading))) }
        val upload = try {
            controller.upload(source, title)
        } catch (error: Exception) {
            state.update { it.copy(scriptRunPhase = ScriptRunPhase.FAILED,
                scriptFinishedAtEpochMillis = clock.now().toEpochMilliseconds(), scriptMessage = uiText(Res.string.upload_failed)) }
            throw error
        }
        state.update { it.copy(scriptRunId = upload.runId, scriptRunPhase = ScriptRunPhase.STARTING,
            scriptMessage = uiText(Res.string.waiting_for_script_start),
            scriptMessages = (it.scriptMessages + tr(
                Res.string.script_trace_uploaded_value, upload.runId, upload.digest,
            )).takeLast(200)) }
        val packetBaseline = state.value.packets
        val labMessageBaseline = state.value.labMessagePackets
        try {
            check(controller.start() == upload.runId) { "启动运行标识不匹配" }
        } catch (error: Exception) {
            state.update { it.copy(scriptRunPhase = ScriptRunPhase.UNKNOWN,
                scriptMessage = uiText(Res.string.script_start_state_unknown)) }
            throw error
        }
        state.update { current -> if (current.scriptRunId == upload.runId &&
            current.scriptRunPhase == ScriptRunPhase.STARTING) current.copy(
            scriptMessages = (current.scriptMessages + tr(
                Res.string.script_trace_start_sent_value, upload.runId,
            )).takeLast(200),
        ) else current }
        scope.launch {
            delay(scriptStartConfirmationTimeoutMillis)
            state.update { current ->
                if (current.scriptRunId == upload.runId && current.scriptRunPhase == ScriptRunPhase.STARTING) {
                    current.copy(
                        scriptRunPhase = ScriptRunPhase.UNKNOWN,
                        scriptMessage = uiText(Res.string.script_start_confirmation_timed_out),
                        scriptMessages = (current.scriptMessages + tr(
                            Res.string.script_trace_start_timeout_value,
                            upload.runId,
                            current.packets - packetBaseline,
                            current.labMessagePackets - labMessageBaseline,
                        )).takeLast(200),
                    )
                } else {
                    current
                }
            }
        }
        return upload.runId
    }

    override fun stop() = work(allowDuringChat = true) {
        chat.cancelAndJoin()
        stopScriptWithoutConfirmation()
    }

    private suspend fun stopScriptWithoutConfirmation() {
        state.update { it.copy(scriptRunPhase = ScriptRunPhase.STOPPING, scriptMessage = uiText(Res.string.stopping_script)) }
        try {
            checkNotNull(lab.load()) { tr(Res.string.robot_is_not_connected) }.stop()
            state.update { it.copy(
                scriptRunPhase = ScriptRunPhase.STOP_UNCONFIRMED,
                scriptMessage = uiText(Res.string.stop_command_sent_robot_stop_is_unconfirmed),
                scriptFinishedAtEpochMillis = null,
            ) }
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
        val labMessageFrame = frame.valid && frame.set == 0x3f && frame.id == 0xa4
        state.update { old -> old.copy(packets = old.packets + 1,
            labMessagePackets = old.labMessagePackets + if (labMessageFrame) 1 else 0,
            frames = (old.frames + line).takeLast(250),
            robotProduct = RobotProductProtocol.updated(old.robotProduct, frame) ?: old.robotProduct,
            battery = if (motion == null) old.battery else motion.batteryPercent,
            signalQuality = signalQuality ?: old.signalQuality,
            gimbal = gimbal ?: old.gimbal,
            scriptMessages = when {
                runEvent != null && eventRunId == old.scriptRunId -> (old.scriptMessages + tr(
                    Res.string.script_trace_lifecycle_value,
                    requireNotNull(eventType).name,
                    requireNotNull(eventRunId),
                )).takeLast(200)
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
        chat.cancelAndJoin()
        scope.coroutineContext[Job]?.children?.toList()?.forEach { it.cancelAndJoin() }
        session.exchange(null)?.close(); lab.store(null)
        robotFiles.clear()
        state.update {
            val uncertain = it.scriptRunPhase.mayBeExecuting
            it.copy(connected = false, connectedAddress = null, connecting = false, busy = false,
                statusMessage = uiText(Res.string.disconnected), battery = null, signalQuality = null,
                values = emptyList(), gimbal = null,
                scriptRunPhase = if (uncertain) ScriptRunPhase.UNKNOWN else it.scriptRunPhase,
                scriptMessage = if (uncertain) uiText(Res.string.connection_closed_robot_state_unknown) else it.scriptMessage)
        }
    }
    override fun close() { connectionRevision.addAndFetch(1); robotFiles.close(); cancelPushToTalk(); speakerInput.close(); voiceInput.close(); modelTester.close(); chat.close(); session.exchange(null)?.close(); lab.store(null); mediaRequests.close(); ledRequests.close(); mediaScope.cancel(); connectionPreferencesScope.cancel(); settings.close(); scope.cancel() }
}
