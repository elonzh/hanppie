package cn.elonzh.hanppie.robot.files

import cn.elonzh.hanppie.robot.session.RobotLock
import cn.elonzh.hanppie.robot.session.RobotFtpClient
import kotlin.concurrent.Volatile
import cn.elonzh.hanppie.robot.session.RobotTransport
import cn.elonzh.hanppie.robot.session.RobotTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * Bounded view of the anonymous FTP tree currently verified at `/data/ftp` on S1.
 * Paths are always absolute inside the FTP chroot and never refer to Android system paths.
 */
class RobotFileSystem(
    private val target: RobotTarget,
    private val network: RobotTransport = RobotTransport.Default,
    private val port: Int = 21,
) : RobotFileService {
    private val clientsLock = RobotLock()
    private val activeClients = mutableSetOf<RobotFtpClient>()
    @Volatile private var closed = false

    init { require(port in 1..65535) }

    override suspend fun list(path: String): List<RobotFileEntry> = withContext(Dispatchers.IO) {
        val directory = RobotFilePath.normalize(path)
        withClient { ftp ->
            val files = ftp.listFiles(directory)
            check(ftp.replyCode in 200..299) {
                "FTP 目录读取失败：${ftp.replyText}"
            }
            files.mapNotNull { file ->
                val name = file.name
                if (name == "." || name == ".." || runCatching { RobotFilePath.requireName(name) }.isFailure) return@mapNotNull null
                RobotFileEntry(
                    path = RobotFilePath.child(directory, name),
                    name = name,
                    kind = file.kind,
                    size = file.size.coerceAtLeast(0),
                    modifiedAtEpochMillis = file.modifiedAtEpochMillis,
                )
            }.sortedWith(compareBy<RobotFileEntry>({ !it.isDirectory }, { it.name.lowercase() }, { it.name }))
        }
    }

    /** Uploads without overwriting: an occupied name becomes `name-2.ext`, `name-3.ext`, and so on. */
    override suspend fun upload(directory: String, preferredName: String, source: Source): RobotFileEntry =
        withContext(Dispatchers.IO) {
            val parent = RobotFilePath.normalize(directory)
            val safeName = RobotFilePath.portableUploadName(preferredName)
            withClient { ftp ->
                val occupied = ftp.listFiles(parent).mapTo(mutableSetOf()) { it.name }
                check(ftp.replyCode in 200..299) {
                    "FTP 目录读取失败：${ftp.replyText}"
                }
                val name = uniqueName(parent, safeName, occupied)
                val path = RobotFilePath.child(parent, name)

                check(ftp.storeFile(path, source)) { "FTP 上传失败：${ftp.replyText}" }
                RobotFileEntry(path, name, RobotFileKind.FILE, ftp.transferredBytes)
            }
        }

    override suspend fun download(path: String, destination: Sink) = withContext(Dispatchers.IO) {
        val source = RobotFilePath.normalize(path)
        check(source != "/") { "FTP 根目录不能作为文件下载" }
        withClient { ftp ->
            check(ftp.retrieveFile(source, destination)) { "FTP 下载失败：${ftp.replyText}" }
        }
    }

    override suspend fun createDirectory(parent: String, name: String): RobotFileEntry = withContext(Dispatchers.IO) {
        val directory = RobotFilePath.normalize(parent)
        val path = RobotFilePath.child(directory, RobotFilePath.requireName(name))
        check(!RobotFilePath.isProtected(path)) { "保留路径不能由文件管理器创建" }
        withClient { ftp ->
            check(ftp.makeDirectory(path)) { "FTP 新建目录失败：${ftp.replyText}" }
            RobotFileEntry(path, path.substringAfterLast('/'), RobotFileKind.DIRECTORY)
        }
    }

    override suspend fun rename(path: String, newName: String): String = withContext(Dispatchers.IO) {
        val source = RobotFilePath.normalize(path)
        check(source != "/") { "FTP 根目录不能重命名" }
        check(!RobotFilePath.isProtected(source)) { "Lab 当前上传槽位不能重命名" }
        val destination = RobotFilePath.child(RobotFilePath.parent(source), RobotFilePath.requireName(newName))
        check(!RobotFilePath.isProtected(destination)) { "不能覆盖 Lab 当前上传槽位" }
        withClient { ftp ->
            check(ftp.rename(source, destination)) { "FTP 重命名失败：${ftp.replyText}" }
        }
        destination
    }

    /** Directories are removed only when the server confirms they are empty; recursive deletion is intentionally absent. */
    override suspend fun delete(entry: RobotFileEntry) = withContext(Dispatchers.IO) {
        val path = RobotFilePath.normalize(entry.path)
        check(path != "/") { "FTP 根目录不能删除" }
        check(!RobotFilePath.isProtected(path)) { "Lab 当前上传槽位不能删除" }
        withClient { ftp ->
            val deleted = if (entry.isDirectory) ftp.removeDirectory(path) else ftp.deleteFile(path)
            check(deleted) {
                if (entry.isDirectory) "FTP 目录删除失败；请确认目录为空：${ftp.replyText}"
                else "FTP 文件删除失败：${ftp.replyText}"
            }
        }
    }

    private fun <T> withClient(block: (RobotFtpClient) -> T): T {
        check(!closed) { "机内文件服务已关闭" }
        val ftp = network.ftp()
        clientsLock.withLock { activeClients += ftp }
        try {
            check(!closed) { "机内文件服务已关闭" }
            ftp.connect(target.ip, port)
            check(ftp.login()) { "FTP 登录失败：${ftp.replyText}" }
            check(ftp.binary()) { "FTP 二进制模式失败：${ftp.replyText}" }
            return block(ftp)
        } finally {
            runCatching { ftp.close() }
            clientsLock.withLock { activeClients -= ftp }
        }
    }

    /** Interrupts active FTP sockets when the robot session is lost or the app leaves the foreground. */
    override fun close() {
        closed = true
        clientsLock.withLock { activeClients.toList() }.forEach { ftp ->
            runCatching { ftp.close() }
        }
    }

    private fun uniqueName(parent: String, preferred: String, occupied: Set<String>): String {
        fun available(name: String) = name !in occupied && !RobotFilePath.isProtected(RobotFilePath.child(parent, name))
        if (available(preferred)) return preferred
        val dot = preferred.lastIndexOf('.').takeIf { it > 0 } ?: preferred.length
        val stem = preferred.substring(0, dot)
        val extension = preferred.substring(dot)
        for (suffix in 2..999) {
            val candidate = RobotFilePath.requireName("$stem-$suffix$extension")
            if (available(candidate)) return candidate
        }
        error("同名文件过多，请先整理当前目录")
    }

}
