package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class CameraCaptureProtocolTest {
    private fun frame(mode: Int = 0, recording: Boolean = false, code: Int = 0, inserted: Boolean = true, shooting: Boolean = false) =
        DussFrame(0, 1, 2, 1, 0, 2, 0xe4, ByteArray(26).apply {
            this[2] = (code or if (inserted) 0x10 else 0).toByte()
            put16(5, mode or 0xc0 or if (recording) 0x1000 else 0)
            this[7] = if (shooting) 0x10 else 0
            put16(8, 100)
        }, true)

    @Test fun switchesModeBeforeCaptureAndUsesNativePhotoStartStopPayloads(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        var mode = 1
        lateinit var camera: CameraCommands
        camera = CameraCommands({ receiver, set, id, p ->
            assertEquals(1, receiver); assertEquals(2, set)
            sent += "$id:${p.hex()}"
            if (id == 0x10) mode = p.u8(0)
            camera.receive(frame(mode, recording = id == 2 && p.u8(0) == 1))
            byteArrayOf(0)
        }) { 1 }
        camera.receive(frame(mode))
        camera.takePhoto()
        assertEquals(listOf("16:00", "1:01"), sent)
        camera.setRecording(true)
        assertEquals(1, camera.state.value.workMode)
        assertTrue(assertNotNull(camera.state.value.storage).recording)
        camera.setRecording(false)
        assertFalse(assertNotNull(camera.state.value.storage).recording)
        assertEquals(listOf("16:00", "1:01", "16:01", "2:01", "2:00"), sent)
    }

    @Test fun unavailableStorageNeverStartsCaptureButExplicitStopCanRecoverWithoutStatus(): Unit = runBlocking {
        var clock = 1L
        var sends = 0
        lateinit var camera: CameraCommands
        camera = CameraCommands({ _, _, id, p ->
            assertEquals(2, id); assertContentEquals(byteArrayOf(0), p); sends++
            camera.receive(frame(inserted = false)); byteArrayOf(0)
        }) { clock }
        assertFailsWith<IllegalStateException> { camera.takePhoto() }
        for (invalid in listOf(frame(recording = true), frame(shooting = true), frame(code = 5), frame(code = 8),
            frame(code = 2), frame(inserted = false))) {
            camera.receive(invalid)
            assertFailsWith<IllegalStateException> { camera.takePhoto() }
            assertFailsWith<IllegalStateException> { camera.setRecording(true) }
        }
        camera.receive(frame()); clock += 1500
        assertFailsWith<IllegalStateException> { camera.takePhoto() }
        camera.expire(); assertNull(camera.state.value.storage)
        assertEquals(0, sends)
        camera.setRecording(false)
        assertEquals(1, sends)
    }

    @Test fun modeAckAloneNeverTriggersCaptureAndCardChangesAreCheckedAgain(): Unit = runBlocking {
        val sent = mutableListOf<Int>()
        val missing = CameraCommands({ _, _, id, _ -> sent += id; byteArrayOf(0) }) { 1 }
        missing.receive(frame(mode = 1))
        assertEquals(RequestProblem.NOT_APPLIED, assertFailsWith<RobotRequestException> { missing.takePhoto() }.problem)
        assertEquals(listOf(0x10), sent)
        sent.clear()
        lateinit var changing: CameraCommands
        changing = CameraCommands({ _, _, id, _ ->
            sent += id; changing.receive(frame(mode = 0, code = 5)); byteArrayOf(0)
        }) { 1 }
        changing.receive(frame(mode = 1))
        assertFailsWith<IllegalStateException> { changing.takePhoto() }
        assertEquals(listOf(0x10), sent)
    }

    @Test fun recordingAckNeedsANewMatchingPushAndClosingTerminatesTheWait(): Unit = runBlocking {
        val noPush = CameraCommands({ _, _, _, _ -> byteArrayOf(0) }) { 1 }
        noPush.receive(frame(mode = 1))
        assertEquals(RequestProblem.NOT_APPLIED, assertFailsWith<RobotRequestException> { noPush.setRecording(true) }.problem)
        assertEquals(RequestProblem.NOT_APPLIED, assertFailsWith<RobotRequestException> { noPush.setRecording(false) }.problem)
        val pending = async { runCatching { noPush.setRecording(true) } }; yield()
        noPush.close()
        assertEquals(RequestProblem.DISCONNECTED, (pending.await().exceptionOrNull() as RobotRequestException).problem)
        noPush.receive(frame(mode = 1, recording = true))
        assertNull(noPush.state.value.workMode); assertNull(noPush.state.value.storage)
    }

    @Test fun storageTasksShareOneLockAndFailuresNeverReplayOrLeakIt(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var failure: RequestProblem? = null
        var sends = 0
        lateinit var camera: CameraCommands
        camera = CameraCommands({ _, _, _, _ ->
            sends++; gate.await()
            failure?.let { throw RobotRequestException(it) }
            camera.receive(frame()); byteArrayOf(0)
        }) { 1 }
        camera.receive(frame())
        val photo = async { camera.takePhoto() }; yield()
        assertFailsWith<IllegalStateException> { camera.formatStorage() }
        assertFailsWith<IllegalStateException> { camera.setRecording(false) }
        gate.complete(Unit); photo.await()
        for (problem in listOf(RequestProblem.TIMED_OUT, RequestProblem.REJECTED)) {
            failure = problem
            assertEquals(problem, assertFailsWith<RobotRequestException> { camera.takePhoto() }.problem)
        }
        assertEquals(3, sends)
        failure = null; camera.formatStorage(); assertEquals(4, sends)
    }
}
