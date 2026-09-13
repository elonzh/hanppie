package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.files.RobotFileService
import cn.elonzh.hanppie.robot.files.RobotFileSystem
import cn.elonzh.hanppie.robot.lab.LabController
import cn.elonzh.hanppie.robot.lab.LabUpload
import cn.elonzh.hanppie.robot.protocol.DiscoveredRobot
import cn.elonzh.hanppie.robot.protocol.DussFrame

/** JVM transport composition. Common callers only observe [RobotRuntime]. */
class JvmRobotRuntime(
    private val networkProvider: () -> RobotNetwork = { RobotNetwork.Default },
) : RobotRuntime {
    override suspend fun discover(): List<DiscoveredRobot> =
        AppSession.discover(network = networkProvider())

    override fun open(
        target: RobotTarget,
        onFrame: (DussFrame) -> Unit,
        onLog: (String) -> Unit,
        onLost: (RobotSession, String) -> Unit,
    ): RobotSession {
        val network = networkProvider()
        lateinit var result: JvmRobotSession
        val app = AppSession(target, onFrame, onLog, network) { reason -> onLost(result, reason) }
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
    override val cameraYaw: Double? get() = app.cameraYaw
    override var onVideo: ((ByteArray) -> Unit)?
        get() = app.onVideo
        set(value) { app.onVideo = value }
    override var onAudio: ((ByteArray) -> Unit)?
        get() = app.onAudio
        set(value) { app.onAudio = value }
    override val lab: RobotLabSession = object : RobotLabSession {
        override fun invalidateMode() = labController.invalidateMode()
        override suspend fun upload(source: String, title: String): LabUpload = labController.upload(source, title)
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
    override suspend fun fireGelOnce(): Int = app.fireGelOnce()
    override suspend fun playSpeaker(encoded: ByteArray): Int = app.playSpeaker(encoded)
    override fun setLed(red: Int, green: Int, blue: Int, enabled: Boolean) {
        app.setLed(red, green, blue, enabled)
    }
    override fun media(start: Boolean, audio: Boolean) = app.media(start, audio)

    override fun close() {
        files.close()
        app.close()
    }
}
