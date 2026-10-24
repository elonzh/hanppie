@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.network.RobotWifiService
import cn.elonzh.hanppie.robot.network.RobotWifiProfile
import cn.elonzh.hanppie.ui.robot.network.RobotWifiController
import cn.elonzh.hanppie.ui.robot.network.WifiVerification
import cn.elonzh.hanppie.robot.network.WifiRadioTarget

import ai.koog.agents.core.tools.ToolRegistry
import cn.elonzh.hanppie.agent.provider.ModelConfigurationTester
import cn.elonzh.hanppie.agent.runtime.SessionHistory
import cn.elonzh.hanppie.agent.skills.SkillLibrary
import cn.elonzh.hanppie.agent.tools.DeleteLabScriptTool
import cn.elonzh.hanppie.agent.tools.ExecuteLabPythonTool
import cn.elonzh.hanppie.agent.tools.ListLabScriptsTool
import cn.elonzh.hanppie.agent.tools.ReadLabScriptTool
import cn.elonzh.hanppie.agent.tools.ReadSkillTool
import cn.elonzh.hanppie.agent.tools.RobotStatusTool
import cn.elonzh.hanppie.agent.tools.SaveLabScriptTool
import cn.elonzh.hanppie.agent.tools.StopLabTool
import cn.elonzh.hanppie.resources.robot_power_task_active
import cn.elonzh.hanppie.resources.challenge_not_ready
import cn.elonzh.hanppie.resources.arm_task_active
import cn.elonzh.hanppie.resources.arm_target_changed
import cn.elonzh.hanppie.resources.extensions_task_active
import cn.elonzh.hanppie.resources.extensions_target_changed
import cn.elonzh.hanppie.resources.robot_power_target_changed
import cn.elonzh.hanppie.resources.calibration_finish_first
import cn.elonzh.hanppie.resources.firmware_finish_first
import cn.elonzh.hanppie.resources.firmware_task_active
import cn.elonzh.hanppie.resources.firmware_target_changed
import cn.elonzh.hanppie.resources.calibration_task_active
import cn.elonzh.hanppie.resources.calibration_target_changed
import cn.elonzh.hanppie.resources.addressing_finish_first
import cn.elonzh.hanppie.resources.addressing_task_active
import cn.elonzh.hanppie.resources.addressing_target_changed
import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.sdr_verification_failed
import cn.elonzh.hanppie.resources.wifi_verification_failed
import cn.elonzh.hanppie.resources.wifi_target_changed
import cn.elonzh.hanppie.resources.wifi_task_active
import cn.elonzh.hanppie.resources.another_operation_is_in_progress
import cn.elonzh.hanppie.resources.automatic_connection_succeeded_value
import cn.elonzh.hanppie.resources.automatically_finding_robot
import cn.elonzh.hanppie.resources.cancel_the_current_chat_before_changing_connections_or_using
import cn.elonzh.hanppie.resources.completed_marker_received_but_cleanup_failed
import cn.elonzh.hanppie.resources.connected_to_value
import cn.elonzh.hanppie.resources.connecting_to_value
import cn.elonzh.hanppie.resources.connection_cancelled
import cn.elonzh.hanppie.resources.connection_closed_robot_state_unknown
import cn.elonzh.hanppie.resources.connection_closed_scripts_on_the_robot_may_still_be
import cn.elonzh.hanppie.resources.connection_failed
import cn.elonzh.hanppie.resources.connection_preferences_load_failed_value
import cn.elonzh.hanppie.resources.connection_preferences_save_failed_value
import cn.elonzh.hanppie.resources.disconnect_the_current_session_first
import cn.elonzh.hanppie.resources.disconnected
import cn.elonzh.hanppie.resources.error_value
import cn.elonzh.hanppie.resources.finishing_completed_script
import cn.elonzh.hanppie.resources.found_value_robots_connected_robots_may_not_broadcast_enter
import cn.elonzh.hanppie.resources.gel_fire_command_sent_seq_value_physical_firing_unconfirmed
import cn.elonzh.hanppie.resources.heading_like_uncalibrated
import cn.elonzh.hanppie.resources.leaving_remote_control_value
import cn.elonzh.hanppie.resources.led_request_failed_value
import cn.elonzh.hanppie.resources.listening_for_robots_on_the_local_network
import cn.elonzh.hanppie.resources.media_request_failed_value
import cn.elonzh.hanppie.resources.media_request_queue_is_full
import cn.elonzh.hanppie.resources.media_stop_queue_is_full
import cn.elonzh.hanppie.resources.no_robot_found_automatic_connection_failed
import cn.elonzh.hanppie.resources.push_to_talk_sent_packets_value
import cn.elonzh.hanppie.resources.remote_stopped_connection_unresponsive
import cn.elonzh.hanppie.resources.request_queue_is_closed
import cn.elonzh.hanppie.resources.robot_disconnected_or_app_not_in_foreground
import cn.elonzh.hanppie.resources.robot_is_not_connected
import cn.elonzh.hanppie.resources.router_pairing_completed
import cn.elonzh.hanppie.resources.script_completed
import cn.elonzh.hanppie.resources.script_failed
import cn.elonzh.hanppie.resources.script_running
import cn.elonzh.hanppie.resources.script_start_confirmation_timed_out
import cn.elonzh.hanppie.resources.script_start_state_unknown
import cn.elonzh.hanppie.resources.script_stop_state_unknown
import cn.elonzh.hanppie.resources.script_trace_completed
import cn.elonzh.hanppie.resources.script_trace_failed
import cn.elonzh.hanppie.resources.script_trace_start_sent
import cn.elonzh.hanppie.resources.script_trace_start_timeout
import cn.elonzh.hanppie.resources.script_trace_started
import cn.elonzh.hanppie.resources.script_trace_uploaded
import cn.elonzh.hanppie.resources.script_trace_uploading
import cn.elonzh.hanppie.resources.session_ended_robot_state_unknown
import cn.elonzh.hanppie.resources.stop_command_sent_robot_stop_is_unconfirmed
import cn.elonzh.hanppie.resources.stop_the_lab_script_first
import cn.elonzh.hanppie.resources.stop_the_script_with_unknown_state_first
import cn.elonzh.hanppie.resources.stopping_script
import cn.elonzh.hanppie.resources.upload_failed
import cn.elonzh.hanppie.resources.uploading
import cn.elonzh.hanppie.resources.waiting_for_robot_to_scan_qr
import cn.elonzh.hanppie.resources.waiting_for_script_start
import cn.elonzh.hanppie.resources.display_task_active
import cn.elonzh.hanppie.resources.display_target_changed
import cn.elonzh.hanppie.resources.motion_task_active
import cn.elonzh.hanppie.resources.motion_target_changed
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.robot.lab.ui.LabUiState
import cn.elonzh.hanppie.robot.lab.ui.LabUiValue
import cn.elonzh.hanppie.robot.lab.ui.LabWidgetKey
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.robot.media.VideoResolution
import cn.elonzh.hanppie.robot.product.RobotProduct
import cn.elonzh.hanppie.robot.product.RobotProductProtocol
import cn.elonzh.hanppie.robot.protocol.DiscoveredRobot
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.session.RobotLabSession
import cn.elonzh.hanppie.robot.session.RobotRuntime
import cn.elonzh.hanppie.robot.session.RobotSession
import cn.elonzh.hanppie.robot.tracking.TrackingState
import cn.elonzh.hanppie.robot.session.RobotTarget
import cn.elonzh.hanppie.robot.telemetry.Telemetry
import cn.elonzh.hanppie.ui.chat.ChatAgent
import cn.elonzh.hanppie.ui.i18n.DateTimeStyle
import cn.elonzh.hanppie.ui.i18n.formatLocalDateTime
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.robot.audio.LabAudioImporter
import cn.elonzh.hanppie.ui.robot.audio.LabAudioPlayer
import cn.elonzh.hanppie.ui.robot.audio.NoLabAudioImporter
import cn.elonzh.hanppie.ui.robot.audio.NoLabAudioPlayer
import cn.elonzh.hanppie.ui.robot.files.RobotFilesController
import cn.elonzh.hanppie.ui.robot.maintenance.RobotFirmwareController
import cn.elonzh.hanppie.ui.robot.remote.DriveSpeed
import cn.elonzh.hanppie.ui.robot.remote.NoSpeakerInput
import cn.elonzh.hanppie.ui.robot.remote.SpeakerInput
import cn.elonzh.hanppie.ui.scripts.ScriptAudioLibrary
import cn.elonzh.hanppie.ui.scripts.ScriptLibrary
import cn.elonzh.hanppie.ui.scripts.ScriptRepository
import cn.elonzh.hanppie.ui.settings.ConnectionMode
import cn.elonzh.hanppie.ui.settings.ConnectionPreferences
import cn.elonzh.hanppie.ui.settings.ModelSettings
import cn.elonzh.hanppie.ui.settings.RememberedRobot
import cn.elonzh.hanppie.ui.settings.RobotLedColor
import cn.elonzh.hanppie.ui.settings.SettingsController
import cn.elonzh.hanppie.ui.settings.SettingsStore
import cn.elonzh.hanppie.ui.speech.NoSpeechInput
import cn.elonzh.hanppie.ui.speech.SpeechInput
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import io.github.vinceglb.filekit.filesDir
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Clock

private val consoleLogger = KotlinLogging.logger {}

internal class ConsoleModel(
    override val voiceInput: SpeechInput = NoSpeechInput(),
    private val speakerInput: SpeakerInput = NoSpeakerInput(),
    private val robotRuntime: RobotRuntime,
    private val settingsStore: SettingsStore,
    private val scriptRepository: ScriptRepository,
    private val audioImporter: LabAudioImporter = NoLabAudioImporter(),
    private val audioPlayer: LabAudioPlayer = NoLabAudioPlayer(),
    sessionHistory: SessionHistory,
    skills: suspend () -> SkillLibrary,
    createAgentHttpClient: () -> HttpClient,
    private val runtimeDefaults: () -> ModelSettings = ::ModelSettings,
    private val applyModelOverrides: (ModelSettings) -> ModelSettings = { it },
    private val prepareNetwork: () -> Unit = {},
    private val clock: Clock = Clock.System,
    private val autoConnectOnStart: Boolean = true,
    private val scriptStartConfirmationTimeoutMillis: Long = 10_000,
    private val albumDirectory: () -> io.github.vinceglb.filekit.PlatformFile = {
        io.github.vinceglb.filekit.PlatformFile(io.github.vinceglb.filekit.FileKit.filesDir, "robot-media")
    },
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
    override val mediaSettings = settings.media
    override val displaySettings get() = settings.display
    override val controlSettings = settings.control
    override val connectionPreferences = MutableStateFlow(ConnectionPreferences.fresh())
    override val settingsBusy = settings.busy
    override val settingsMessage = settings.message
    private val connectionPreferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val scriptLibrary = ScriptLibrary(scriptRepository)
    override val scriptAudio = ScriptAudioLibrary(scriptRepository, audioImporter, audioPlayer)
    override val robotFiles: RobotFilesController = RobotFilesController(scope, blockedReason = {
        if (robotFirmware.state.value.blocksControl || session.load()?.firmware?.state?.value?.blocksControl == true)
            tr(Res.string.firmware_finish_first) else null
    }) { acceptingWork.load() && state.value.connected }
    override val robotAudio = cn.elonzh.hanppie.ui.robot.audio.RobotAudioController(scope)
    override val cameraZoom = cn.elonzh.hanppie.ui.robot.remote.CameraZoomController(scope)
    override val robotCamera = cn.elonzh.hanppie.ui.robot.camera.RobotCameraController(scope)
    override val robotAlbum = cn.elonzh.hanppie.ui.robot.album.RobotAlbumController(scope, albumDirectory)
    override val robotPower = cn.elonzh.hanppie.ui.robot.device.RobotPowerController(scope, ::changePower,
        { source -> if (session.load()?.power === source) closeConnection(preserveNetworkRecovery = false) })
    override val robotInformation = cn.elonzh.hanppie.ui.robot.device.RobotInformationController(scope)
    override val robotHealth = cn.elonzh.hanppie.ui.robot.device.RobotHealthController(scope)
    override val robotArm: cn.elonzh.hanppie.ui.robot.arm.RobotArmController = cn.elonzh.hanppie.ui.robot.arm.RobotArmController(scope,
        ::changeArm, ::canObserveArm, { source -> canObserveArm(source) && remoteEnabled.value && !state.value.busy &&
            !tracking.value.blocksManualControl && !vision.value.ownsPipeline && videoFrames.isFresh(state.value.connectedAddress) })
    override val robotExtensions = cn.elonzh.hanppie.ui.robot.extensions.RobotExtensionController(scope, ::changeExtensions, ::canObserveExtensions)
    override val robotDisplay = cn.elonzh.hanppie.ui.robot.display.RobotDisplayController(scope) { source, action ->
        check(robotWifi.state.value.recovery == null && robotSdr.state.value.recovery == null) { tr(Res.string.display_task_active) }
        changeDevice({ it.display === source }, Res.string.display_task_active, Res.string.display_target_changed, action)
    }
    override val robotMotion = cn.elonzh.hanppie.ui.robot.remote.RobotMotionController(scope) { source, action ->
        check(robotWifi.state.value.recovery == null && robotSdr.state.value.recovery == null) { tr(Res.string.motion_task_active) }
        changeDevice({ it.motion === source }, Res.string.motion_task_active, Res.string.motion_target_changed, action)
    }
    override val robotFirmware: RobotFirmwareController = RobotFirmwareController(scope, ::changeFirmware,
        { source -> if (session.load()?.firmware === source) {
            haltRemote()
            robotArm.refreshVisibility(); robotExtensions.refreshVisibility()
            if (robotFiles.state.value.busy) robotFiles.clear()
        } })
    override val robotCalibration = cn.elonzh.hanppie.ui.robot.maintenance.RobotCalibrationController(scope, ::changeCalibration,
        { source -> isForeground && session.load()?.calibration === source && !state.value.busy && !chat.state.value.running })
    override val robotAddressing = cn.elonzh.hanppie.ui.robot.maintenance.RobotAddressingController(scope, ::changeAddressing,
        { source -> isForeground && session.load()?.addressing === source && !state.value.busy && !chat.state.value.running })
    override val robotWifi = RobotWifiController(scope,
        ::changeWifi, ::retireWifiSession, ::reconnectWifi)
    override val robotSdr = cn.elonzh.hanppie.ui.robot.network.RobotSdrController(scope,
        { source, action ->
            check(robotWifi.state.value.recovery == null) { tr(Res.string.wifi_task_active) }
            changeNetwork({ it.sdr === source }, action)
        },
        { source -> if (session.load()?.sdr === source) closeConnection(preserveNetworkRecovery = true) },
        ::reconnectSdr)
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { scriptLibrary.load() }
        scope.launch {
            robotCamera.state.map { it.storage?.let { card -> !card.inserted || card.formatting || card.hasError } == true }
                .distinctUntilChanged().filter { it }.collect { robotAlbum.invalidateStorage() }
        }
    }
    override fun saveSettings() {
        settings.save()
        robotAudio.invalidate()
        val current = session.load()
        if (current != null && current.connected) {
            scope.launch {
                runCatching { current.setSpeakerVolume(mediaSettings.value.speakerVolume) }
            }
        }
    }
    override fun loadModelCatalog() = modelTester.loadCatalog(modelSettings.value)
    override fun testModelSettings() = modelTester.test(modelSettings.value)
    override fun restoreDefaultSettings() {
        settings.restoreDefaults()
        robotAudio.invalidate()
        val current = session.load()
        if (current != null && current.connected) {
            scope.launch {
                runCatching { current.setSpeakerVolume(mediaSettings.value.speakerVolume) }
            }
        }
    }
    private data class MediaRequest(
        val session: RobotSession,
        val start: Boolean,
        val audio: Boolean,
        val resolution: VideoResolution = VideoResolution.R720P,
    )
    private data class LedRequest(val session: RobotSession, val color: RobotLedColor?)
    private val mediaRequests = kotlinx.coroutines.channels.Channel<MediaRequest>(16)
    private val ledRequests = kotlinx.coroutines.channels.Channel<LedRequest>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val mediaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        mediaScope.launch {
            for (request in mediaRequests) if (request.session === session.load() && request.session.connected &&
                (!request.start || robotPower.state.value.phase != cn.elonzh.hanppie.ui.robot.device.PowerPhase.ACTION && !robotAddressing.state.value.blocksControl && !robotCalibration.state.value.blocksControl && !robotFirmware.state.value.blocksControl)) {
                runCatching { request.session.media(request.start, request.audio, request.resolution) }
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
    override val remoteInputRevision: Long get() = remoteRevision.load()
    override val tracking = MutableStateFlow(TrackingState())
    private var trackingObserver: Job? = null
    override val vision = MutableStateFlow(cn.elonzh.hanppie.robot.vision.VisionState())
    private var visionObserver: Job? = null
    override val challenge = MutableStateFlow(cn.elonzh.hanppie.robot.challenge.ChallengeState())
    override val gunCooling = MutableStateFlow(cn.elonzh.hanppie.robot.remote.GunCoolingState())
    private var gunCoolingObserver: Job? = null
    override fun coolGun() {
        val current = session.load() ?: return
        if (!remoteEnabled.value || !isForeground || state.value.busy || chat.state.value.running ||
            !videoFrames.isFresh(state.value.connectedAddress)) return
        stopFiring()
        runCatching { current.coolGun() }.onFailure { log(it.message.orEmpty()) }
    }
    private var challengeObserver: Job? = null
    override fun startChallenge(configuration: cn.elonzh.hanppie.robot.challenge.ChallengeConfiguration): String? {
        val current = session.load() ?: return tr(cn.elonzh.hanppie.resources.Res.string.robot_is_not_connected)
        val round = current.challenge.value
        if (round.ownsRound && !(round.phase == cn.elonzh.hanppie.robot.challenge.ChallengePhase.RESULT && round.stopConfirmed))
            return tr(Res.string.challenge_not_ready)
        if (!remoteEnabled.value || !isForeground || !acceptingWork.load() || state.value.busy || chat.state.value.running ||
            tracking.value.blocksManualControl || !videoFrames.isFresh(state.value.connectedAddress)) return tr(cn.elonzh.hanppie.resources.Res.string.challenge_not_ready)
        clearRemoteInputs()
        return runCatching { current.startChallenge(configuration) }.fold(onSuccess = { null },
            onFailure = { error -> log(error.message.orEmpty()); tr(Res.string.challenge_not_ready) })
    }
    override fun stopChallenge() { stopFiring(); runCatching { session.load()?.stopChallenge() } }
    override fun dismissChallenge() { runCatching { session.load()?.dismissChallenge() } }
    override val labUi = MutableStateFlow(LabUiState())
    private var labUiObserver: Job? = null
    private var labUiVisibleRun: String? = null
    override fun setLabUiVisible(runId: String, visible: Boolean) {
        if (visible && labUi.value.runId != runId) return
        if (visible) labUiVisibleRun = runId else if (labUiVisibleRun == runId) labUiVisibleRun = null
        session.load()?.labUi?.setVisible(runId, visible && isForeground &&
            state.value.scriptRunPhase == ScriptRunPhase.RUNNING && !state.value.busy && !chat.state.value.running)
    }
    override fun labUiInteract(runId: String, key: LabWidgetKey, token: Long, event: Int, value: LabUiValue?, final: Boolean) {
        if (labUi.value.runId != runId || !isForeground || state.value.busy || chat.state.value.running || state.value.scriptRunPhase != ScriptRunPhase.RUNNING) return
        runCatching { session.load()?.labUi?.interact(runId, key, token, event, value, final) }
            .onFailure { log(it.message.orEmpty()) }
    }
    override fun startVision(mode: cn.elonzh.hanppie.robot.vision.VisionMode, color: cn.elonzh.hanppie.robot.vision.VisionColor?) {
        val current = session.load() ?: return
        if (!remoteEnabled.value || !isForeground || !acceptingWork.load() || state.value.busy || chat.state.value.running ||
            tracking.value.blocksManualControl || !videoFrames.isFresh(state.value.connectedAddress)) return
        gimbalCentering.cancel(); stopFiring()
        runCatching { current.startVision(mode, color) }.onFailure { log(it.message.orEmpty()) }
    }
    override fun stopVision() { runCatching { session.load()?.stopVision() } }
    private fun trackingAction(action: (RobotSession) -> Unit) {
        val current = session.load() ?: return
        if (!remoteEnabled.value || !isForeground || state.value.busy || chat.state.value.running) return
        gimbalCentering.cancel(); stopFiring()
        runCatching { action(current) }.onFailure { error ->
            runCatching { current.stopTracking() }
            log(error.message.orEmpty())
        }
    }
    override fun startObjectTracking() {
        if (videoFrames.isFresh(state.value.connectedAddress)) trackingAction { it.startTracking() }
    }
    override fun selectTrackingTarget(x: Float, y: Float) = trackingAction { it.selectTrackingTarget(x, y) }
    override fun confirmObjectTracking() {
        if (videoFrames.isFresh(state.value.connectedAddress)) trackingAction { it.confirmTracking() }
    }
    override fun stopObjectTracking() { runCatching { session.load()?.stopTracking() } }
    override val remoteEnabled = MutableStateFlow(false)
    override val remoteInput = MutableStateFlow(List(5) { 0.0 })
    override val cameraYaw = MutableStateFlow<Double?>(null)
    override val gelSelected = MutableStateFlow(false)
    private val firingInternal = AtomicBoolean(false)
    override val firing = MutableStateFlow(false)
    private val _fireEvents = MutableSharedFlow<AmmoType>(extraBufferCapacity = 16)
    override val fireEvents: SharedFlow<AmmoType> = _fireEvents.asSharedFlow()
    private var firingJob: Job? = null
    private val gelFireMutex = Mutex()
    override val driveGear = MutableStateFlow(3)
    override val talking = MutableStateFlow(false)
    override val microphoneReady = MutableStateFlow(false)
    private val talkGeneration = AtomicLong(0L)
    override val talkBusy = MutableStateFlow(false)
    override fun shiftGear(delta: Int) { driveGear.update { (it + delta).coerceIn(1, DriveSpeed.gearCount) } }
    override fun selectGear(gear: Int) { driveGear.value = gear.coerceIn(1, DriveSpeed.gearCount) }
    override fun switchAmmo() { if (!challenge.value.ownsRound) gelSelected.value = !gelSelected.value }
    private val selectedGel: Boolean get() = challenge.value.firing?.let { it == cn.elonzh.hanppie.robot.challenge.ChallengeFiring.GEL } ?: gelSelected.value
    override fun fireSelected() { if (selectedGel) fireGel() else fire() }
    private val videoConsumer = AtomicReference<((ByteArray) -> Unit)?>(null)
    private val audioConsumer = AtomicReference<((ByteArray) -> Unit)?>(null)
    override val videoFrames = cn.elonzh.hanppie.ui.robot.remote.VideoFrameCache()
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
            check(!robotFirmware.state.value.blocksControl) { tr(Res.string.firmware_finish_first) }
            check(!robotCalibration.state.value.blocksControl) { tr(Res.string.calibration_finish_first) }
            check(!robotAddressing.state.value.blocksControl) { tr(Res.string.addressing_finish_first) }
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
    private val gimbalCentering = cn.elonzh.hanppie.ui.robot.remote.GimbalCentering()
    override fun recenterGimbal() {
        if (remoteEnabled.value && isForeground && acceptingWork.load() && !chat.state.value.running && !state.value.busy && !tracking.value.blocksManualControl &&
            !challenge.value.blocksControl && !(vision.value.ownsPipeline && vision.value.mode == cn.elonzh.hanppie.robot.vision.VisionMode.ROBOT_AIM)) {
            gimbalCentering.start(clock.now().toEpochMilliseconds())
        }
    }
    override fun clearRemoteInputs() {
        robotArm.stopControl()
        session.load()?.arm?.stopIdentification()
        robotAddressing.holdWheel(null)
        gimbalCentering.cancel()
        remoteRevision.addAndFetch(1)
        stopFiring()
        cancelPushToTalk()
        runCatching { session.load()?.halt() }
        remoteInput.value = List(5) { 0.0 }
        cameraYaw.value = null
    }
    override fun haltRemote() {
        cameraZoom.cancelTransition()
        clearRemoteInputs()
        remoteEnabled.value = false
    }
    override fun leaveRemote() {
        haltRemote()
        val current = session.load()
        scope.launch { runCatching { current?.exitRemote() }.onFailure { log(tr(Res.string.leaving_remote_control_value,it.message)) } }
    }
    override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double) {
        if (remoteEnabled.value && acceptingWork.load() && !chat.state.value.running && !state.value.busy) {
            val current = state.value
            val centered = gimbalCentering.velocity(clock.now().toEpochMilliseconds(), current.gimbal,
                current.gimbalReceivedAtMillis, listOf(x, y, z, pitch, yaw).any { it != 0.0 })
            val effectivePitch = centered?.first ?: pitch
            val effectiveYaw = centered?.second ?: yaw
            remoteInput.value = listOf(x,y,z,effectivePitch,effectiveYaw)
            cameraYaw.value = session.load()?.cameraYaw
            runCatching { session.load()?.drive(x, y, z, effectivePitch, effectiveYaw, cameraRelative = true) }.onFailure {
                haltRemote()
                val message = tr(Res.string.remote_stopped_connection_unresponsive)
                log(message)
                state.update { current -> current.copy(error = message) }
            }
        }
    }
    override fun startFiring() {
        if (gunCooling.value.ownsCooling) return
        if (!remoteEnabled.value || !acceptingWork.load() || chat.state.value.running || state.value.busy || tracking.value.blocksManualControl || vision.value.blocksManualControl || challenge.value.blocksControl) return
        firingInternal.store(true)
        firing.value = true
        if (firingJob?.isActive == true) return
        firingJob = scope.launch {
            try {
                while (firingInternal.load() && remoteEnabled.value && acceptingWork.load() && !chat.state.value.running && !state.value.busy) {
                    val s = session.load() ?: break
                    if (selectedGel) {
                        val seq = gelFireMutex.withLock {
                            if (!firingInternal.load() || !remoteEnabled.value) return@withLock null
                            runCatching { s.fireGelOnce { _fireEvents.tryEmit(AmmoType.GEL) } }.getOrNull()
                        }
                        if (seq != null) {
                            log(tr(Res.string.gel_fire_command_sent_seq_value_physical_firing_unconfirmed, seq))
                        } else {
                            break
                        }
                    } else {
                        val ok = runCatching { s.fireInfrared() }.isSuccess
                        if (ok) {
                            _fireEvents.tryEmit(AmmoType.INFRARED)
                        } else {
                            break
                        }
                        delay(200)
                    }
                }
            } finally {
                firingInternal.store(false)
                firing.value = false
            }
        }
    }
    override fun stopFiring() {
        firingInternal.store(false)
        firing.value = false
        if (!selectedGel) {
            firingJob?.cancel()
        }
    }
    override fun fire() {
        if (gunCooling.value.ownsCooling) return
        if (remoteEnabled.value && acceptingWork.load() && !chat.state.value.running && !state.value.busy && !tracking.value.blocksManualControl && !vision.value.blocksManualControl && !challenge.value.blocksControl) {
            val s = session.load() ?: return
            val ok = runCatching { s.fireInfrared() }.onFailure {
                state.update { current ->
                    current.copy(error = it.message)
                }
            }.isSuccess
            if (ok) _fireEvents.tryEmit(AmmoType.INFRARED)
        }
    }
    override fun fireGel() {
        if (gunCooling.value.ownsCooling) return
        if (!remoteEnabled.value || !acceptingWork.load() || chat.state.value.running || state.value.busy || tracking.value.blocksManualControl || vision.value.blocksManualControl || challenge.value.blocksControl) return
        scope.launch {
            val s = session.load() ?: return@launch
            gelFireMutex.withLock {
                runCatching {
                    val sequence = s.fireGelOnce { _fireEvents.tryEmit(AmmoType.GEL) }
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
        if (robotPower.state.value.phase == cn.elonzh.hanppie.ui.robot.device.PowerPhase.ACTION || robotAddressing.state.value.blocksControl || robotCalibration.state.value.blocksControl || robotFirmware.state.value.blocksControl) return
        if (acceptingWork.load() && mediaRequests.trySend(MediaRequest(current, true, audio, mediaSettings.value.videoResolution)).isFailure) {
            log(tr(Res.string.media_request_queue_is_full))
        }
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
                signalQualityRaw = snapshot.signalQuality,
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
        tool(ReadSkillTool { name, path -> skills().read(name, path) })
        tool(ListLabScriptsTool {
            ListLabScriptsTool.Result(scriptLibrary.savedScripts().map { script ->
                ListLabScriptsTool.Script(
                    id = script.id,
                    name = script.name,
                    sourceLength = script.source.length,
                    updatedAtEpochMillis = script.updatedAtEpochMillis,
                )
            })
        })
        tool(ReadLabScriptTool { scriptId ->
            val script = scriptLibrary.read(scriptId)
                ?: return@ReadLabScriptTool ReadLabScriptTool.Result(scriptId, status = ReadLabScriptTool.Status.NOT_FOUND)
            ReadLabScriptTool.Result(
                id = script.id,
                name = script.name,
                source = script.source,
                createdAtEpochMillis = script.createdAtEpochMillis,
                updatedAtEpochMillis = script.updatedAtEpochMillis,
                status = ReadLabScriptTool.Status.FOUND,
            )
        })
        tool(SaveLabScriptTool { scriptId, name, source ->
            val script = scriptLibrary.save(scriptId, name, source)
            SaveLabScriptTool.Result(
                id = script.id,
                name = script.name,
                created = scriptId == null,
                sourceLength = script.source.length,
                updatedAtEpochMillis = script.updatedAtEpochMillis,
            )
        })
        tool(DeleteLabScriptTool({ id -> requireNotNull(scriptLibrary.read(id)) { "A saved script with ID '$id' does not exist" }.name }) { scriptId ->
            val script = scriptLibrary.delete(scriptId)
            DeleteLabScriptTool.Result(script.id, script.name, DeleteLabScriptTool.Status.DELETED)
        })
        tool(ExecuteLabPythonTool(scriptLibrary::executionSnapshot) { script -> agentOperation {
            check(!state.value.scriptRunPhase.mayBeExecuting) { tr(Res.string.stop_the_script_with_unknown_state_first) }
            haltRemote()
            session.load()?.exitRemote()
            val scriptRunId = startScript(script.source, script.name, script.audioClips)
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
        skillsPrompt = { skills().prompt() },
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
        session.load()?.setMobileSensorsActive(foreground)
        if (!foreground) { robotMotion.cancelRead(); robotDisplay.cancelRead() }
        if (!foreground) labUiVisibleRun?.let { session.load()?.labUi?.setVisible(it, false) }
        if (!foreground) { robotCalibration.stopAutomaticContinuation(); robotAddressing.holdWheel(null); haltRemote(); voiceInput.cancel() }
        robotArm.refreshVisibility(); robotExtensions.refreshVisibility()
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
                robotFiles.clear(); robotAudio.clear(); cameraZoom.clear(); robotCamera.clear(); robotAlbum.clear(); robotHealth.clear(); robotInformation.clear(); robotFirmware.detach(); robotArm.detach(); robotExtensions.detach(); robotCalibration.detach(); robotAddressing.detach(); robotPower.detach(); robotMotion.clear(); robotDisplay.clear(); robotWifi.detach(); robotSdr.detach()
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
            add(ConnectionCandidate(RobotTarget(Protocol.DEFAULT_ROBOT_IP, preferences.appId), ConnectionMode.DIRECT))
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
        expectedWifi: RobotWifiProfile? = null,
        expectedRadio: WifiRadioTarget? = null,
        expectedSdr: cn.elonzh.hanppie.robot.network.SdrChange? = null,
    ): WifiVerification? {
        robotPower.clear()
        state.update { ConsoleState(busy = true, connecting = true,
            statusMessage = uiText(Res.string.connecting_to_value,target.ip), logs = it.logs, devices = it.devices) }
        val attemptTarget = if (automaticProbe) target.forAutomaticProbe() else target
        val candidate = newSession(attemptTarget)
        session.store(candidate)
        try {
            candidate.connect()
            candidate.safetyStop()
            val verifiedWifi = expectedWifi?.let { expected ->
                val actual = candidate.wifi.read().also { actual ->
                    check(actual.modeCode == expected.modeCode && actual.ssid == expected.ssid &&
                        (expected.password == null || actual.password == expected.password)) { tr(Res.string.wifi_verification_failed) }
                }
                val radio = expectedRadio?.let { target -> candidate.wifi.readRadio().also {
                    check(target.matches(it)) { tr(Res.string.wifi_verification_failed) }
                } }
                WifiVerification(actual, radio)
            }
            val verifiedSdr = expectedSdr?.let { target -> checkNotNull(candidate.sdr.read()).also {
                check(target.matches(it)) { tr(Res.string.sdr_verification_failed) }
            } }
            runCatching { candidate.setSpeakerVolume(mediaSettings.value.speakerVolume) }
            currentCoroutineContext().ensureActive()
            check(revision == connectionRevision.load()) { tr(Res.string.connection_cancelled) }
            session.store(candidate)
            lab.store(candidate.lab)
            robotFiles.attach(candidate.files)
            robotAudio.attach(candidate.audio)
            cameraZoom.attach(candidate.camera)
            robotCamera.attach(candidate.camera)
            robotAlbum.attach(candidate.album)
            robotHealth.attach(candidate.health)
            robotInformation.attach(candidate.information, candidate.health)
            robotArm.attach(candidate.arm, target)
            robotExtensions.attach(candidate.extensions)
            robotPower.attach(candidate.power, candidate.information.systemStatus)
            robotMotion.attach(candidate.motion)
            robotDisplay.attach(candidate.display)
            robotFirmware.attach(candidate.firmware, target)
            robotCalibration.attach(candidate.calibration, target)
            robotAddressing.attach(candidate.addressing, target)
            robotWifi.attach(candidate.wifi, target)
            robotSdr.attach(candidate.sdr, target, verifiedSdr)
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
            return verifiedWifi
        } catch (error: Exception) {
            if (session.load() === candidate) clearLabUiBinding()
            candidate.close()
            session.compareAndSet(candidate, null)
            if (revision == connectionRevision.load()) {
                robotFiles.clear(); robotAudio.clear(); cameraZoom.clear(); robotCamera.clear(); robotAlbum.clear(); robotHealth.clear(); robotInformation.clear(); robotFirmware.detach(); robotArm.detach(); robotExtensions.detach(); robotCalibration.detach(); robotAddressing.detach(); robotPower.detach(); robotMotion.clear(); robotDisplay.clear(); robotWifi.detach(); robotSdr.detach()
                state.update { it.lost(tr(Res.string.connection_failed)).copy(connecting = false, statusMessage = uiText(Res.string.connection_failed)) }
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
        robotAudio.clear(); cameraZoom.clear(); robotCamera.clear(); robotAlbum.clear(); robotHealth.clear(); robotInformation.clear(); robotFirmware.detach(); robotArm.detach(); robotExtensions.detach(); robotCalibration.detach(); robotAddressing.detach(); robotPower.detach(); robotMotion.clear(); robotDisplay.clear(); robotWifi.detach(); robotSdr.detach()
        val candidate = robotRuntime.open(target, ::receive, ::log, ::connectionLost)
        candidate.setMobileSensorsActive(isForeground)
        candidate.onVideo = { videoConsumer.load()?.invoke(it) }
        candidate.onAudio = { audioConsumer.load()?.invoke(it) }
        trackingObserver?.cancel()
        tracking.value = TrackingState()
        trackingObserver = scope.launch {
            candidate.tracking.collect { value -> if (session.load() === candidate) tracking.value = value }
        }
        visionObserver?.cancel()
        vision.value = cn.elonzh.hanppie.robot.vision.VisionState()
        visionObserver = scope.launch {
            candidate.vision.collect { value -> if (session.load() === candidate) vision.value = value }
        }
        challengeObserver?.cancel()
        gunCoolingObserver?.cancel()
        gunCooling.value = cn.elonzh.hanppie.robot.remote.GunCoolingState()
        gunCoolingObserver = scope.launch {
            candidate.gunCooling.collect { value -> if (session.load() === candidate) {
                gunCooling.value = value
                if (value.ownsCooling) stopFiring()
            } }
        }
        challenge.value = cn.elonzh.hanppie.robot.challenge.ChallengeState()
        challengeObserver = scope.launch {
            candidate.challenge.collect { value -> if (session.load() === candidate) {
                challenge.value = value
                if (value.blocksControl) { gimbalCentering.cancel(); stopFiring() }
            } }
        }
        clearLabUiBinding()
        labUiObserver = scope.launch {
            candidate.labUi.state.collect { value -> if (session.load() === candidate) {
                if (value.issue != null && (labUi.value.runId != value.runId || labUi.value.issue != value.issue))
                    log("程序面板更新失败：${value.issue}")
                labUi.value = value
            } }
        }
        return candidate
    }

    private fun clearLabUiBinding() {
        labUiVisibleRun?.let { session.load()?.labUi?.setVisible(it, false) }
        labUiObserver?.cancel(); labUiObserver = null
        labUi.value = LabUiState(); labUiVisibleRun = null
    }

    private fun connectionLost(candidate: RobotSession, reason: String) {
        if (session.load() === candidate) gunCooling.value = cn.elonzh.hanppie.robot.remote.GunCoolingState()
        if (session.load() === candidate) clearLabUiBinding()
        if (session.load() === candidate) vision.value = cn.elonzh.hanppie.robot.vision.VisionState()
        tracking.value = TrackingState()
        gimbalCentering.cancel()
        remoteEnabled.value = false
        stopFiring()
        remoteInput.value = List(5) { 0.0 }
        cameraYaw.value = null
        scope.launch {
            if (session.load() !== candidate) return@launch
            session.store(null)
            lab.store(null)
            robotFiles.clear(); robotAudio.clear(); cameraZoom.clear(); robotCamera.clear(); robotAlbum.clear(); robotHealth.clear(); robotInformation.clear(); robotFirmware.detach(); robotArm.detach(); robotExtensions.detach(); robotCalibration.detach(); robotAddressing.detach(); robotPower.detach(); robotMotion.clear(); robotDisplay.clear(); robotWifi.detach(); robotSdr.detach()
            candidate.close()
            log(reason)
            state.update { it.lost(reason).copy(busy = false, connecting = false) }
        }
    }

    override fun disconnect() = work { closeConnection(preserveNetworkRecovery = false) }

    private fun closeConnection(preserveNetworkRecovery: Boolean) {
        connectionRevision.addAndFetch(1)
        clearLabUiBinding()
        haltRemote()
        session.exchange(null)?.close(); lab.store(null); tracking.value = TrackingState(); vision.value = cn.elonzh.hanppie.robot.vision.VisionState()
        challenge.value = cn.elonzh.hanppie.robot.challenge.ChallengeState()
        robotFiles.clear(); robotAudio.clear(); cameraZoom.clear(); robotCamera.clear(); robotAlbum.clear(); robotHealth.clear(); robotInformation.clear(); robotFirmware.detach(); robotArm.detach(); robotExtensions.detach(); robotCalibration.detach(); robotAddressing.detach(); robotPower.detach(); robotMotion.clear(); robotDisplay.clear(); robotWifi.detach(preserveNetworkRecovery); robotSdr.detach(preserveNetworkRecovery)
        gunCooling.value = cn.elonzh.hanppie.robot.remote.GunCoolingState()
        state.update {
            val uncertain = it.scriptRunPhase.mayBeExecuting
            it.copy(connected = false, connectedAddress = null, connecting = false,
                statusMessage = uiText(Res.string.disconnected), error = null,
                scriptRunPhase = if (uncertain) ScriptRunPhase.UNKNOWN else it.scriptRunPhase,
                scriptMessage = if (uncertain) uiText(Res.string.session_ended_robot_state_unknown) else it.scriptMessage,
                robotProduct = RobotProduct(), battery = null, signalQuality = null, values = emptyList(), gimbal = null, gimbalReceivedAtMillis = null, chassisAttitude = null, chassisReceivedAtMillis = null, wheels = null, wheelsReceivedAtMillis = null)
        }
        log(tr(Res.string.connection_closed_scripts_on_the_robot_may_still_be))
    }

    private suspend fun changePower(source: cn.elonzh.hanppie.robot.product.RobotPowerService, action: suspend () -> Unit) {
        check(robotWifi.state.value.recovery == null && robotSdr.state.value.recovery == null) { tr(Res.string.robot_power_task_active) }
        changeDevice({ it.power === source }, Res.string.robot_power_task_active, Res.string.robot_power_target_changed, action)
    }
    private fun canObserveArm(source: cn.elonzh.hanppie.robot.extensions.arm.RobotArmService): Boolean {
        val current = session.load() ?: return false
        return isForeground && acceptingWork.load() && current.connected && current.arm === source &&
            (!state.value.busy || robotArm.state.value.changing) && !chat.state.value.running && !state.value.scriptRunPhase.mayBeExecuting &&
            !robotFirmware.state.value.blocksControl && !current.firmware.state.value.blocksControl &&
            !robotCalibration.state.value.blocksControl && !current.calibration.state.value.blocksControl &&
            !robotAddressing.state.value.blocksControl && !current.addressing.state.value.blocksControl
    }
    private fun canObserveExtensions(source: cn.elonzh.hanppie.robot.extensions.RobotExtensionService): Boolean {
        val current = session.load() ?: return false
        return isForeground && acceptingWork.load() && current.connected && current.extensions === source &&
            (!state.value.busy || robotExtensions.state.value.changing) && !remoteEnabled.value && !chat.state.value.running &&
            !state.value.scriptRunPhase.mayBeExecuting && !robotFirmware.state.value.blocksControl && !current.firmware.state.value.blocksControl &&
            !robotCalibration.state.value.blocksControl && !current.calibration.state.value.blocksControl &&
            !robotAddressing.state.value.blocksControl && !current.addressing.state.value.blocksControl
    }
    private suspend fun changeExtensions(source: cn.elonzh.hanppie.robot.extensions.RobotExtensionService, action: suspend () -> Unit) {
        check(robotWifi.state.value.recovery == null) { tr(Res.string.extensions_task_active) }
        changeDevice({ it.extensions === source }, Res.string.extensions_task_active, Res.string.extensions_target_changed, action, MaintenanceOwner.EXTENSIONS)
    }
    private suspend fun changeArm(source: cn.elonzh.hanppie.robot.extensions.arm.RobotArmService, action: suspend () -> Unit) {
        check(robotWifi.state.value.recovery == null) { tr(Res.string.arm_task_active) }
        changeDevice({ it.arm === source }, Res.string.arm_task_active, Res.string.arm_target_changed, action, MaintenanceOwner.ARM)
    }

    private suspend fun changeAddressing(source: cn.elonzh.hanppie.robot.maintenance.RobotAddressingService, action: suspend () -> Unit) {
        check(robotWifi.state.value.recovery == null) { tr(Res.string.addressing_task_active) }
        changeDevice({ it.addressing === source }, Res.string.addressing_task_active, Res.string.addressing_target_changed,
            action, maintenanceOwner = MaintenanceOwner.ADDRESSING)
    }

    private suspend fun changeCalibration(source: cn.elonzh.hanppie.robot.maintenance.RobotCalibrationService, action: suspend () -> Unit) {
        check(robotWifi.state.value.recovery == null) { tr(Res.string.calibration_task_active) }
        changeDevice({ it.calibration === source }, Res.string.calibration_task_active, Res.string.calibration_target_changed,
            action, maintenanceOwner = MaintenanceOwner.CALIBRATION)
    }

    private suspend fun changeFirmware(source: cn.elonzh.hanppie.robot.maintenance.firmware.RobotFirmwareService, action: suspend () -> Unit) {
        check(robotWifi.state.value.recovery == null) { tr(Res.string.firmware_task_active) }
        changeDevice({ it.firmware === source }, Res.string.firmware_task_active, Res.string.firmware_target_changed,
            action, maintenanceOwner = MaintenanceOwner.FIRMWARE)
    }

    override fun reconnectFirmware() {
        val origin = robotFirmware.state.value.recovery?.target ?: return
        work(connecting = true) {
            awaitConnectionPreferences()
            check(session.load() == null) { tr(Res.string.disconnect_the_current_session_first) }
            val revision = connectionRevision.addAndFetch(1)
            prepareNetwork(); connectTarget(origin, revision, ConnectionMode.UNKNOWN)
        }
    }

    override fun reconnectCalibration() {
        val origin = robotCalibration.state.value.recovery?.target ?: return
        work(connecting = true) {
            awaitConnectionPreferences()
            check(session.load() == null) { tr(Res.string.disconnect_the_current_session_first) }
            val revision = connectionRevision.addAndFetch(1)
            prepareNetwork(); connectTarget(origin, revision, ConnectionMode.UNKNOWN)
        }
    }

    override fun reconnectAddressing() {
        val origin = robotAddressing.state.value.recovery?.target ?: return
        work(connecting = true) {
            awaitConnectionPreferences()
            check(session.load() == null) { tr(Res.string.disconnect_the_current_session_first) }
            val revision = connectionRevision.addAndFetch(1)
            prepareNetwork()
            connectTarget(origin, revision, ConnectionMode.UNKNOWN)
        }
    }

    private suspend fun changeWifi(source: RobotWifiService, action: suspend () -> Unit) {
        changeNetwork({ it.wifi === source }, action)
    }

    private suspend fun changeNetwork(matches: (RobotSession) -> Boolean, action: suspend () -> Unit) =
        changeDevice(matches, Res.string.wifi_task_active, Res.string.wifi_target_changed, action)

    private enum class MaintenanceOwner { ADDRESSING, CALIBRATION, FIRMWARE, ARM, EXTENSIONS }

    private suspend fun changeDevice(matches: (RobotSession) -> Boolean,
        activeMessage: org.jetbrains.compose.resources.StringResource,
        changedMessage: org.jetbrains.compose.resources.StringResource, action: suspend () -> Unit, maintenanceOwner: MaintenanceOwner? = null) {
        val current = checkNotNull(session.load()) { tr(changedMessage) }
        val snapshot = state.value
        check((maintenanceOwner == MaintenanceOwner.ADDRESSING || !robotAddressing.state.value.blocksControl && !current.addressing.state.value.blocksControl) &&
            (maintenanceOwner == MaintenanceOwner.CALIBRATION || !robotCalibration.state.value.blocksControl && !current.calibration.state.value.blocksControl) &&
            (maintenanceOwner == MaintenanceOwner.FIRMWARE || !robotFirmware.state.value.blocksControl && !current.firmware.state.value.blocksControl) &&
            (maintenanceOwner != MaintenanceOwner.FIRMWARE || !robotFiles.state.value.busy) &&
            (maintenanceOwner == MaintenanceOwner.ARM || !robotArm.state.value.changing && !current.arm.state.value.busy) &&
            (maintenanceOwner == MaintenanceOwner.EXTENSIONS || !robotExtensions.state.value.changing && !current.extensions.state.value.busy) &&
            acceptingWork.load() && current.connected && matches(current) && robotSdr.state.value.recovery == null && !chat.state.value.running &&
            !snapshot.scriptRunPhase.mayBeExecuting && !robotAlbum.state.value.nativeBusy && !robotCamera.state.value.storageBusy &&
            robotCamera.state.value.storage?.recording != true && !talkBusy.value && !talking.value) { tr(activeMessage) }
        check(!snapshot.busy && state.compareAndSet(snapshot, snapshot.copy(busy = true))) { tr(activeMessage) }
        val revision = connectionRevision.load()
        try {
            if (maintenanceOwner != MaintenanceOwner.ARM) robotArm.pauseAndJoin()
            if (maintenanceOwner != MaintenanceOwner.EXTENSIONS) robotExtensions.pauseAndJoin()
            if (maintenanceOwner != MaintenanceOwner.FIRMWARE || !current.firmware.state.value.loaderMayBeActive) {
                haltRemote(); current.exitRemote(); current.safetyStop()
            }
            currentCoroutineContext().ensureActive()
            check(session.load() === current && current.connected) { tr(changedMessage) }
            action()
        } finally {
            if (revision == connectionRevision.load()) state.update { it.copy(busy = false) }
        }
    }

    private fun retireWifiSession(source: RobotWifiService) {
        if (session.load()?.wifi === source) closeConnection(preserveNetworkRecovery = true)
    }

    private suspend fun reconnectSdr(origin: RobotTarget, change: cn.elonzh.hanppie.robot.network.SdrChange) {
        reconnectNetwork { revision -> connectTarget(origin, revision, ConnectionMode.UNKNOWN, expectedSdr = change) }
    }

    private suspend fun <T> reconnectNetwork(action: suspend (Long) -> T): T {
        val snapshot = state.value
        check(acceptingWork.load() && session.load() == null && !chat.state.value.running && !snapshot.busy &&
            state.compareAndSet(snapshot, snapshot.copy(busy = true, connecting = true, error = null))) { tr(Res.string.wifi_task_active) }
        val revision = connectionRevision.addAndFetch(1)
        try { awaitConnectionPreferences(); prepareNetwork(); return action(revision) }
        finally { if (revision == connectionRevision.load()) state.update { it.copy(busy = false, connecting = false) } }
    }

    private suspend fun reconnectWifi(origin: RobotTarget, expected: RobotWifiProfile, radio: WifiRadioTarget?): WifiVerification {
        return reconnectNetwork { revision ->
            checkNotNull(connectTarget(origin, revision, when (expected.modeCode) {
                0 -> ConnectionMode.DIRECT; 2 -> ConnectionMode.ROUTER; else -> ConnectionMode.UNKNOWN
            }, expectedWifi = expected, expectedRadio = radio))
        }
    }

    override fun runScript(source: String, title: String, audio: List<LabAudioClip>) = work {
        haltRemote(); session.load()?.exitRemote()
        startScript(source, title, audio)
    }

    private suspend fun startScript(source: String, title: String, audio: List<LabAudioClip> = emptyList()): String {
        check(!robotFirmware.state.value.blocksControl) { tr(Res.string.firmware_finish_first) }
        check(!robotCalibration.state.value.blocksControl) { tr(Res.string.calibration_finish_first) }
        check(!robotAddressing.state.value.blocksControl) { tr(Res.string.addressing_finish_first) }
        check(!robotArm.state.value.changing && session.load()?.arm?.state?.value?.busy != true) { tr(Res.string.arm_task_active) }
        robotArm.pauseAndJoin()
        check(!robotExtensions.state.value.changing && session.load()?.extensions?.state?.value?.busy != true) { tr(Res.string.extensions_task_active) }
        robotExtensions.pauseAndJoin()
        val controller = checkNotNull(lab.load()) { tr(Res.string.robot_is_not_connected) }
        val startedAt = clock.now().toEpochMilliseconds()
        state.update { it.copy(scriptRunId = null, scriptTitle = title,
            scriptRunPhase = ScriptRunPhase.UPLOADING, scriptStartedAtEpochMillis = startedAt,
            scriptFinishedAtEpochMillis = null, scriptMessage = uiText(Res.string.uploading),
            scriptMessages = listOf(tr(Res.string.script_trace_uploading))) }
        val upload = try {
            controller.upload(source, title, audio)
        } catch (error: Exception) {
            state.update { it.copy(scriptRunPhase = ScriptRunPhase.FAILED,
                scriptFinishedAtEpochMillis = clock.now().toEpochMilliseconds(), scriptMessage = uiText(Res.string.upload_failed)) }
            throw error
        }
        state.update { it.copy(scriptRunId = upload.runId, scriptRunPhase = ScriptRunPhase.STARTING,
            scriptMessage = uiText(Res.string.waiting_for_script_start),
            scriptMessages = (it.scriptMessages + tr(Res.string.script_trace_uploaded)).takeLast(200)
        )
        }
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
            scriptMessages = (current.scriptMessages + tr(Res.string.script_trace_start_sent)).takeLast(
                200
            ),
        ) else current }
        scope.launch {
            delay(scriptStartConfirmationTimeoutMillis)
            state.update { current ->
                if (current.scriptRunId == upload.runId && current.scriptRunPhase == ScriptRunPhase.STARTING) {
                    current.copy(
                        scriptRunPhase = ScriptRunPhase.UNKNOWN,
                        scriptMessage = uiText(Res.string.script_start_confirmation_timed_out),
                        scriptMessages = (current.scriptMessages + tr(Res.string.script_trace_start_timeout)).takeLast(
                            200
                        ),
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
        val payload = when {
            frame.set == 0 && frame.id == 0x32 -> "<redacted ${frame.payload.size} B>"
            frame.set == Protocol.CMDSET_RM && frame.id in listOf(0xba, 0xbb) -> "<program-ui ${frame.payload.size} B>"
            frame.set == Protocol.CMDSET_WIFI && frame.id in listOf(Protocol.CMD_WIFI_AP_GET_SSID, Protocol.CMD_WIFI_AP_SET_SSID,
                Protocol.CMD_WIFI_AP_SET_PASSWORD, Protocol.CMD_WIFI_AP_GET_PASSWORD, Protocol.CMD_WIFI_CONFIG_BY_QRCODE) -> "<redacted ${frame.payload.size} B>"
            frame.set == 0 && frame.id == 0x27 -> "media=${frame.payload.size} B header=${frame.payload.copyOfRange(0, minOf(10, frame.payload.size)).hex()}"
            else -> frame.payload.hex()
        }
        val line = "seq=${frame.sequence} ${frame.sender.toString(16)}→${frame.receiver.toString(16)} " +
            "${frame.set.toString(16)}:${frame.id.toString(16)} attr=${frame.attr.toString(16)} $payload"
        val motion = Telemetry.motion(frame)
        val chassisAttitude = Telemetry.chassisAttitude(frame)
        val wheels = Telemetry.wheels(frame)
        val gimbal = Telemetry.gimbal(frame)
        val signalQuality = Telemetry.wifiSignalQuality(frame)
        val values = motion?.let {
            listOf(tr(Res.string.heading_like_uncalibrated) to it.headingLike.toString()) +
                it.raw.mapIndexed { index, value -> "raw[$index] / offset ${26 + index * 4}" to value.toString() }
        }
        val message = Telemetry.labMessage(frame)
        val scriptStatus = Telemetry.labScriptStatus(frame)
        val labMessageFrame = frame.valid && frame.set == 0x3f && (frame.id == 0xa4 || frame.id == 0xa5)
        val snapshot = state.value
        val runId = snapshot.scriptRunId
        val isMatchingRun = runId != null && scriptStatus != null &&
            scriptStatus.guid.equals(runId, ignoreCase = true)
        val isRunningTransition = isMatchingRun && scriptStatus.isRunning &&
            snapshot.scriptRunPhase == ScriptRunPhase.STARTING
        val isFailedTransition = isMatchingRun && scriptStatus.isFailed &&
            snapshot.scriptRunPhase.mayBeExecuting
        val isCompletedTransition = runId != null && scriptStatus != null && scriptStatus.isIdle &&
            snapshot.scriptRunPhase == ScriptRunPhase.RUNNING

        val newMessages = buildList {
            if (message != null) add(message.text)
            if (isRunningTransition) {
                add(tr(Res.string.script_trace_started))
            }
            if (isFailedTransition) {
                add(tr(Res.string.script_trace_failed))
                scriptStatus.traceback?.lines()?.filter { it.isNotBlank() }?.let(::addAll)
            }
            if (isCompletedTransition) {
                add(tr(Res.string.script_trace_completed))
            }
        }

        state.update { old -> old.copy(
            packets = old.packets + 1,
            labMessagePackets = old.labMessagePackets + if (labMessageFrame) 1 else 0,
            frames = (old.frames + line).takeLast(250),
            robotProduct = RobotProductProtocol.updated(old.robotProduct, frame) ?: old.robotProduct,
            battery = if (motion == null) old.battery else motion.batteryPercent,
            signalQuality = signalQuality ?: old.signalQuality,
            chassisAttitude = chassisAttitude ?: old.chassisAttitude,
            chassisReceivedAtMillis = if (chassisAttitude != null) clock.now().toEpochMilliseconds() else old.chassisReceivedAtMillis,
            wheels = wheels ?: old.wheels,
            wheelsReceivedAtMillis = if (wheels != null) clock.now().toEpochMilliseconds() else old.wheelsReceivedAtMillis,
            gimbal = gimbal ?: old.gimbal,
            gimbalReceivedAtMillis = if (gimbal != null) clock.now().toEpochMilliseconds() else old.gimbalReceivedAtMillis,
            scriptMessages = if (newMessages.isEmpty()) old.scriptMessages else (old.scriptMessages + newMessages).takeLast(200),
            scriptRunPhase = if (isRunningTransition) ScriptRunPhase.RUNNING else old.scriptRunPhase,
            scriptMessage = if (isRunningTransition) uiText(Res.string.script_running) else old.scriptMessage,
            values = values ?: old.values,
        ) }

        if (runId != null) {
            if (isFailedTransition) {
                finishReportedRun(runId, completed = false, errorText = scriptStatus.traceback ?: "")
            } else if (isCompletedTransition) {
                finishReportedRun(runId, completed = true)
            }
        }
    }

    private fun finishReportedRun(runId: String, completed: Boolean, errorText: String = "") {
        val snapshot = state.value
        if (snapshot.scriptRunId != runId || snapshot.scriptRunPhase == ScriptRunPhase.COMPLETING ||
            !snapshot.scriptRunPhase.mayBeExecuting) return
        if (!state.compareAndSet(snapshot, snapshot.copy(scriptRunPhase = ScriptRunPhase.COMPLETING,
                scriptMessage = uiText(Res.string.finishing_completed_script)))) return
        scope.launch {
            try {
                val accepted = lab.load()?.complete(runId) == true
                if (!accepted) return@launch
                val phase = if (completed) ScriptRunPhase.COMPLETED else ScriptRunPhase.FAILED
                val message =
                    if (completed) uiText(Res.string.script_completed) else uiText(Res.string.script_failed)
                if (!completed) {
                    log(tr(Res.string.script_failed))
                } else {
                    log(tr(Res.string.script_completed))
                }
                state.update { current -> if (current.scriptRunId == runId) current.copy(
                    scriptRunPhase = phase, scriptMessage = message,
                    scriptFinishedAtEpochMillis = clock.now().toEpochMilliseconds()) else current }
            } catch (error: Exception) {
                val message = error.message ?: error::class.simpleName ?: "Unknown error"
                log(tr(Res.string.error_value, message))
                state.update { current -> if (current.scriptRunId == runId) current.copy(
                    scriptRunPhase = ScriptRunPhase.UNKNOWN,
                    scriptMessage = uiText(Res.string.completed_marker_received_but_cleanup_failed)) else current }
            }
        }
    }

    override fun clearLogs() { state.update { it.copy(logs = emptyList(), frames = emptyList()) } }
    override suspend fun pauseConnection() {
        connectionRevision.addAndFetch(1)
        clearLabUiBinding()
        chat.cancelAndJoin()
        scope.coroutineContext[Job]?.children?.toList()?.forEach { it.cancelAndJoin() }
        session.exchange(null)?.close(); lab.store(null); tracking.value = TrackingState(); vision.value = cn.elonzh.hanppie.robot.vision.VisionState()
        robotFiles.clear(); robotAudio.clear(); cameraZoom.clear(); robotCamera.clear(); robotAlbum.clear(); robotHealth.clear(); robotInformation.clear(); robotFirmware.detach(); robotArm.detach(); robotExtensions.detach(); robotCalibration.detach(); robotAddressing.detach(); robotPower.detach(); robotMotion.clear(); robotDisplay.clear(); robotWifi.detach(); robotSdr.detach()
        state.update {
            val uncertain = it.scriptRunPhase.mayBeExecuting
            it.copy(connected = false, connectedAddress = null, connecting = false, busy = false,
                statusMessage = uiText(Res.string.disconnected), battery = null, signalQuality = null,
                values = emptyList(), gimbal = null, gimbalReceivedAtMillis = null, chassisAttitude = null, chassisReceivedAtMillis = null, wheels = null, wheelsReceivedAtMillis = null,
                scriptRunPhase = if (uncertain) ScriptRunPhase.UNKNOWN else it.scriptRunPhase,
                scriptMessage = if (uncertain) uiText(Res.string.connection_closed_robot_state_unknown) else it.scriptMessage)
        }
    }
    override fun close() { connectionRevision.addAndFetch(1); clearLabUiBinding(); robotFiles.close(); robotAudio.clear(); cameraZoom.clear(); robotCamera.clear(); robotAlbum.clear(); robotHealth.clear(); robotInformation.clear(); robotFirmware.clear(); robotArm.clear(); robotExtensions.clear(); robotCalibration.clear(); robotAddressing.clear(); robotPower.clear(); robotMotion.clear(); robotDisplay.clear(); robotWifi.close(); robotSdr.close(); cancelPushToTalk(); speakerInput.close(); voiceInput.close(); audioImporter.close(); modelTester.close(); chat.close(); session.exchange(null)?.close(); lab.store(null); tracking.value = TrackingState(); vision.value = cn.elonzh.hanppie.robot.vision.VisionState(); mediaRequests.close(); ledRequests.close(); mediaScope.cancel(); connectionPreferencesScope.cancel(); settings.close(); scope.cancel() }
}
