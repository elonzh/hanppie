package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.protocol.hex
import kotlin.test.*

class RobotMd5Test {
    @Test fun incrementalDigestKeepsTheExistingEmptyAndByteArrayContract() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", robotMd5(byteArrayOf()).hex())
        val bytes = "abc".encodeToByteArray()
        assertEquals("900150983cd24fb0d6963f7d28e17f72", robotMd5(bytes).hex())
        RobotMd5().use {
            it.update(byteArrayOf(), 0); it.update("a-extra".encodeToByteArray(), 1); it.update("bc".encodeToByteArray(), 2)
            assertEquals(robotMd5(bytes).hex(), it.digest().hex())
            assertFailsWith<IllegalStateException> { it.digest() }
            assertFailsWith<IllegalStateException> { it.update(bytes, 1) }
        }
    }
    @Test fun invalidSlicesAndExplicitCloseCannotSilentlyHashDifferentBytes() {
        RobotMd5().use {
            assertFailsWith<IllegalArgumentException> { it.update(byteArrayOf(1), 2) }
            assertFailsWith<IllegalArgumentException> { it.update(byteArrayOf(1), -1) }
            it.close(); it.close()
            assertFailsWith<IllegalStateException> { it.update(byteArrayOf(), 0) }
        }
    }
}
