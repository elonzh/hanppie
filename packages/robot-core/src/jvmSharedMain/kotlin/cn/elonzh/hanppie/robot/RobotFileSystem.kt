package cn.elonzh.hanppie.robot

import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply

enum class RobotFileKind { DIRECTORY, FILE, LINK, UNKNOWN }

data class RobotFileEntry(
    val path: String,
    val name: String,
    val kind: RobotFileKind,
    val size: Long = 0,
    val modifiedAtEpochMillis: Long? = null,
) {
    val isDirectory: Boolean get() = kind == RobotFileKind.DIRECTORY
    val isRegularFile: Boolean get() = kind == RobotFileKind.FILE
    val isProtected: Boolean get() = RobotFileSystem.isProtected(path)
}

/**
 * Bounded view of the anonymous FTP tree exposed by the S1 at `/data/ftp`.
 * Paths are always absolute inside the FTP chroot and never refer to Android system paths.
 */
class RobotFileSystem(
    private val target: RobotTarget,
    private val network: RobotNetwork = RobotNetwork.Default,
    private val port: Int = 21,
) : AutoCloseable {
    private val activeClients = ConcurrentHashMap.newKeySet<FTPClient>()
    @Volatile private var closed = false

    init { require(port in 1..65535) }

    suspend fun list(path: String): List<RobotFileEntry> = withContext(Dispatchers.IO) {
        val directory = normalizePath(path)
        withClient { ftp ->
            val files = ftp.listFiles(directory)
            check(FTPReply.isPositiveCompletion(ftp.replyCode)) {
                "FTP 目录读取失败：${ftp.replyText()}"
            }
            files.mapNotNull { file ->
                val name = file.name
                if (name == "." || name == ".." || !isSafeName(name)) return@mapNotNull null
                RobotFileEntry(
                    path = child(directory, name),
                    name = name,
                    kind = file.kind(),
                    size = file.size.coerceAtLeast(0),
                    modifiedAtEpochMillis = file.timestampInstant?.toEpochMilli(),
                )
            }.sortedWith(compareBy<RobotFileEntry>({ !it.isDirectory }, { it.name.lowercase() }, { it.name }))
        }
    }

    /** Uploads without overwriting: an occupied name becomes `name-2.ext`, `name-3.ext`, and so on. */
    suspend fun upload(directory: String, preferredName: String, source: InputStream): RobotFileEntry =
        withContext(Dispatchers.IO) {
            val parent = normalizePath(directory)
            val safeName = portableUploadName(preferredName)
            withClient { ftp ->
                val occupied = ftp.listFiles(parent).mapTo(mutableSetOf()) { it.name }
                check(FTPReply.isPositiveCompletion(ftp.replyCode)) {
                    "FTP 目录读取失败：${ftp.replyText()}"
                }
                val name = uniqueName(parent, safeName, occupied)
                val path = child(parent, name)
                val counted = CountingInputStream(source)
                check(ftp.storeFile(path, counted)) { "FTP 上传失败：${ftp.replyText()}" }
                RobotFileEntry(path, name, RobotFileKind.FILE, counted.count)
            }
        }

    suspend fun download(path: String, destination: OutputStream) = withContext(Dispatchers.IO) {
        val source = normalizePath(path)
        check(source != "/") { "FTP 根目录不能作为文件下载" }
        withClient { ftp ->
            check(ftp.retrieveFile(source, destination)) { "FTP 下载失败：${ftp.replyText()}" }
        }
    }

    suspend fun createDirectory(parent: String, name: String): RobotFileEntry = withContext(Dispatchers.IO) {
        val directory = normalizePath(parent)
        val path = child(directory, requireName(name))
        check(!isProtected(path)) { "保留路径不能由文件管理器创建" }
        withClient { ftp ->
            check(ftp.makeDirectory(path)) { "FTP 新建目录失败：${ftp.replyText()}" }
            RobotFileEntry(path, path.substringAfterLast('/'), RobotFileKind.DIRECTORY)
        }
    }

    suspend fun rename(path: String, newName: String): String = withContext(Dispatchers.IO) {
        val source = normalizePath(path)
        check(source != "/") { "FTP 根目录不能重命名" }
        check(!isProtected(source)) { "Lab 当前上传槽位不能重命名" }
        val destination = child(parent(source), requireName(newName))
        check(!isProtected(destination)) { "不能覆盖 Lab 当前上传槽位" }
        withClient { ftp ->
            check(ftp.rename(source, destination)) { "FTP 重命名失败：${ftp.replyText()}" }
        }
        destination
    }

    /** Directories are removed only when the server confirms they are empty; recursive deletion is intentionally absent. */
    suspend fun delete(entry: RobotFileEntry) = withContext(Dispatchers.IO) {
        val path = normalizePath(entry.path)
        check(path != "/") { "FTP 根目录不能删除" }
        check(!isProtected(path)) { "Lab 当前上传槽位不能删除" }
        withClient { ftp ->
            val deleted = if (entry.isDirectory) ftp.removeDirectory(path) else ftp.deleteFile(path)
            check(deleted) {
                if (entry.isDirectory) "FTP 目录删除失败；请确认目录为空：${ftp.replyText()}"
                else "FTP 文件删除失败：${ftp.replyText()}"
            }
        }
    }

    private fun <T> withClient(block: (FTPClient) -> T): T {
        check(!closed) { "机内文件服务已关闭" }
        val ftp = FTPClient()
        activeClients += ftp
        try {
            check(!closed) { "机内文件服务已关闭" }
            ftp.setSocketFactory(network.socketFactory)
            ftp.setAutodetectUTF8(true)
            ftp.controlEncoding = Charsets.UTF_8.name()
            ftp.connectTimeout = 5000
            ftp.defaultTimeout = 10000
            ftp.dataTimeout = Duration.ofSeconds(30)
            ftp.connect(target.ip, port)
            check(FTPReply.isPositiveCompletion(ftp.replyCode)) { "FTP 连接失败：${ftp.replyText()}" }
            check(ftp.login("anonymous", "")) { "FTP 登录失败：${ftp.replyText()}" }
            check(ftp.setFileType(FTP.BINARY_FILE_TYPE)) { "FTP 二进制模式失败：${ftp.replyText()}" }
            ftp.enterLocalPassiveMode()
            ftp.listHiddenFiles = true
            return block(ftp)
        } finally {
            if (ftp.isConnected) {
                runCatching { ftp.logout() }
                runCatching { ftp.disconnect() }
            }
            activeClients -= ftp
        }
    }

    /** Interrupts active FTP sockets when the robot session is lost or the app leaves the foreground. */
    override fun close() {
        closed = true
        activeClients.toList().forEach { ftp ->
            if (ftp.isConnected) runCatching { ftp.disconnect() }
        }
    }

    private fun uniqueName(parent: String, preferred: String, occupied: Set<String>): String {
        fun available(name: String) = name !in occupied && !isProtected(child(parent, name))
        if (available(preferred)) return preferred
        val dot = preferred.lastIndexOf('.').takeIf { it > 0 } ?: preferred.length
        val stem = preferred.substring(0, dot)
        val extension = preferred.substring(dot)
        for (suffix in 2..999) {
            val candidate = requireName("$stem-$suffix$extension")
            if (available(candidate)) return candidate
        }
        error("同名文件过多，请先整理当前目录")
    }

    companion object {
        private val protectedPaths = setOf("/python/python_raw.dsp")

        fun isProtected(path: String): Boolean = runCatching { normalizePath(path) in protectedPaths }.getOrDefault(false)

        fun normalizePath(path: String): String {
            require(path.startsWith('/')) { "机内路径必须从 / 开始" }
            if (path == "/") return path
            require(!path.endsWith('/')) { "机内路径不能以 / 结尾" }
            val parts = path.drop(1).split('/')
            require(parts.isNotEmpty() && parts.all(::isSafeName)) { "机内路径包含无效片段" }
            return "/" + parts.joinToString("/")
        }

        fun requireName(name: String): String {
            require(isSafeName(name)) { "S1 FTP 名称需为 1–128 个 ASCII 字符，且不能包含 /、\\、控制字符或首尾空格" }
            return name
        }

        /**
         * The stock S1 FTP server does not advertise UTF-8 and replaces non-ASCII command bytes with question marks.
         * Uploads therefore receive a predictable portable name instead of silently creating a corrupted one.
         */
        fun portableUploadName(preferredName: String): String {
            val trimmed = preferredName.trim().ifEmpty { "upload" }
            val extensionStart = trimmed.lastIndexOf('.').takeIf { it > 0 }
            val rawStem = extensionStart?.let { trimmed.substring(0, it) } ?: trimmed
            val rawExtension = extensionStart?.let { trimmed.substring(it) }.orEmpty()
            val stem = rawStem.map { char -> if (char.code in 0x20..0x7e && char != '/' && char != '\\') char else '-' }
                .joinToString("")
                .replace(Regex("-+"), "-")
                .trim(' ', '-', '.')
                .ifEmpty { "upload" }
            val extension = rawExtension.takeIf {
                it.length <= 16 && it.all { char -> char == '.' || char == '-' || char == '_' || char.isLetterOrDigit() }
            }.orEmpty()
            val maxStemLength = (128 - extension.length).coerceAtLeast(1)
            return requireName(stem.take(maxStemLength).trimEnd() + extension)
        }

        private fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name.length <= 128 && name == name.trim() &&
                name != "." && name != ".." && '/' !in name && '\\' !in name &&
                name.none(Char::isISOControl) && name.all { it.code in 0x20..0x7e }

        private fun child(parent: String, name: String): String =
            if (parent == "/") "/$name" else "$parent/$name"

        private fun parent(path: String): String = path.substringBeforeLast('/', "").ifEmpty { "/" }
    }
}

private fun FTPFile.kind(): RobotFileKind = when {
    isDirectory -> RobotFileKind.DIRECTORY
    isFile -> RobotFileKind.FILE
    isSymbolicLink -> RobotFileKind.LINK
    else -> RobotFileKind.UNKNOWN
}

private fun FTPClient.replyText(): String = replyString?.trim().orEmpty().ifBlank { "code $replyCode" }

private class CountingInputStream(source: InputStream) : FilterInputStream(source) {
    var count: Long = 0
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) count += it }
}
