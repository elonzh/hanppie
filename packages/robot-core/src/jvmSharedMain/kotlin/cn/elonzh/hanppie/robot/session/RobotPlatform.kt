package cn.elonzh.hanppie.robot.session

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal actual class RobotLock actual constructor() {
    private val lock = Any()
    actual fun <T> withLock(action: () -> T): T = synchronized(lock, action)
}
internal actual fun robotSleep(millis: Long) = Thread.sleep(millis)
internal actual fun robotMd5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)
internal actual fun robotRandomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)
internal actual fun robotDate(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
