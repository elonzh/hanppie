@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.elonzh.hanppie.ui.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** Bulk copies across the Foundation bridge keep the receive loop free of per-byte ObjC calls. */
internal fun ByteArray.toPlatformData(): NSData = if (isEmpty()) NSData() else usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}

internal fun NSData.toByteArray(): ByteArray {
    require(length <= Int.MAX_VALUE.toULong())
    return ByteArray(length.toInt()).also { result ->
        if (result.isNotEmpty()) result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
}
