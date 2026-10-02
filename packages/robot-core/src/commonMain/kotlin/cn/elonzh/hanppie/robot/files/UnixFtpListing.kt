package cn.elonzh.hanppie.robot.files

import cn.elonzh.hanppie.robot.session.RobotFtpEntry

/** Unix LIST format used by the native FTP adapter; other formats fail explicitly. */
internal fun parseUnixEntry(line: String): RobotFtpEntry? {
    if (line.isBlank() || line.startsWith("total ")) return null
    val fields = line.trim().split(Regex("\\s+"), limit = 9)
    require(fields.size == 9 && fields[0].length >= 10) { "Unsupported FTP directory format" }
    val kind = when (fields[0][0]) {
        'd' -> RobotFileKind.DIRECTORY
        '-' -> RobotFileKind.FILE
        'l' -> RobotFileKind.LINK
        else -> RobotFileKind.UNKNOWN
    }
    val name = if (kind == RobotFileKind.LINK) fields[8].substringBefore(" -> ") else fields[8]
    val size = requireNotNull(fields[4].toLongOrNull()) { "Invalid FTP file size" }
    require(size >= 0) { "Invalid FTP file size" }
    return RobotFtpEntry(name, kind, size)
}
