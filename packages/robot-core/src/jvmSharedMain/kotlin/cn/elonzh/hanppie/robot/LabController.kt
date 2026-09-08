package cn.elonzh.hanppie.robot

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
    private var startRequested = false
    fun invalidateMode() { check(!startRequested); entered = false; program = null; digest = null }

    suspend fun upload(source: String, title: String): String = mutex.withLock {
        check(session.connected) { "机器人未连接" }
        check(!startRequested) { "请先停止已启动的脚本，再上传新脚本" }
        val random = SecureRandom()
        val candidate = LabProgram(source, ByteArray(16).also(random::nextBytes).hex(),
            ByteArray(8).also(random::nextBytes).hex(), title)
        val bytes = candidate.dsp(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")))
        program = null; digest = null
        if (!entered) {
            session.labMode()
            session.send(9, 0, 0x3f, 4, "020302".hexBytes())
            delay(1000)
            session.send(9, 0x40, 0x3f, 9,
                "05000000ea03000000000000ef0300000a000000f003000000000000f1030000b80b0000f2030000dc0500000000000000000000".hexBytes())
            delay(1000)
            session.send(9, 0x40, 0x3f, 0x57)
            entered = true
        }
        session.send(9, 0x40, 0x3f, 0x4c, byteArrayOf(0)); delay(20)
        session.send(0xa9, 0x40, 0x3f, 0xa3, candidate.metadata(0x21)); delay(20)
        session.send(0xa9, 0x40, 0x3f, 0xa3, candidate.guidMetadata()); delay(20)
        val size = byteArrayOf(1, 0, 4, 0) + ByteArray(4) { (bytes.size ushr (8 * it)).toByte() }
        session.send(0xa9, 0x40, 0x3f, 0xa1, size); delay(20)
        withContext(Dispatchers.IO) { uploadBytes(bytes) }
        delay(500)
        check(session.connected) { "上传期间机器人连接已断开" }
        val hash = MessageDigest.getInstance("MD5").digest(bytes)
        program = candidate; digest = hash
        log("FTP 已确认上传 ${bytes.size} 字节，MD5 ${hash.hex()}")
        hash.hex()
    }

    suspend fun start() = mutex.withLock {
        check(entered && session.connected) { "Lab 会话未就绪" }
        check(!startRequested) { "启动命令已经发送；如需重跑，请先停止脚本" }
        val current = checkNotNull(program) { "请先上传脚本" }
        val hash = checkNotNull(digest) { "请先上传脚本" }
        // Once registration begins a partial delivery is possible. Require stop before any retry.
        startRequested = true
        session.labMode(running = true)
        session.send(0xa9, 0x40, 0x3f, 0xa2, byteArrayOf(1, 0) + hash); delay(20)
        session.send(0xa9, 0x40, 0x3f, 0xa3, current.metadata(0x52)); delay(20)
        session.send(0xc9, 0x80, 0x3f, 0xba, byteArrayOf(0), sender = 0x42); delay(20)
        session.send(0xc9, 0x80, 0x3f, 0xab, byteArrayOf(1))
        log("启动命令已发送；需结合机器人回报确认脚本执行结果")
    }

    suspend fun stop() = mutex.withLock {
        check(session.connected) { "机器人未连接，无法发送停止命令" }
        program?.let {
            session.send(0xa9, 0x40, 0x3f, 0xa3, it.metadata(0x55)); delay(52)
        }
        session.send(0xc9, 0x80, 0x3f, 0xba, byteArrayOf(0), sender = 0x42)
        startRequested = false
        session.labMode()
        log("停止命令已发送")
    }

    companion object {
        internal fun transfer(ip: String, bytes: ByteArray, port: Int = 21, network: RobotNetwork = RobotNetwork.Default) {
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
