package cn.elonzh.hanppie.robot.maintenance.firmware

import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.session.RobotMd5
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.Source
import kotlinx.io.readByteArray

/** Payload integrity, not a host-side verification of DJI's signature. */
data class FirmwarePackageInspection(val manifest: FirmwareManifest, val archiveSize: Long, val archiveMd5: String)

object FirmwareArchive {
    /** Inspects a flat, uncompressed TAR without extracting files or buffering firmware images. The caller owns [source]. */
    suspend fun inspect(source: Source, length: Long, onProgress: (Long) -> Unit = {}): FirmwarePackageInspection {
        if (length !in 1024..0xffffffffL || length % 512 != 0L) archiveError(FirmwarePackageProblem.INVALID_ARCHIVE)
        return RobotMd5().use { archiveDigest ->
            val input = ArchiveInput(source, length, archiveDigest, onProgress)
            val entries = mutableMapOf<String, ArchiveEntry>()
            var configuration: ByteArray? = null
            var configurationName: String? = null
            var pendingMetadata = false
            while (true) {
                val header = input.read(512)
                if (header.all { it == 0.toByte() }) {
                    if (pendingMetadata || input.read(512).any { it != 0.toByte() }) archiveError(FirmwarePackageProblem.INVALID_ARCHIVE)
                    while (input.count < length) if (input.read(minOf(65_536L, length - input.count).toInt()).any { it != 0.toByte() })
                        archiveError(FirmwarePackageProblem.INVALID_ARCHIVE)
                    break
                }
                val sum = header.indices.sumOf { if (it in 148..155) 32 else header[it].toInt() and 255 }.toLong()
                if (header.octal(148, 8) != sum) archiveError(FirmwarePackageProblem.INVALID_ARCHIVE)
                if (header.field(257, 6) !in listOf("ustar", "ustar ") || header.field(345, 155).isNotEmpty())
                    archiveError(FirmwarePackageProblem.UNSUPPORTED_ARCHIVE)
                val size = header.octal(124, 12)
                if (size > 0xffffffffL || size > length - input.count) archiveError(FirmwarePackageProblem.SIZE_MISMATCH)
                val type = header[156].toInt()
                if (type == 'x'.code) {
                    if (pendingMetadata || size > 65_536) archiveError(FirmwarePackageProblem.UNSUPPORTED_ARCHIVE)
                    checkTimeMetadata(input.read(size.toInt()))
                    pendingMetadata = true
                } else {
                    if (type != 0 && type != '0'.code) archiveError(FirmwarePackageProblem.UNSUPPORTED_ARCHIVE)
                    val name = header.field(0, 100)
                    if (!firmwareFileName(name) || !name.endsWith(".sig") || name in entries || entries.size >= 1025)
                        archiveError(FirmwarePackageProblem.UNEXPECTED_FILE, name)
                    val isConfiguration = name.endsWith(".cfg.sig")
                    if (isConfiguration && (configurationName != null || size > 1_048_576)) archiveError(FirmwarePackageProblem.INVALID_MANIFEST, name)
                    val contents = if (isConfiguration) Buffer() else null
                    val digest = RobotMd5().use { entryDigest ->
                        var remaining = size
                        while (remaining > 0) {
                            val bytes = input.read(minOf(65_536L, remaining).toInt())
                            entryDigest.update(bytes, bytes.size)
                            contents?.write(bytes)
                            remaining -= bytes.size
                        }
                        entryDigest.digest().hex()
                    }
                    entries[name] = ArchiveEntry(size, digest)
                    if (isConfiguration) { configurationName = name; configuration = checkNotNull(contents).readByteArray() }
                    pendingMetadata = false
                }
                val padding = ((512 - size % 512) % 512).toInt()
                if (padding > 0) input.read(padding)
            }
            if (!source.exhausted()) archiveError(FirmwarePackageProblem.SIZE_MISMATCH)
            val manifest = FirmwareManifest.parse(configuration ?: archiveError(FirmwarePackageProblem.MISSING_FILE))
            if (manifest.signaturePrefixBytes == 0 || configurationName != "${manifest.version}.cfg.sig") archiveError(FirmwarePackageProblem.INVALID_MANIFEST)
            val expected = manifest.modules.mapTo(mutableSetOf()) { it.fileName }.apply { add(checkNotNull(configurationName)) }
            (expected - entries.keys).firstOrNull()?.let { archiveError(FirmwarePackageProblem.MISSING_FILE, it) }
            (entries.keys - expected).firstOrNull()?.let { archiveError(FirmwarePackageProblem.UNEXPECTED_FILE, it) }
            for (module in manifest.modules) {
                val actual = entries.getValue(module.fileName)
                if (module.size == 0L || actual.size != module.size) archiveError(FirmwarePackageProblem.SIZE_MISMATCH, module.fileName)
                if (module.md5.isEmpty() || actual.md5 != module.md5) archiveError(FirmwarePackageProblem.CHECKSUM_MISMATCH, module.fileName)
            }
            currentCoroutineContext().ensureActive()
            FirmwarePackageInspection(manifest, length, archiveDigest.digest().hex())
        }
    }
}

private data class ArchiveEntry(val size: Long, val md5: String)
private class ArchiveInput(val source: Source, val length: Long, val digest: RobotMd5, val onProgress: (Long) -> Unit) {
    var count = 0L; private set
    suspend fun read(size: Int): ByteArray {
        currentCoroutineContext().ensureActive()
        if (size.toLong() > length - count) archiveError(FirmwarePackageProblem.SIZE_MISMATCH)
        val bytes = try { source.readByteArray(size) } catch (_: EOFException) { archiveError(FirmwarePackageProblem.SIZE_MISMATCH) }
        digest.update(bytes, bytes.size); count += bytes.size; onProgress(count)
        return bytes
    }
}
private fun ByteArray.field(start: Int, length: Int): String {
    val end = (start until start + length).firstOrNull { this[it] == 0.toByte() } ?: start + length
    return runCatching { decodeToString(start, end, throwOnInvalidSequence = true) }
        .getOrElse { archiveError(FirmwarePackageProblem.INVALID_ARCHIVE) }
}
private fun ByteArray.octal(start: Int, length: Int): Long {
    val text = field(start, length).trim()
    if (text.isEmpty() || text.any { it !in '0'..'7' }) archiveError(FirmwarePackageProblem.INVALID_ARCHIVE)
    return text.toLongOrNull(8) ?: archiveError(FirmwarePackageProblem.INVALID_ARCHIVE)
}
/** PAX timestamps occur in locally assembled official packages; path/size overrides and sparse formats are not accepted. */
private fun checkTimeMetadata(bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
        val space = (offset until bytes.size).firstOrNull { bytes[it] == 32.toByte() } ?: archiveError(FirmwarePackageProblem.INVALID_ARCHIVE)
        val count = bytes.decodeToString(offset, space).toIntOrNull() ?: archiveError(FirmwarePackageProblem.INVALID_ARCHIVE)
        if (count <= space - offset + 2 || count > bytes.size - offset || bytes[offset + count - 1] != 10.toByte()) archiveError(FirmwarePackageProblem.INVALID_ARCHIVE)
        val record = runCatching { bytes.decodeToString(space + 1, offset + count - 1, throwOnInvalidSequence = true) }
            .getOrElse { archiveError(FirmwarePackageProblem.INVALID_ARCHIVE) }
        if (record.substringBefore('=') !in setOf("mtime", "atime", "ctime") || '=' !in record) archiveError(FirmwarePackageProblem.UNSUPPORTED_ARCHIVE)
        offset += count
    }
}
private fun archiveError(problem: FirmwarePackageProblem, name: String? = null): Nothing = throw FirmwarePackageException(problem, name)
