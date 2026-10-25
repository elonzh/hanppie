package cn.elonzh.hanppie.robot.protocol

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

    @Test fun anInvalidOuterHeaderCandidateCannotHideTheRealFrame() {
        // S1 video download: outer checksum 0x55 at offset 7 falsely claimed a 64-byte frame.
        val frame = Protocol.duss(1, 2, 0, 0, 0x27, ByteArray(80), 123)
        for (validHeader in listOf(false, true)) {
            val prefix = ByteArray(20).apply {
                this[7] = 0x55; this[8] = 64; this[9] = 4
                this[10] = if (validHeader) Protocol.crc(copyOfRange(7, 10), Protocol.CRC8_INIT, Protocol.CRC8_POLY).toByte() else 0
            }
            val decoded = Protocol.frames(prefix + frame)
            assertTrue(decoded.any { !it.valid && it.offset == 7 })
            val valid = decoded.filter { it.valid }.single()
            assertEquals(20, valid.offset)
            assertEquals(0x27, valid.id)
            assertEquals(123, valid.sequence)
            assertContentEquals(ByteArray(80), valid.payload)
        }
    }

    @Test fun aValidPayloadContainingAnotherFrameIsNotRescanned() {
        val inner = Protocol.duss(9, 2, 0, 0x3f, 0x70, sequence = 5)
        val outer = Protocol.duss(1, 2, 0, 0, 0x27, inner, 6)
        val decoded = Protocol.frames(outer).single()
        assertTrue(decoded.valid)
        assertEquals(0x27, decoded.id)
        assertContentEquals(inner, decoded.payload)
    }

    private fun reliablePacket(tick: Int, base: Int = 8, session: Int = 0x1234): ByteArray =
        AppEnvelope(session, base).direct(Protocol.duss(9, 2, 0, 0, 0x70, sequence = 0)).apply {
            put16(4, tick); this[6] = 3; put16(8, base); put16(10, tick)
            this[7] = take(7).fold(0) { sum, byte -> sum xor (byte.toInt() and 255) }.toByte()
        }

    @Test fun receivedReliableWindowAcknowledgesAllOneHundredNativePackets() {
        val envelope = AppEnvelope(0x1234, 80)
        for (tick in 16..808 step 8) envelope.observe(reliablePacket(tick))
        val ack = envelope.control(byteArrayOf())
        assertEquals(808, ack.u16(16), "The S1 releases the receive window from its cumulative acknowledgement")
        assertEquals(808, ack.u16(18), "The S1 reliable stream stalls at 100 packets without this acknowledgement")
        assertEquals(80, ack.u16(24)); assertEquals(80, ack.u16(26))
        assertEquals(88, envelope.direct(byteArrayOf()).u16(4), "Receiving must not advance the outbound channel")
    }

    @Test fun reliableWindowDoesNotAcknowledgeMissingPacketsOrRegressOnDuplicates() {
        val envelope = AppEnvelope(0x1234, 80)
        envelope.observe(reliablePacket(16))
        envelope.observe(reliablePacket(32))
        assertEquals(16, envelope.control(byteArrayOf()).u16(16))
        assertEquals(16, envelope.control(byteArrayOf()).u16(18))
        envelope.observe(reliablePacket(24))
        assertEquals(32, envelope.control(byteArrayOf()).u16(16))
        assertEquals(32, envelope.control(byteArrayOf()).u16(18))
        envelope.observe(reliablePacket(16)); envelope.observe(reliablePacket(32))
        envelope.observe(reliablePacket(40, session = 0x5678))
        assertEquals(32, envelope.control(byteArrayOf()).u16(18))
    }

    @Test fun reliableTickZeroWrapsWithoutBeingMisreadAsAControlTable() {
        val envelope = AppEnvelope(0x1234, 80)
        val wrapped = reliablePacket(0, base = 65528).apply { put16(24, 1234); put16(26, 5678) }
        envelope.observe(wrapped)
        assertEquals(0, envelope.control(byteArrayOf()).u16(18))
        envelope.observe(reliablePacket(8, base = 65528))
        assertEquals(8, envelope.control(byteArrayOf()).u16(18))
        assertEquals(80, envelope.direct(byteArrayOf()).u16(8), "Payload bytes are not a control-table reference")
    }

    @Test fun aLateControlTableCannotMoveTheReceiveReferenceBackwards() {
        val envelope = AppEnvelope(0x1234, 80)
        for (tick in 16..32 step 8) envelope.observe(reliablePacket(tick))
        fun control(reference: Int) = ByteArray(34).apply { put16(2, 0x1234); this[6] = 1; put16(16, reference) }
        envelope.observe(control(24)); assertEquals(32, envelope.control(byteArrayOf()).u16(16))
        envelope.observe(control(8)); assertEquals(32, envelope.control(byteArrayOf()).u16(16))
        envelope.observe(control(40)); assertEquals(32, envelope.control(byteArrayOf()).u16(16))
        assertEquals(32, envelope.control(byteArrayOf()).u16(18))
    }
}
