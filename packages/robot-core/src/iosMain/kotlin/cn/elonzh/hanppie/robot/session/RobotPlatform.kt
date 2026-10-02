@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.elonzh.hanppie.robot.session

import kotlinx.cinterop.*
import cn.elonzh.hanppie.robot.native.hanppie_md5
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDate
import platform.Foundation.NSLocale
import platform.Foundation.NSRecursiveLock
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.usleep

internal actual class RobotLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(action: () -> T): T {
        lock.lock()
        try { return action() } finally { lock.unlock() }
    }
}
internal actual fun robotSleep(millis: Long) { usleep((millis * 1000).toUInt()) }
internal actual fun robotMd5(bytes: ByteArray): ByteArray = ByteArray(16).also { output ->
    output.usePinned { digest ->
        if (bytes.isEmpty()) hanppie_md5(null, 0u, digest.addressOf(0).reinterpret())
        else bytes.usePinned { input -> hanppie_md5(input.addressOf(0), bytes.size.toUInt(), digest.addressOf(0).reinterpret()) }
    }
}
internal actual fun robotRandomBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
    if (size > 0) bytes.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) == 0) }
}
internal actual fun robotDate(): String = NSDateFormatter().apply {
    locale = NSLocale("en_US_POSIX")
    dateFormat = "yyyy/MM/dd"
}.stringFromDate(NSDate())
