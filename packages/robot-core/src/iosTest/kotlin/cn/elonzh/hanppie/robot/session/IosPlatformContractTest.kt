package cn.elonzh.hanppie.robot.session

import platform.posix.SOCK_DGRAM
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class IosPlatformContractTest {
    @Test fun nativeDigestMatchesTheLabContentAddressContract() {
        assertContentEquals(hex("d41d8cd98f00b204e9800998ecf8427e"), robotMd5(byteArrayOf()))
        assertContentEquals(hex("5d41402abc4b2a76b9719d911017c592"), robotMd5("hello".encodeToByteArray()))
        assertTrue(Regex("\\d{4}/\\d{2}/\\d{2}").matches(robotDate()))
    }
    @Test fun nativeDatagramReceiveIsBoundedAndCloseIsIdempotent() {
        val socket = IosSocket(SOCK_DGRAM)
        try {
            socket.bind("127.0.0.1", 0)
            socket.timeout(20)
            val start = TimeSource.Monotonic.markNow()
            assertNull(socket.receive(1024))
            assertTrue(start.elapsedNow().inWholeMilliseconds < 1_000)
        } finally { socket.close(); socket.close() }
        assertFailsWith<IllegalStateException> { socket.receive(1024) }
    }
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
