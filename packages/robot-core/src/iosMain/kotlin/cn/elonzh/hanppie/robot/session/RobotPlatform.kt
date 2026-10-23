@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.elonzh.hanppie.robot.session

import kotlinx.cinterop.*
import platform.CoreCrypto.CC_MD5_CTX
import cn.elonzh.hanppie.robot.native.hanppie_md5_init
import cn.elonzh.hanppie.robot.native.hanppie_md5_update
import cn.elonzh.hanppie.robot.native.hanppie_md5_final
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
internal actual class RobotMd5 actual constructor() : AutoCloseable {
    private var context: CPointer<CC_MD5_CTX>? = nativeHeap.alloc<CC_MD5_CTX>().ptr.also {
        if (hanppie_md5_init(it) != 1) { nativeHeap.free(it); error("MD5 initialization failed") }
    }
    actual fun update(bytes: ByteArray, count: Int) {
        val ctx = checkNotNull(context); require(count in 0..bytes.size)
        if (count > 0) bytes.usePinned { check(hanppie_md5_update(ctx, it.addressOf(0), count.toUInt()) == 1) }
    }
    actual fun digest(): ByteArray {
        val ctx = checkNotNull(context)
        try { return ByteArray(16).also { output ->
            output.usePinned { check(hanppie_md5_final(ctx, it.addressOf(0).reinterpret()) == 1) }
        } } finally { close() }
    }
    actual override fun close() { context?.let { nativeHeap.free(it); context = null } }
}
internal actual fun robotRandomBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
    if (size > 0) bytes.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) == 0) }
}
internal actual fun robotDate(): String = NSDateFormatter().apply {
    locale = NSLocale("en_US_POSIX")
    dateFormat = "yyyy/MM/dd"
}.stringFromDate(NSDate())
