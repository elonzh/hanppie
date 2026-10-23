package cn.elonzh.hanppie.robot.session

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter

actual class RobotLock actual constructor() {
    private val lock = Any()
    actual fun <T> withLock(action: () -> T): T = synchronized(lock, action)
}
internal actual fun robotSleep(millis: Long) = Thread.sleep(millis)
internal actual class RobotMd5 actual constructor() : AutoCloseable {
    private val md5 = MessageDigest.getInstance("MD5")
    private var closed = false
    actual fun update(bytes: ByteArray, count: Int) {
        check(!closed); require(count in 0..bytes.size)
        md5.update(bytes, 0, count)
    }
    actual fun digest(): ByteArray { check(!closed); return md5.digest().also { close() } }
    actual override fun close() { closed = true }
}
internal actual fun robotRandomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)
internal actual fun robotDate(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
