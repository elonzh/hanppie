@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.elonzh.hanppie.agent.runtime

import kotlinx.cinterop.*
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.posix.*

internal class IosJournalDirectory(private val directory: String) : JournalDirectory {
    init { check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)) }
    override fun names(): List<String> = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null)
        ?.filterIsInstance<String>().orEmpty()
    override fun read(name: String): ByteArray? {
        val fd = open("$directory/$name", O_RDONLY)
        if (fd < 0) { if (errno == ENOENT) return null; error("Cannot read session journal: $errno") }
        try {
            val chunks = mutableListOf<ByteArray>()
            var total = 0
            while (true) {
                val bytes = ByteArray(64 * 1024)
                val count = bytes.usePinned { platform.posix.read(fd, it.addressOf(0), bytes.size.toULong()) }.toInt()
                check(count >= 0) { "Cannot read session journal" }
                if (count == 0) break
                total += count; chunks += bytes.copyOf(count)
            }
            return ByteArray(total).also { result -> var offset = 0; chunks.forEach { it.copyInto(result, offset); offset += it.size } }
        } finally { close(fd) }
    }
    override fun append(name: String, bytes: ByteArray) {
        val fd = open("$directory/$name", O_CREAT or O_WRONLY or O_APPEND, 384)
        check(fd >= 0)
        try {
            bytes.usePinned { pinned ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = write(fd, pinned.addressOf(offset), (bytes.size - offset).toULong()).toInt()
                    check(count > 0) { "Cannot append session journal" }; offset += count
                }
            }
            check(fsync(fd) == 0) { "Cannot persist session journal" }
        } finally { close(fd) }
    }
    override fun truncate(name: String, size: Long) {
        val fd = open("$directory/$name", O_WRONLY); check(fd >= 0)
        try { check(ftruncate(fd, size) == 0); check(fsync(fd) == 0) } finally { close(fd) }
    }
    override fun delete(name: String) {
        check(unlink("$directory/$name") == 0 || errno == ENOENT) { "Cannot delete session journal" }
    }
    override fun <T> locked(action: () -> T): T {
        processLock.lock()
        try {
            val fd = open("$directory/.sessions.lock", O_CREAT or O_WRONLY, 384); check(fd >= 0)
            try {
                check(flock(fd, LOCK_EX) == 0)
                try { return action() } finally { flock(fd, LOCK_UN) }
            } finally { close(fd) }
        } finally { processLock.unlock() }
    }
    private companion object { val processLock = NSRecursiveLock() }
}
