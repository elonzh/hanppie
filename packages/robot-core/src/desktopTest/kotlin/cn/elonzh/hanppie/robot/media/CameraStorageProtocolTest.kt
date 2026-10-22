package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class CameraStorageProtocolTest {
    private fun frame(code: Int = 0, inserted: Boolean = true, recording: Boolean = false, shooting: Boolean = false) =
        DussFrame(0, 1, 2, 1, 0, 2, 0xe4, ByteArray(26).apply {
            this[2] = (code or if (inserted) 0x10 else 0).toByte()
            put16(3, 513); put16(5, if (recording) 0x30c0 else 0xc0)
            this[7] = if (shooting) 0x20 else 0
            put16(8, 100)
            for ((offset, value) in listOf(10 to 32768, 14 to 23456, 18 to 76543, 22 to 4567))
                repeat(4) { this[offset + it] = (value ushr (it * 8)).toByte() }
        }, true)

    @Test fun decodesSignedCountersAndIndependentNativeFlags() {
        val camera = CameraCommands({ _, _, _, _ -> byteArrayOf(0) }) { 1 }
        camera.receive(frame(recording = true, shooting = true))
        val status = assertNotNull(camera.state.value.storage)
        assertTrue(status.inserted); assertTrue(status.recording); assertTrue(status.shooting)
        assertEquals(513, status.recordingSeconds)
        assertEquals(32768, status.totalSpaceMb); assertEquals(23456, status.remainingSpaceMb)
        assertEquals(76543, status.availablePhotos); assertEquals(4567, status.remainingRecordingSeconds)
        assertFalse(status.canFormat); assertEquals(true, camera.state.value.timeSynced)
        camera.receive(frame().let { it.copy(payload = it.payload.apply { for (i in 10..25) this[i] = 0xff.toByte() }) })
        val unknown = assertNotNull(camera.state.value.storage)
        assertNull(unknown.totalSpaceMb); assertNull(unknown.remainingSpaceMb)
        assertNull(unknown.availablePhotos); assertNull(unknown.remainingRecordingSeconds)
        for (code in 0..15) {
            camera.receive(frame(code))
            val item = assertNotNull(camera.state.value.storage)
            assertEquals(code in listOf(2, 3, 4, 6, 10, 11), item.hasError)
            assertEquals(code == 5, item.formatting); assertEquals(code == 8, item.full)
        }
    }

    @Test fun onlyFreshInsertedIdleStorageCanSendTheExactFormatRequest(): Unit = runBlocking {
        var clock = 1L
        var requests = 0
        val camera = CameraCommands({ receiver, set, id, payload ->
            assertEquals(1, receiver); assertEquals(2, set); assertEquals(0x72, id)
            assertContentEquals(byteArrayOf(0), payload); requests++; byteArrayOf(0)
        }) { clock }
        assertFailsWith<IllegalStateException> { camera.formatStorage() }
        for (busy in listOf(frame(inserted = false), frame(5), frame(recording = true), frame(shooting = true))) {
            camera.receive(busy)
            assertFailsWith<IllegalStateException> { camera.formatStorage() }
        }
        camera.receive(frame()); clock += 1500
        assertFailsWith<IllegalStateException> { camera.formatStorage() }
        assertEquals(0, requests)
        camera.expire(); assertNull(camera.state.value.storage)
        for (stateCode in listOf(0, 8, 2)) {
            camera.receive(frame(stateCode))
            val before = camera.state.value
            camera.formatStorage()
            assertEquals(before, camera.state.value, "ACK cannot fabricate completion or new capacity")
        }
        assertEquals(3, requests)
        camera.close(); camera.receive(frame())
        assertNull(camera.state.value.storage)
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { camera.formatStorage() }.problem)
        assertEquals(3, requests)
    }

    @Test fun duplicateFormatIsNotQueuedAndFailuresReleaseTheOperation(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        var failure: RequestProblem? = null
        val camera = CameraCommands({ _, _, _, _ ->
            requests++; gate.await()
            failure?.let { throw RobotRequestException(it) }
            byteArrayOf(0)
        }) { 1 }
        camera.receive(frame())
        val first = async { camera.formatStorage() }; yield()
        assertFailsWith<IllegalStateException> { camera.formatStorage() }
        assertEquals(1, requests)
        gate.complete(Unit); first.await()
        for (problem in listOf(RequestProblem.REJECTED, RequestProblem.TIMED_OUT, RequestProblem.INVALID_RESPONSE)) {
            failure = problem
            assertEquals(problem, assertFailsWith<RobotRequestException> { camera.formatStorage() }.problem)
        }
        failure = null; camera.formatStorage(); assertEquals(5, requests)
    }

    @Test fun cancellationDoesNotReplayADestructiveRequest(): Unit = runBlocking {
        var requests = 0
        val gate = CompletableDeferred<Unit>()
        val camera = CameraCommands({ _, _, _, _ -> requests++; gate.await(); byteArrayOf(0) }) { 1 }
        camera.receive(frame())
        val request = launch { camera.formatStorage() }; yield(); request.cancelAndJoin()
        assertEquals(1, requests)
        assertEquals(0, camera.state.value.storage?.stateCode)
        gate.complete(Unit); camera.formatStorage()
        assertEquals(2, requests)
    }
}
