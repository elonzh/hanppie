package cn.elonzh.hanppie.robot.lab

import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.protocol.hexBytes
import cn.elonzh.hanppie.robot.session.AppSession
import cn.elonzh.hanppie.robot.session.RobotNetwork
import cn.elonzh.hanppie.robot.session.RobotTarget
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply

/** Serializes lifecycle operations. A sent start command is NOT execution confirmation. */
class LabController internal constructor(private val session: LabChannel,
                    private val uploadBytes: (ByteArray) -> Unit,
                    private val log: (String) -> Unit = {}) {
    constructor(session: AppSession, target: RobotTarget, log: (String) -> Unit = {}, network: RobotNetwork = RobotNetwork.Default) :
        this(session, { bytes -> transfer(target.ip, bytes, network = network) }, log)
    private val mutex = Mutex()
    private var entered = false
    private var program: LabProgram? = null
    private var digest: ByteArray? = null
    private var runId: String? = null
    private val uploadedAudioSlots = mutableMapOf<Int, String>()
    private var startRequested = false
    fun invalidateMode() {
        check(!startRequested)
        entered = false
        program = null
        digest = null
        runId = null
        uploadedAudioSlots.clear()
    }

    suspend fun upload(source: String, title: String, audio: List<LabAudioClip> = emptyList()): LabUpload = mutex.withLock {
        check(session.connected) { "机器人未连接" }
        check(!startRequested) { "请先停止已启动的脚本，再上传新脚本" }
        require(source.isNotBlank()) { "脚本不能为空" }
        val random = SecureRandom()
        val candidateGuid = ByteArray(16).also(random::nextBytes).hex()
        val candidateSign = ByteArray(8).also(random::nextBytes).hex()
        val candidate = LabProgram(
            source,
            candidateGuid,
            candidateSign,
            title,
        )
        val audioXml = labAudioListXml(audio) { slotId, md5 ->
            uploadedAudioSlots[slotId] == md5
        }
        val bytes = candidate.dsp(
            LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")),
            audioXml,
        )
        require(bytes.size <= LabProgram.MAX_DSP_BYTES) {
            "Lab 程序与自定义音频合计 ${bytes.size} 字节，超过上传上限 ${LabProgram.MAX_DSP_BYTES / (1024 * 1024)} MB；请缩短音频或减少数量"
        }
        log("Lab 上传开始；guid=$candidateGuid bytes=${bytes.size} audio=${audio.size}")
        program = null; digest = null; runId = null
        if (!entered) {
            session.labMode()
            session.send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NO_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SPECIAL_CONTROL, Protocol.MODE_LAB.hexBytes())
            delay(1000)
            session.send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_GAME_STATE_SYNC, Protocol.GAME_STATE_SYNC_LAB_PARAMS.hexBytes())
            delay(1000)
            session.send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_GET_SIGHT_BEAD_POSITION)
            entered = true
        }
        // A previous client can leave the native single slot marked as running after its Python
        // start() has returned. Running a replacement explicitly ends that stale native run first.
        session.send(Protocol.HOST_SCRATCH_SCRIPT, Protocol.ATTR_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_CUSTOM_UI_ATTRIBUTE_SET, byteArrayOf(0), sender = Protocol.HOST_SCRATCH_CLIENT); delay(52)
        session.labMode()
        session.send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_EXIT_LOW_POWER_MODE, byteArrayOf(0)); delay(20)
        session.send(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_CTRL, candidate.metadata(Protocol.SCRIPT_CTRL_METADATA_FULL)); delay(20)
        session.send(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_CTRL, candidate.guidMetadata()); delay(20)
        val size = byteArrayOf(1, 0, 4, 0) + ByteArray(4) { (bytes.size ushr (8 * it)).toByte() }
        session.send(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_DOWNLOAD_DATA, size); delay(20)
        withContext(Dispatchers.IO) { uploadBytes(bytes) }
        delay(500)
        check(session.connected) { "上传期间机器人连接已断开" }
        val hash = MessageDigest.getInstance("MD5").digest(bytes)
        program = candidate; digest = hash; runId = candidateGuid
        val activeSlots = mutableSetOf<Int>()
        audio.forEach { clip ->
            activeSlots.add(clip.id)
            uploadedAudioSlots[clip.id] = labAudioDigest(clip.packets)
        }
        uploadedAudioSlots.keys.retainAll(activeSlots)
        log("Lab 上传已确认；guid=$candidateGuid bytes=${bytes.size} md5=${hash.hex()}")
        LabUpload(hash.hex(), candidateGuid)
    }

    suspend fun start(): String = mutex.withLock {
        check(entered && session.connected) { "Lab 会话未就绪" }
        check(!startRequested) { "启动命令已经发送；如需重跑，请先停止脚本" }
        val current = checkNotNull(program) { "请先上传脚本" }
        val hash = checkNotNull(digest) { "请先上传脚本" }
        // Once registration begins a partial delivery is possible. Require stop before any retry.
        startRequested = true
        session.labMode(running = true)
        session.send(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_DOWNLOAD_FINSH, byteArrayOf(1, 0) + hash); delay(20)
        session.send(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_CTRL, current.metadata(Protocol.SCRIPT_CTRL_START)); delay(20)
        session.send(Protocol.HOST_SCRATCH_SCRIPT, Protocol.ATTR_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_CUSTOM_UI_ATTRIBUTE_SET, byteArrayOf(0), sender = Protocol.HOST_SCRATCH_CLIENT); delay(20)
        session.send(Protocol.HOST_SCRATCH_SCRIPT, Protocol.ATTR_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SUB_MOBILE_INFO, byteArrayOf(1))
        val currentRunId = checkNotNull(runId)
        log("Lab 启动序列已发送；runId=$currentRunId 等待 STARTED")
        currentRunId
    }

    suspend fun stop() = mutex.withLock {
        check(session.connected) { "机器人未连接，无法发送停止命令" }
        finishLocked()
        log("停止命令已发送")
    }

    /** Clear the native Lab run after a matching terminal marker; stale markers cannot stop a newer run. */
    suspend fun complete(completedRunId: String): Boolean = mutex.withLock {
        if (!startRequested || runId != completedRunId) return@withLock false
        check(session.connected) { "机器人未连接，无法结束已完成的脚本" }
        finishLocked()
        log("已收到机内完成标记并结束 Lab 运行")
        true
    }

    private suspend fun finishLocked() {
        program?.let {
            session.send(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_CTRL, it.metadata(Protocol.SCRIPT_CTRL_STOP)); delay(52)
        }
        session.send(Protocol.HOST_SCRATCH_SCRIPT, Protocol.ATTR_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_CUSTOM_UI_ATTRIBUTE_SET, byteArrayOf(0), sender = Protocol.HOST_SCRATCH_CLIENT)
        startRequested = false
        session.labMode()
    }

    companion object {
        internal fun transfer(ip: String, bytes: ByteArray, port: Int = Protocol.ROBOT_FTP_PORT, network: RobotNetwork = RobotNetwork.Default) {
            val ftp = FTPClient()
            ftp.setSocketFactory(network.socketFactory)
            ftp.connectTimeout = 5000
            ftp.defaultTimeout = 10000
            ftp.dataTimeout = Duration.ofSeconds(10)
            try {
                ftp.connect(ip, port)
                check(FTPReply.isPositiveCompletion(ftp.replyCode)) { "FTP 连接失败：${ftp.replyString}" }
                check(ftp.login("anonymous", "")) { "FTP 登录失败：${ftp.replyString}" }
                check(ftp.changeWorkingDirectory("python")) { "FTP python 目录不可用：${ftp.replyString}" }
                check(ftp.setFileType(FTP.BINARY_FILE_TYPE)) { "FTP 二进制模式失败" }
                ftp.enterLocalPassiveMode()
                check(ftp.storeFile("python_raw.dsp", bytes.inputStream())) { "FTP 上传失败：${ftp.replyString}" }
                ftp.logout()
            } finally { if (ftp.isConnected) ftp.disconnect() }
        }
    }
}
