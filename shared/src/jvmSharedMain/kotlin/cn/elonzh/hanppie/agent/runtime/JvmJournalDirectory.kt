package cn.elonzh.hanppie.agent.runtime

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class JvmJournalDirectory(private val directory: Path) : JournalDirectory {
    init {
        Files.createDirectories(directory)
        locked {
            names().filter { it.endsWith(".lock") && Regex("[0-9a-fA-F-]{36}").matches(it.removeSuffix(".lock")) }
                .forEach(::delete)
        }
    }
    override fun names(): List<String> = Files.list(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.toList()
    }
    override fun read(name: String): ByteArray? = directory.resolve(name).takeIf { Files.exists(it) }?.let(Files::readAllBytes)
    override fun append(name: String, bytes: ByteArray) {
        FileChannel.open(directory.resolve(name), CREATE, WRITE, APPEND).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
    override fun truncate(name: String, size: Long) {
        FileChannel.open(directory.resolve(name), WRITE).use { it.truncate(size); it.force(true) }
    }
    override fun delete(name: String) { Files.deleteIfExists(directory.resolve(name)) }
    override fun <T> locked(action: () -> T): T {
        val lockFile = directory.toRealPath().resolve(".sessions.lock")
        return locks.computeIfAbsent(lockFile) { ReentrantLock() }.withLock {
            FileChannel.open(lockFile, CREATE, WRITE).use { channel -> channel.lock().use { action() } }
        }
    }
    private companion object { val locks = ConcurrentHashMap<Path, ReentrantLock>() }
}
