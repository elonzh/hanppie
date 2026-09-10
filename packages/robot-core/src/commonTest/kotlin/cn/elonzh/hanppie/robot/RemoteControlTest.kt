package cn.elonzh.hanppie.robot

import kotlin.test.*

class RemoteControlTest {
    @Test fun followOnlyWhileAimingOutward() {
        assertEquals(RemoteControl.Follow(0.0,0.0), RemoteControl.follow(null,30.0))
        for (angle in listOf(-240.0,-90.0,0.0,90.0,240.0))
            assertEquals(RemoteControl.Follow(0.0,0.0), RemoteControl.follow(angle,0.0))
        assertEquals(RemoteControl.Follow(0.0,30.0), RemoteControl.follow(60.0,30.0))
        assertEquals(RemoteControl.Follow(30.0,30.0), RemoteControl.follow(75.0,30.0))
        assertEquals(RemoteControl.Follow(60.0,30.0), RemoteControl.follow(90.0,30.0))
        assertEquals(RemoteControl.Follow(-60.0,0.0), RemoteControl.follow(-235.0,-30.0))
        assertEquals(RemoteControl.Follow(0.0,30.0), RemoteControl.follow(-235.0,30.0))
    }
    @Test fun moduleVelocityVectorsMatchSdk() {
        assertContentEquals(ByteArray(12),RemoteControl.velocity(0.0,0.0,0.0))
        assertEquals("0000003f000000bf00007042",RemoteControl.velocity(.5,-.5,60.0).hex())
        assertEquals("58020000a8fddc", RemoteControl.gimbalVelocity(-60.0,60.0).hex())
        assertEquals("000000000000dc", RemoteControl.gimbalVelocity(0.0,0.0).hex())
        assertFailsWith<IllegalArgumentException> { RemoteControl.gimbalVelocity(Double.NaN,0.0) }
        assertFailsWith<IllegalArgumentException> { RemoteControl.velocity(Double.NaN,0.0,0.0) }
    }
    @Test fun muzzleFireLedMatchesDirectRobotPayload() {
        assertEquals("40000000ff0001ffffff6401000100", RemoteControl.muzzleFireLed(true).hex())
        assertEquals("40000000ff0000ffffff6401000100", RemoteControl.muzzleFireLed(false).hex())
        assertEquals("71ffffff0164006400", RemoteControl.blasterLed(true).hex())
        assertEquals("70ffffff0164006400", RemoteControl.blasterLed(false).hex())
    }

    @Test fun speakerUploadPayloadsMatchRecoveredTransport() {
        assertEquals("000000010002002c010000000000000000", SpeakerAudio.start(2, 300).hex())
        assertEquals("00030000000300010203", SpeakerAudio.block(byteArrayOf(1, 2, 3), 3).hex())
        assertEquals("0b040000010001000001", SpeakerAudio.playPayload.hex())
    }
    @Test fun fragmentedAnnexBAndMultiSliceFrames() {
        val parser = AnnexB()
        assertTrue(parser.accept("0000".hexBytes()).isEmpty())
        val nals = parser.accept("00016580000001654000000001618000000109f0".hexBytes())
        assertEquals(3,nals.size)
        val frames = H264AccessUnits()
        assertNull(frames.accept(nals[0]))
        assertNull(frames.accept(nals[1]))
        assertContentEquals(nals[0]+nals[1],frames.accept(nals[2]))
    }
}
