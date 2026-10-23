package cn.elonzh.hanppie.robot.lab

import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.protocol.hexBytes
import cn.elonzh.hanppie.robot.session.robotMd5
import cn.elonzh.hanppie.robot.session.robotRandomBytes
import cn.elonzh.hanppie.robot.session.robotDate
import kotlinx.io.Buffer
import cn.elonzh.hanppie.robot.session.AppSession
import cn.elonzh.hanppie.robot.session.RobotTransport
import cn.elonzh.hanppie.robot.session.RobotTarget
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes lifecycle operations. A sent start command is NOT execution confirmation. */
class LabController internal constructor(private val session: LabChannel,
                    private val uploadBytes: (ByteArray) -> Unit,
                    private val log: (String) -> Unit = {}) {
    constructor(session: AppSession, target: RobotTarget, log: (String) -> Unit = {}, network: RobotTransport = RobotTransport.Default) :
        this(session, { bytes -> transfer(target.ip, bytes, network = network) }, log)
    private val mutex = Mutex()
    private var entered = false
    private var program: LabProgram? = null
    private var digest: ByteArray? = null
    private var runId: String? = null
    private var audioDigests = emptyMap<Int, String>()
    @Volatile private var modeToken = Any()
    private var startRequested = false
    fun invalidateMode() {
        check(!startRequested)
        modeToken = Any()
        entered = false
        program = null
        digest = null
        runId = null
        audioDigests = emptyMap()
    }

    suspend fun upload(source: String, title: String, audio: List<LabAudioClip> = emptyList()): LabUpload = mutex.withLock {
        check(session.connected) { "机器人未连接" }
        check(!startRequested) { "请先停止已启动的脚本，再上传新脚本" }
        require(source.isNotBlank()) { "脚本不能为空" }
        val token = modeToken
        val clips = audio.map { LabAudioClip(it.id, it.name, it.durationMillis, it.packets.copyOf()) }
        val candidateGuid = robotRandomBytes(16).hex()
        val candidateSign = robotRandomBytes(8).hex()
        val candidate = LabProgram(
            source,
            candidateGuid,
            candidateSign,
            title,
        )
        val date = robotDate()
        val fullBytes = candidate.dsp(date, labAudioListXml(clips))
        require(fullBytes.size <= LabProgram.MAX_DSP_BYTES) {
            "Lab 程序与自定义音频合计 ${fullBytes.size} 字节，超过上传上限 ${LabProgram.MAX_DSP_BYTES / (1024 * 1024)} MB；请缩短音频或减少数量"
        }
        val digests = clips.associate { it.id to labAudioDigest(it.packets) }
        checkCurrent(token)
        log("Lab 上传开始；guid=$candidateGuid audio=${clips.size}")
        program = null; digest = null; runId = null
        audioDigests = emptyMap()
        if (!entered) {
            session.labMode()
            session.send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NO_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SPECIAL_CONTROL, Protocol.MODE_LAB.hexBytes())
            delay(1000)
            checkCurrent(token)
            session.send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_GAME_STATE_SYNC, Protocol.GAME_STATE_SYNC_LAB_PARAMS.hexBytes())
            delay(1000)
            checkCurrent(token)
            session.send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_GET_SIGHT_BEAD_POSITION)
            entered = true
        }
        // A previous client can leave the native single slot marked as running after its Python
        // start() has returned. Running a replacement explicitly ends that stale native run first.
        session.send(Protocol.HOST_SCRATCH_SCRIPT, Protocol.ATTR_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_CUSTOM_UI_ATTRIBUTE_SET, byteArrayOf(0), sender = Protocol.HOST_SCRATCH_CLIENT); delay(52)
        session.labMode()
        session.send(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_EXIT_LOW_POWER_MODE, byteArrayOf(0)); delay(20)
        checkCurrent(token)
        val matches = if (digests.isEmpty()) emptySet() else try {
            LabResourceProtocol.audioMatches(session.requestLab(Protocol.CMD_RM_SCRIPT_CTRL,
                LabResourceProtocol.audioQuery(candidateGuid, digests)), digests.keys)
        } catch (error: RobotRequestException) {
            if (error.problem == RequestProblem.DISCONNECTED) throw error
            log("机内音频摘要未确认，上传完整音频；原因=${error.problem}")
            emptySet()
        }
        checkCurrent(token)
        val bytes = if (matches.isEmpty()) fullBytes else candidate.dsp(date, labAudioListXml(clips) { id, _ -> id in matches })
        log("机内音频核对：${matches.size}/${digests.size} 项匹配；上传 ${bytes.size} 字节")
        session.send(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_CTRL, candidate.metadata(Protocol.SCRIPT_CTRL_METADATA_FULL)); delay(20)
        checkCurrent(token)
        session.send(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_CTRL, candidate.guidMetadata()); delay(20)
        checkCurrent(token)
        val size = byteArrayOf(1, 0, 4, 0) + ByteArray(4) { (bytes.size ushr (8 * it)).toByte() }
        session.send(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_DOWNLOAD_DATA, size); delay(20)
        checkCurrent(token)
        withContext(Dispatchers.IO) { uploadBytes(bytes) }
        delay(500)
        checkCurrent(token)
        val hash = robotMd5(bytes)
        program = candidate; digest = hash; runId = candidateGuid
        audioDigests = digests
        log("Lab 文件已传输；guid=$candidateGuid bytes=${bytes.size} md5=${hash.hex()}；启动前核对机内资源")
        LabUpload(hash.hex(), candidateGuid)
    }

    suspend fun start(): String = mutex.withLock {
        check(entered && session.connected) { "Lab 会话未就绪" }
        check(!startRequested) { "启动命令已经发送；如需重跑，请先停止脚本" }
        val current = checkNotNull(program) { "请先上传脚本" }
        val hash = checkNotNull(digest) { "请先上传脚本" }
        val token = modeToken
        checkCurrent(token)
        // Once registration begins a partial delivery is possible. Require stop before any retry.
        startRequested = true
        session.labMode(running = true)
        try {
            LabResourceProtocol.accepted(session.requestLab(Protocol.CMD_RM_SCRIPT_DOWNLOAD_FINSH, byteArrayOf(1, 0) + hash))
            checkCurrent(token)
            check(LabResourceProtocol.programMatches(session.requestLab(Protocol.CMD_RM_SCRIPT_CTRL,
                LabResourceProtocol.programQuery(current)))) { "机内程序摘要不匹配，请停止后重新上传" }
            checkCurrent(token)
            if (audioDigests.isNotEmpty()) {
                val matches = LabResourceProtocol.audioMatches(session.requestLab(Protocol.CMD_RM_SCRIPT_CTRL,
                    LabResourceProtocol.audioQuery(current.guid, audioDigests)), audioDigests.keys)
                check(matches == audioDigests.keys) { "机内音频资源已变化，请停止后重新上传" }
            }
            checkCurrent(token)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("机内资源未确认，未发送执行命令；请停止后重新上传")
            throw error
        }
        log("机内程序与音频资源已核对；guid=${current.guid}")
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

    private suspend fun checkCurrent(token: Any) {
        currentCoroutineContext().ensureActive()
        check(session.connected) { "机器人连接已断开" }
        check(modeToken === token) { "Lab 模式已变化，请重新上传" }
    }

    companion object {
        internal fun transfer(ip: String, bytes: ByteArray, port: Int = Protocol.ROBOT_FTP_PORT, network: RobotTransport = RobotTransport.Default) {
            network.ftp().use { ftp ->
                ftp.connect(ip, port)
                check(ftp.login()) { "FTP 登录失败：${ftp.replyText}" }
                check(ftp.changeWorkingDirectory("python")) { "FTP python 目录不可用：${ftp.replyText}" }
                check(ftp.binary()) { "FTP 二进制模式失败" }
                check(ftp.storeFile("python_raw.dsp", Buffer().apply { write(bytes) })) { "FTP 上传失败：${ftp.replyText}" }
            }
        }
    }
}
