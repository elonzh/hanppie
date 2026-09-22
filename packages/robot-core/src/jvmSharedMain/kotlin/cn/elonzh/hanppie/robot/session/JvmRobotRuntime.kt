package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.files.RobotFileService
import cn.elonzh.hanppie.robot.files.RobotFileSystem
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.robot.lab.LabController
import cn.elonzh.hanppie.robot.lab.LabUpload
import cn.elonzh.hanppie.robot.media.VideoResolution
import cn.elonzh.hanppie.robot.protocol.DiscoveredRobot
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.product.RobotProduct

/** JVM transport composition. Common callers only observe [RobotRuntime]. */
class JvmRobotRuntime(
    private val networkProvider: () -> RobotNetwork = { RobotNetwork.Default },
) : RobotRuntime {
    override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> =
        AppSession.discover(timeoutMillis = timeoutMillis, network = networkProvider())

    override suspend fun waitForRouterPairing(appId: String): RouterPairing =
        AppSession.waitForRouterPairing(appId, network = networkProvider())

    override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String) =
        AppSession.acknowledgeRouterPairing(pairing, appId, network = networkProvider())

    override fun open(
        target: RobotTarget,
        onFrame: (DussFrame) -> Unit,
        onLog: (String) -> Unit,
        onLost: (RobotSession, String) -> Unit,
    ): RobotSession {
        val network = networkProvider()
        lateinit var result: JvmRobotSession
        val app = AppSession(
            target,
            onFrame,
            onLog,
            network,
        ) { reason -> onLost(result, reason) }
        result = JvmRobotSession(
            app = app,
            labController = LabController(app, target, onLog, network),
            files = RobotFileSystem(target, network),
        )
        return result
    }
}

private class JvmRobotSession(
    private val app: AppSession,
    labController: LabController,
    override val files: RobotFileService,
) : RobotSession {
    override val connected: Boolean get() = app.connected
    override val product: RobotProduct get() = app.product
    override val cameraYaw: Double? get() = app.cameraYaw
    override var onVideo: ((ByteArray) -> Unit)?
        get() = app.onVideo
        set(value) { app.onVideo = value }
    override var onAudio: ((ByteArray) -> Unit)?
        get() = app.onAudio
        set(value) { app.onAudio = value }
    override val lab: RobotLabSession = object : RobotLabSession {
        override fun invalidateMode() = labController.invalidateMode()
        override suspend fun upload(source: String, title: String, audio: List<LabAudioClip>): LabUpload =
            labController.upload(source, title, audio)
        override suspend fun start(): String = labController.start()
        override suspend fun stop() = labController.stop()
        override suspend fun complete(runId: String): Boolean = labController.complete(runId)
    }

    override suspend fun connect() = app.connect()
    override fun safetyStop() = app.safetyStop()
    override suspend fun enterRemote() = app.enterRemote()
    override suspend fun exitRemote() = app.exitRemote()
    override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double, cameraRelative: Boolean) =
        app.drive(x, y, z, pitch, yaw, cameraRelative)
    override fun halt() = app.halt()
    override fun fireInfrared() = app.fireInfrared()
    override suspend fun fireGelOnce(onSent: (Int) -> Unit): Int = app.fireGelOnce(onSent)
    override suspend fun playSpeaker(encoded: ByteArray): Int = app.playSpeaker(encoded)
    override fun setLed(red: Int, green: Int, blue: Int, enabled: Boolean) {
        app.setLed(red, green, blue, enabled)
    }
    override fun setSpeakerVolume(volume: Int) {
        app.setSpeakerVolume(volume)
    }
    override fun media(start: Boolean, audio: Boolean, resolution: VideoResolution) = app.media(start, audio, resolution)

    override fun close() {
        files.close()
        app.close()
    }
}
