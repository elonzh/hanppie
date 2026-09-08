package cn.elonzh.hanppie.robot

import kotlin.test.*

class ProtocolTest {
    @Test fun packetCaptureVector() {
        val frame = Protocol.duss(2, 9, 0x40, 0x3f, 0x57, sequence = 10072)
        assertEquals("550d043302095827403f573c34", frame.hex())
        assertEquals("2180dc68e04605b6d846e0460000000001010000550d043302095827403f573c34",
            AppEnvelope(0x68dc, 0x46d8).direct(frame).hex())
        assertTrue(Protocol.frames(frame).single().valid)
    }
    @Test fun corruptionAndTruncation() {
        val frame = Protocol.duss(2, 9, 0x40, 0x3f, 0x57, sequence = 10072)
        assertTrue(Protocol.frames(frame.copyOf(10)).isEmpty())
        frame[12] = 0
        assertFalse(Protocol.frames(frame).single().valid)
    }
    @Test fun streamOffsetsAndLimits() {
        val frame = Protocol.duss(2, 9, 0, 1, 4, Protocol.neutral, 65535)
        val packet = AppEnvelope(1, 65528).control(frame)
        assertEquals(34, Protocol.frames(packet).single().offset)
        assertEquals(65535, Protocol.frames(packet).single().sequence)
        assertFailsWith<IllegalArgumentException> {
            Protocol.duss(2, 9, 0, 1, 4, ByteArray(1011), 1)
        }
    }
}
