package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.files.RobotFileService
import cn.elonzh.hanppie.robot.lab.LabUpload
import cn.elonzh.hanppie.robot.protocol.DiscoveredRobot
import cn.elonzh.hanppie.robot.protocol.DussFrame

data class RobotTarget(
    val ip: String,
    val appId: String,
    val localIp: String = "0.0.0.0",
    val localPort: Int = 10609,
    val remotePort: Int = 10607,
) {
    init {
        require(ip.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }) {
            "请指定机器人 IPv4 地址"
        }
        require(Regex("[0-9a-fA-F]{8}").matches(appId)) { "AppID 必须是 8 位十六进制字符" }
        require(localPort in 0..65535 && remotePort in 1..65535)
    }
}

interface RobotLabSession {
    fun invalidateMode()
    suspend fun upload(source: String, title: String): LabUpload
    suspend fun start(): String
    suspend fun stop()
    suspend fun complete(runId: String): Boolean
}

interface RobotSession : AutoCloseable {
    val connected: Boolean
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
    suspend fun fireGelOnce(): Int
    suspend fun playSpeaker(encoded: ByteArray): Int
    fun setLed(red: Int, green: Int, blue: Int, enabled: Boolean)
    fun media(start: Boolean, audio: Boolean)
}

interface RobotRuntime {
    suspend fun discover(): List<DiscoveredRobot>
    fun open(
        target: RobotTarget,
        onFrame: (DussFrame) -> Unit,
        onLog: (String) -> Unit,
        onLost: (RobotSession, String) -> Unit,
    ): RobotSession
}
