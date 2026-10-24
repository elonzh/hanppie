package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class CameraZoomTransitionTest {
    private fun frame(value: Int) = DussFrame(0, 1, 2, 1, 0, 2, 0xe4,
        ByteArray(26).apply { put16(8, value) }, true)
    private fun now() = System.nanoTime() / 1_000_000

    @Test fun nativeLerpTruncatesAndSnapsStrictlyBelowSixHundredths() {
        assertEquals(150, nextCameraZoom(100, 400, 1f / 60))
        assertEquals(350, nextCameraZoom(400, 100, 1f / 60))
        assertEquals(394, nextCameraZoom(394, 400, 0f))
        assertEquals(400, nextCameraZoom(395, 400, 0f))
        assertEquals(106, nextCameraZoom(106, 100, 0f))
        assertEquals(100, nextCameraZoom(105, 100, 0f))
        assertEquals(400, nextCameraZoom(100, 400, 1f))
        assertEquals(100, nextCameraZoom(400, 100, .1f))
        // The original integer cast can stall above the nominal 60 Hz; the writer enforces 17 ms.
        assertEquals(389, nextCameraZoom(389, 400, 1f / 120))
        for (fps in listOf(30, 60)) for ((start, target) in listOf(100 to 400, 400 to 100)) {
            var value = start
            repeat(120) {
                val next = nextCameraZoom(value, target, 1f / fps)
                assertTrue(next in minOf(value, target)..maxOf(value, target))
                value = next
            }
            assertEquals(target, value)
        }
    }

    @Test fun invalidFormulaInputsAreRejected() {
        assertFailsWith<IllegalArgumentException> { nextCameraZoom(99, 400, .02f) }
        assertFailsWith<IllegalArgumentException> { nextCameraZoom(100, 401, .02f) }
        for (time in listOf(-.1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> { nextCameraZoom(100, 400, time) }
    }

    @Test fun intermediateWritesUseTheNativePayloadAndFinalReportBeforeAckIsAccepted(): Unit = runBlocking {
        val writes = mutableListOf<Int>()
        val finalSent = CompletableDeferred<Unit>(); val ack = CompletableDeferred<Unit>()
        lateinit var camera: CameraCommands
        camera = CameraCommands({ receiver, set, id, body ->
            assertEquals(1, receiver); assertEquals(2, set); assertEquals(0x34, id)
            assertEquals(5, body.size); assertEquals("090000", body.copyOfRange(0, 3).hex())
            val value = body.u16(3); writes += value
            camera.receive(frame(value))
            if (value == 400) { finalSent.complete(Unit); ack.await() }
            byteArrayOf(0)
        }, nowMillis = ::now)
        camera.receive(frame(100))
        val task = async { camera.setZoom(400, smooth = true) }
        withTimeout(5_000) { finalSent.await() }
        assertFalse(task.isCompleted)
        assertTrue(writes.first() in 101..399)
        assertTrue(writes.zipWithNext().all { (a, b) -> b > a })
        assertEquals(400, writes.last())
        ack.complete(Unit); task.await()
        assertEquals(400, camera.state.value.zoomHundredths)
    }

    @Test fun earlierTargetReportsCannotConfirmTheFinalWriteAndReturningToOneIsMonotone(): Unit = runBlocking {
        val writes = mutableListOf<Int>(); val finalSent = CompletableDeferred<Unit>()
        lateinit var camera: CameraCommands
        camera = CameraCommands({ _, _, _, body ->
            val value = body.u16(3); writes += value
            if (value == 100) finalSent.complete(Unit) else camera.receive(frame(100))
            byteArrayOf(0)
        }, nowMillis = ::now)
        camera.receive(frame(400))
        val task = async { camera.setZoom(100, smooth = true) }
        withTimeout(5_000) { finalSent.await() }; yield()
        assertFalse(task.isCompleted, "A matching report before the final send is insufficient")
        assertTrue(writes.first() in 101..399)
        assertTrue(writes.zipWithNext().all { (a, b) -> b < a })
        camera.receive(frame(100)); task.await()
    }

    @Test fun onlyOneWriteIsInFlightAndCancellationNeverDrainsAMissedFrameQueue(): Unit = runBlocking {
        val sent = CompletableDeferred<Unit>(); val ack = CompletableDeferred<Unit>(); val writes = mutableListOf<Int>()
        val camera = CameraCommands({ _, _, _, body ->
            writes += body.u16(3); sent.complete(Unit); ack.await(); byteArrayOf(0)
        }, nowMillis = ::now)
        camera.receive(frame(100))
        val task = async { camera.setZoom(400, smooth = true) }
        withTimeout(5_000) { sent.await() }; delay(80)
        assertEquals(1, writes.size)
        task.cancelAndJoin(); ack.complete(Unit); delay(40)
        assertEquals(1, writes.size)
    }

    @Test fun rejectionStopsTheTransitionWithoutAnEndpointWrite(): Unit = runBlocking {
        var count = 0
        val camera = CameraCommands({ _, _, _, _ -> count++; throw RobotRequestException(RequestProblem.REJECTED) }, nowMillis = ::now)
        camera.receive(frame(100))
        assertEquals(RequestProblem.REJECTED, assertFailsWith<RobotRequestException> { camera.setZoom(400, true) }.problem)
        assertEquals(1, count); assertEquals(100, camera.state.value.zoomHundredths)
    }

    @Test fun staleReportsStopBeforeTheNextWrite(): Unit = runBlocking {
        var offset = 0L; var count = 0
        val camera = CameraCommands({ _, _, _, _ -> count++; offset = 1_600; byteArrayOf(0) }, nowMillis = { now() + offset })
        camera.receive(frame(100))
        assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { camera.setZoom(400, true) }.problem)
        assertEquals(1, count)
    }

    @Test fun closingDuringTheTransitionPreventsFurtherWrites(): Unit = runBlocking {
        var count = 0; lateinit var camera: CameraCommands
        camera = CameraCommands({ _, _, _, _ -> count++; camera.close(); byteArrayOf(0) }, nowMillis = ::now)
        camera.receive(frame(100))
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { camera.setZoom(400, true) }.problem)
        assertEquals(1, count)
    }

    @Test fun smoothModeStillSupportsAnExplicitSameOrIntermediateTarget(): Unit = runBlocking {
        val writes = mutableListOf<Int>(); lateinit var camera: CameraCommands
        camera = CameraCommands({ _, _, _, body ->
            val value = body.u16(3); writes += value; camera.receive(frame(value)); byteArrayOf(0)
        }, nowMillis = ::now)
        camera.receive(frame(100)); camera.setZoom(100, true)
        assertEquals(listOf(100), writes)
        writes.clear(); camera.setZoom(250, true)
        assertTrue(writes.first() in 101..249); assertEquals(250, writes.last())
    }
}
