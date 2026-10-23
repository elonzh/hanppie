package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.telemetry.RobotSystemStatus
import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.extensions.arm.*
import cn.elonzh.hanppie.robot.extensions.*
import cn.elonzh.hanppie.robot.files.*
import cn.elonzh.hanppie.robot.lab.*
import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.delay
import kotlinx.io.Sink
import kotlinx.io.Source
import java.util.concurrent.atomic.AtomicInteger

internal class RobotSessionFixture : RobotSession {
    override var extensions: RobotExtensionService = object : RobotExtensionService {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(RobotExtensionState())
        override suspend fun setObserving(kind: ExtensionKind?) {}
        override suspend fun identify(component: RobotComponent): Unit = error("unused")
        override fun stopIdentification() {}
    }
    override var arm: RobotArmService = object : RobotArmService {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(RobotArmState())
        override suspend fun setObserving(enabled: Boolean) {}
        override suspend fun readBinding(): Unit = error("unused")
        override suspend fun setBinding(binding: ArmServoBinding, expected: ArmServoBinding): Unit = error("unused")
        override suspend fun setLoadMode(mode: ArmLoadMode, expectedBinding: ArmServoBinding, expectedModeRaw: Int?): Unit = error("unused")
        override suspend fun identifyServo(id: Int): Unit = error("unused")
        override fun stopIdentification() {}
        override fun beginControl(): ArmControlLease = error("unused")
        override fun move(lease: ArmControlLease, x: Double, y: Double, claw: Double): Unit = error("unused")
        override fun stopControl() {}
    }
    override var calibration: RobotCalibrationService = object : RobotCalibrationService {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(RobotCalibrationState())
        override suspend fun begin(kind: CalibrationKind): Unit = error("unused")
        override suspend fun startGimbal(): Unit = error("unused")
        override suspend fun cancel(): Unit = error("unused")
        override suspend fun retryArm(): Unit = error("unused")
        override fun confirmRestarted(): Unit = error("unused")
        override suspend fun observeInterrupted(previous: RobotCalibrationState): Unit = error("unused")
    }
    override var addressing: RobotAddressingService = object : RobotAddressingService {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(RobotAddressingState())
        override suspend fun begin(kind: ModuleAddressingKind): Unit = error("unused")
        override suspend fun assign(id: Int, candidate: Int): Unit = error("unused")
        override suspend fun skipArmor(step: Int): Unit = error("unused")
        override suspend fun prepareVerification(): Unit = error("unused")
        override suspend fun identify(id: Int): Unit = error("unused")
        override fun holdWheel(wheel: AddressingWheel?) {}
        override fun renewWheel() {}
        override fun confirmWheel(wheel: AddressingWheel): Unit = error("unused")
        override fun confirmModule(id: Int): Unit = error("unused")
        override suspend fun end(): Unit = error("unused")
        override suspend fun recover(kind: ModuleAddressingKind): Unit = error("unused")
    }
    override var firmware: cn.elonzh.hanppie.robot.maintenance.firmware.RobotFirmwareService = object : cn.elonzh.hanppie.robot.maintenance.firmware.RobotFirmwareService {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(cn.elonzh.hanppie.robot.maintenance.firmware.RobotFirmwareState())
        override suspend fun refreshConfiguration(): Unit = error("unused")
        override suspend fun install(expected: cn.elonzh.hanppie.robot.maintenance.firmware.FirmwarePackageInspection,
            configuration: cn.elonzh.hanppie.robot.maintenance.firmware.FirmwareManifest,
            consent: cn.elonzh.hanppie.robot.maintenance.firmware.FirmwareInstallConsent, openArchive: () -> kotlinx.io.Source): Unit = error("unused")
        override fun interruptTransfer(): Unit = error("unused")
        override suspend fun observeInterrupted(previous: cn.elonzh.hanppie.robot.maintenance.firmware.RobotFirmwareState): Unit = error("unused")
        override fun confirmRestartedAndChecked(): Unit = error("unused")
    }
    override val health = kotlinx.coroutines.flow.MutableStateFlow(cn.elonzh.hanppie.robot.telemetry.RobotHealth())
    override var information: cn.elonzh.hanppie.robot.product.RobotInformationService = object : cn.elonzh.hanppie.robot.product.RobotInformationService {
        override val systemStatus = kotlinx.coroutines.flow.MutableStateFlow<RobotSystemStatus?>(null)
        override suspend fun readFirmware(target: cn.elonzh.hanppie.robot.product.RobotVersionTarget): String = error("unused")
        override suspend fun readActivated(): Boolean = error("unused")
    }
    override var power: RobotPowerService = object : RobotPowerService {
        override suspend fun readPolicy(): LowPowerPolicy = error("unused")
        override suspend fun setPolicy(policy: LowPowerPolicy, onDispatch: () -> Unit) = error("unused")
        override suspend fun perform(action: RobotPowerAction, onDispatch: () -> Unit) = error("unused")
    }
    @Volatile var closed = false
    override val album = object : cn.elonzh.hanppie.robot.media.album.RobotAlbumService {
        override val busy = kotlinx.coroutines.flow.MutableStateFlow(false)
        override suspend fun list(): List<cn.elonzh.hanppie.robot.media.album.RobotMediaEntry> = error("unused")
        override suspend fun download(entry: cn.elonzh.hanppie.robot.media.album.RobotMediaEntry,
            part: cn.elonzh.hanppie.robot.media.album.RobotMediaPart, destination: kotlinx.io.Sink,
            onProgress: (Long, Long) -> Unit) { error("unused") }
    }
    val infraredCount = AtomicInteger(0)
    val gelCount = AtomicInteger(0)
    val driveCount = AtomicInteger(0)
    val mediaStarts = AtomicInteger(0)
    val mediaStops = AtomicInteger(0)
    val labStopCount = AtomicInteger(0)

    override val camera = object : cn.elonzh.hanppie.robot.media.RobotCameraService {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(cn.elonzh.hanppie.robot.media.CameraState(100))
        override suspend fun formatStorage() { error("unused") }
        override suspend fun takePhoto() { error("unused") }
        override suspend fun setRecording(active: Boolean) { error("unused") }
        override suspend fun getAntiFlicker(): Int = error("unused")
        override suspend fun setAntiFlicker(mode: cn.elonzh.hanppie.robot.media.AntiFlicker): Int = error("unused")
        override suspend fun getDate(): cn.elonzh.hanppie.robot.media.CameraDate? = error("unused")
        override suspend fun syncClock(): cn.elonzh.hanppie.robot.media.CameraDate = error("unused")
        override suspend fun setPhotoSize(size: cn.elonzh.hanppie.robot.media.CameraPhotoSize) = error("unused")
        override suspend fun setTransmissionRate(rate: cn.elonzh.hanppie.robot.media.VideoTransmissionRate) = error("unused")
        override suspend fun switchCamera(index: Int) = error("unused")
        override suspend fun requestKeyFrame() = error("unused")
        override suspend fun setZoom(hundredths: Int) {}
    }
    override var sdr: cn.elonzh.hanppie.robot.network.RobotSdrService = object : cn.elonzh.hanppie.robot.network.RobotSdrService {
        override suspend fun read(): cn.elonzh.hanppie.robot.network.SdrSettings? = null
        override suspend fun set(change: cn.elonzh.hanppie.robot.network.SdrChange, onDispatch: () -> Unit) = error("unused")
    }
    override var wifi: cn.elonzh.hanppie.robot.network.RobotWifiService = object : cn.elonzh.hanppie.robot.network.RobotWifiService {
        override val signals = kotlinx.coroutines.flow.MutableStateFlow(cn.elonzh.hanppie.robot.network.WifiSignals())
        override suspend fun readRadio(): cn.elonzh.hanppie.robot.network.WifiRadioSettings = error("unused")
        override suspend fun setChannelMode(mode: cn.elonzh.hanppie.robot.network.WifiChannelMode, onDispatch: () -> Unit) = error("unused")
        override suspend fun setChannel(channel: Int, onDispatch: () -> Unit) = error("unused")
        override suspend fun setSignalReports(enabled: Boolean) = error("unused")
        override suspend fun read(): cn.elonzh.hanppie.robot.network.RobotWifiProfile = error("unused")
        override suspend fun configure(credentials: cn.elonzh.hanppie.robot.network.RobotApCredentials,
            onConfirmed: (cn.elonzh.hanppie.robot.network.WifiConfirmedField) -> Unit) = error("unused")
        override suspend fun reboot() = error("unused")
    }
    override val audio = object : cn.elonzh.hanppie.robot.media.RobotAudioService {
        override val playback = kotlinx.coroutines.flow.MutableStateFlow<cn.elonzh.hanppie.robot.media.RobotSpeakerPlayback?>(null)
        override suspend fun readSoundEnabled() = false
        override suspend fun setAntiFeedback(enabled: Boolean) = error("unused")
        override suspend fun read(): cn.elonzh.hanppie.robot.media.RobotAudioSettings = error("unused")
        override suspend fun applyVolume(volume: Int): cn.elonzh.hanppie.robot.media.RobotAudioSettings = error("unused")
        override suspend fun setLanguage(language: cn.elonzh.hanppie.robot.media.SpeakerLanguage): cn.elonzh.hanppie.robot.media.RobotAudioSettings = error("unused")
    }
    override val connected = true
    override val product = RobotProduct(model = RobotModel.ROBOMASTER_S1)
    override val tracking = kotlinx.coroutines.flow.MutableStateFlow(cn.elonzh.hanppie.robot.tracking.TrackingState())
    override val vision = kotlinx.coroutines.flow.MutableStateFlow(cn.elonzh.hanppie.robot.vision.VisionState())
    val visionStarts = java.util.concurrent.CopyOnWriteArrayList<cn.elonzh.hanppie.robot.vision.VisionMode>()
    val visionStops = AtomicInteger(0)
    val visionColors = java.util.concurrent.CopyOnWriteArrayList<cn.elonzh.hanppie.robot.vision.VisionColor?>()
    override fun startVision(mode: cn.elonzh.hanppie.robot.vision.VisionMode, color: cn.elonzh.hanppie.robot.vision.VisionColor?) { visionStarts += mode; visionColors += color }
    override fun stopVision() { visionStops.incrementAndGet(); vision.value = cn.elonzh.hanppie.robot.vision.VisionState() }
    override fun startTracking() {}
    override fun selectTrackingTarget(x: Float, y: Float) {}
    override fun confirmTracking() {}
    override fun stopTracking() {}
    override val cameraYaw: Double? = null
    override var onVideo: ((ByteArray) -> Unit)? = null
    override var onAudio: ((ByteArray) -> Unit)? = null
    override val lab: RobotLabSession = object : RobotLabSession {
        override fun invalidateMode() {}
        override suspend fun upload(source: String, title: String, audio: List<LabAudioClip>): LabUpload =
            LabUpload("hash", "0123456789abcdef0123456789abcdef")
        override suspend fun start(): String = "0123456789abcdef0123456789abcdef"
        override suspend fun stop() { labStopCount.incrementAndGet() }
        override suspend fun complete(runId: String): Boolean = true
    }
    override val files: RobotFileService = object : RobotFileService {
        override suspend fun list(path: String): List<RobotFileEntry> = emptyList()
        override suspend fun upload(directory: String, preferredName: String, source: Source): RobotFileEntry =
            RobotFileEntry(path = "$directory/$preferredName", name = preferredName, kind = RobotFileKind.FILE, size = 0)
        override suspend fun download(path: String, destination: Sink) {}
        override suspend fun createDirectory(parent: String, name: String): RobotFileEntry =
            RobotFileEntry(path = "$parent/$name", name = name, kind = RobotFileKind.DIRECTORY, size = 0)
        override suspend fun rename(path: String, newName: String): String = "$path/$newName"
        override suspend fun delete(entry: RobotFileEntry) {}
        override fun close() {}
    }

    override suspend fun connect() {}
    override fun safetyStop() {}
    override suspend fun enterRemote() {}
    override suspend fun exitRemote() {}
    override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double, cameraRelative: Boolean) {
        driveCount.incrementAndGet()
    }
    override fun halt() { stopVision() }
    override fun fireInfrared() {
        infraredCount.incrementAndGet()
    }
    override suspend fun fireGelOnce(onSent: (Int) -> Unit): Int {
        val sequence = gelCount.incrementAndGet()
        onSent(sequence)
        delay(50)
        return sequence
    }
    override suspend fun playSpeaker(encoded: ByteArray): Int = 0
    override fun setLed(red: Int, green: Int, blue: Int, enabled: Boolean) {}
    override fun setSpeakerVolume(volume: Int) {}
    override fun media(start: Boolean, audio: Boolean, resolution: cn.elonzh.hanppie.robot.media.VideoResolution) {
        if (start) mediaStarts.incrementAndGet() else mediaStops.incrementAndGet()
    }
    override fun close() { closed = true }
}
