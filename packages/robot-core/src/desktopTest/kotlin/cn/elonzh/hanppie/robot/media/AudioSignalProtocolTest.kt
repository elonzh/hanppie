package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AudioSignalProtocolTest {
    @Test fun nativeMicrophoneQueryUsesTheCameraNodeAndTheNativeBooleanConversion(): Unit = runBlocking {
        var response = "0000".hexBytes()
        val audio = SpeakerCommands({ receiver, set, id, payload ->
            assertEquals(1, receiver); assertEquals(0x3f, set); assertEquals(0x1f, id); assertTrue(payload.isEmpty())
            response
        }) { 1 }
        assertFalse(audio.readSoundEnabled())
        for (value in listOf("0001", "0002", "00ff", "000100")) { response = value.hexBytes(); assertTrue(audio.readSoundEnabled()) }
        response = "000059c6".hexBytes(); assertFalse(audio.readSoundEnabled())
        for (invalid in listOf("", "00")) {
            response = invalid.hexBytes()
            assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { audio.readSoundEnabled() }.problem)
        }
    }

    @Test fun feedbackSuppressionUsesNativeConfigurationValuesAndNeverInventsAGetter(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        var failure: RequestProblem? = null
        val audio = SpeakerCommands({ receiver, set, id, payload ->
            assertEquals(1, receiver); assertEquals(0x3f, set); assertEquals(0xe1, id)
            sent += payload.hex()
            failure?.let { throw RobotRequestException(it) }
            byteArrayOf(0)
        }) { 1 }
        audio.setAntiFeedback(true); audio.setAntiFeedback(false)
        assertEquals(listOf("010c0101", "010c0102"), sent)
        for (problem in listOf(RequestProblem.REJECTED, RequestProblem.TIMED_OUT)) {
            failure = problem
            assertEquals(problem, assertFailsWith<RobotRequestException> { audio.setAntiFeedback(true) }.problem)
        }
        assertNull(audio.playback.value)
        audio.close()
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { audio.setAntiFeedback(false) }.problem)
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { audio.readSoundEnabled() }.problem)
        assertEquals(4, sent.size)
    }

    @Test fun playbackPushFiltersAllFrameIdentityFieldsAndPreservesTaskAndUnknownProgress(): Unit = runBlocking {
        val audio = SpeakerCommands({ _, _, _, _ -> error("A push does not query or change the robot") }) { 1 }
        val valid = playbackFrame("0b230000000100")
        for (invalid in listOf(valid.copy(valid = false), valid.copy(sender = 1), valid.copy(receiver = 9),
            valid.copy(attr = 0xc0), valid.copy(set = 2), valid.copy(id = 0xb3), valid.copy(payload = ByteArray(6)))) {
            audio.receive(invalid); assertNull(audio.playback.value)
        }
        audio.receive(valid)
        assertEquals(RobotSpeakerPlayback(11, 65536, 35, 0), audio.playback.value)
        assertEquals(35, audio.playback.value?.percent); assertEquals(true, audio.playback.value?.playing)
        // This is the latest device task, without claiming ownership by a host recording.
        audio.receive(playbackFrame("0cff0504030201"))
        assertEquals(0x01020304, audio.playback.value?.soundId)
        assertEquals(12, audio.playback.value?.taskId)
        assertEquals(false, audio.playback.value?.playing); assertNull(audio.playback.value?.percent)
        assertEquals(5, audio.playback.value?.rawStatus)
    }

    @Test fun playbackExpiresAndCloseRejectsLateTaskPushes(): Unit = runBlocking {
        var clock = 1L
        val audio = SpeakerCommands({ _, _, _, _ -> byteArrayOf(0) }) { clock }
        audio.receive(playbackFrame("0b640100000100"))
        assertEquals(false, audio.playback.value?.playing)
        clock += 1499; audio.expire(); assertNotNull(audio.playback.value)
        clock++; audio.expire(); assertNull(audio.playback.value)
        audio.receive(playbackFrame("0b010000000100")); assertNotNull(audio.playback.value)
        audio.close(); audio.receive(playbackFrame("0b020000000100")); assertNull(audio.playback.value)
    }
}

private fun playbackFrame(payload: String) = DussFrame(0, 9, 2, 1, 0, 0x3f, 0xb4, payload.hexBytes(), true)
