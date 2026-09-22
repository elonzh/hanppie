package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.files.RobotFileService
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.robot.lab.LabUpload
import cn.elonzh.hanppie.robot.media.VideoResolution
import cn.elonzh.hanppie.robot.protocol.DiscoveredRobot
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.product.RobotProduct
import cn.elonzh.hanppie.robot.protocol.Protocol

data class RobotTarget(
    val ip: String,
    val appId: String,
    val localIp: String = "0.0.0.0",
    val localPort: Int = Protocol.LOCAL_CONTROL_PORT,
    val remotePort: Int = Protocol.ROBOT_CONTROL_PORT,
    val identityTimeoutMillis: Long = 4_000,
    val sessionTimeoutMillis: Long = 5_000,
) {
    init {
        require(ip.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }) {
            "请指定机器人 IPv4 地址"
        }
        require(Regex("[0-9a-fA-F]{8}").matches(appId)) { "AppID 必须是 8 位十六进制字符" }
        require(localPort in 0..65535 && remotePort in 1..65535)
        require(identityTimeoutMillis in 200..30_000 && sessionTimeoutMillis in 200..30_000)
    }

    fun forAutomaticProbe(): RobotTarget = copy(identityTimeoutMillis = 800, sessionTimeoutMillis = 1_800)

}

/** Router-mode pairing broadcast together with the UDP endpoint that must receive the final ACK. */
data class RouterPairing(
    val robot: DiscoveredRobot,
    val sourcePort: Int,
) {
    init { require(sourcePort in 1..65535) }
}

interface RobotLabSession {
    fun invalidateMode()
    suspend fun upload(source: String, title: String, audio: List<LabAudioClip> = emptyList()): LabUpload
    suspend fun start(): String
    suspend fun stop()
    suspend fun complete(runId: String): Boolean
}

interface RobotSession : AutoCloseable {
    val connected: Boolean
    val product: RobotProduct
    val cameraYaw: Double?
    var onVideo: ((ByteArray) -> Unit)?
    var onAudio: ((ByteArray) -> Unit)?
    val lab: RobotLabSession
    val files: RobotFileService

    suspend fun connect()
    fun safetyStop()
    suspend fun enterRemote()
    suspend fun exitRemote()
    fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double, cameraRelative: Boolean)
    fun halt()
    fun fireInfrared()
    /** Reports the command send before the firing light cycle ends; not physical hit confirmation. */
    suspend fun fireGelOnce(onSent: (Int) -> Unit = {}): Int
    suspend fun playSpeaker(encoded: ByteArray): Int
    fun setLed(red: Int, green: Int, blue: Int, enabled: Boolean)
    fun setSpeakerVolume(volume: Int)
    fun media(start: Boolean, audio: Boolean, resolution: VideoResolution = VideoResolution.R720P)
}

interface RobotRuntime {
    suspend fun discover(timeoutMillis: Long = 3000): List<DiscoveredRobot>
    suspend fun waitForRouterPairing(appId: String): RouterPairing
    suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String)
    fun open(
        target: RobotTarget,
        onFrame: (DussFrame) -> Unit,
        onLog: (String) -> Unit,
        onLost: (RobotSession, String) -> Unit,
    ): RobotSession
}
