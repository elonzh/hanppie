package cn.elonzh.hanppie.robot.files

import kotlinx.io.Sink
import kotlinx.io.Source

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
    val isProtected: Boolean get() = RobotFilePath.isProtected(path)
}

/** Platform-independent operations exposed by the robot's bounded file tree. */
interface RobotFileService : AutoCloseable {
    suspend fun list(path: String): List<RobotFileEntry>
    suspend fun upload(directory: String, preferredName: String, source: Source): RobotFileEntry
    suspend fun download(path: String, destination: Sink)
    suspend fun createDirectory(parent: String, name: String): RobotFileEntry
    suspend fun rename(path: String, newName: String): String
    suspend fun delete(entry: RobotFileEntry)
}

object RobotFilePath {
    private val protectedPaths = setOf("/python/python_raw.dsp")

    fun isProtected(path: String): Boolean =
        runCatching { normalize(path) in protectedPaths }.getOrDefault(false)

    fun normalize(path: String): String {
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

    /** Produces the ASCII-only name required by the stock S1 FTP server. */
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

    fun child(parent: String, name: String): String =
        if (parent == "/") "/$name" else "$parent/$name"

    fun parent(path: String): String = path.substringBeforeLast('/', "").ifEmpty { "/" }

    private fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 128 && name == name.trim() &&
            name != "." && name != ".." && '/' !in name && '\\' !in name &&
            name.none(Char::isISOControl) && name.all { it.code in 0x20..0x7e }
}
