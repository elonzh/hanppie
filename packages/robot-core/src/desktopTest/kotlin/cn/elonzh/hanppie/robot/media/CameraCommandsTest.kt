package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class CameraCommandsTest {
    private fun frame(zoom: Int = 100) = DussFrame(0, 1, 2, 1, 0, 2, 0xe4,
        ByteArray(26).apply { put16(8, zoom) }, true)

    @Test fun validatesNativeStatusFieldsAndExpiresTheLastReading() {
        var clock = 1L
        val camera = CameraCommands({ _, _, _, _ -> byteArrayOf(0) }) { clock }
        for (invalid in listOf(frame().copy(valid = false), frame().copy(sender = 9), frame().copy(receiver = 3),
            frame().copy(attr = 0x80), frame().copy(payload = ByteArray(25)), frame().copy(id = 0x80))) camera.receive(invalid)
        assertNull(camera.state.value.zoomHundredths)
        camera.receive(frame(400)); assertEquals(400, camera.state.value.zoomHundredths)
        clock += 1_499; camera.expire(); assertEquals(400, camera.state.value.zoomHundredths)
        clock++; camera.expire(); assertNull(camera.state.value.zoomHundredths)
        camera.receive(frame(401)); assertNull(camera.state.value.zoomHundredths)
    }

    @Test fun requiresBothWriteAckAndANewMatchingStatus(): Unit = runBlocking {
        var payload = ""
        val camera = CameraCommands({ receiver, set, id, bytes ->
            assertEquals(1, receiver); assertEquals(2, set); assertEquals(0x34, id)
            payload = bytes.hex(); byteArrayOf(0)
        }) { 1 }
        camera.receive(frame())
        val pending = async { camera.setZoom(400) }
        yield()
        assertEquals("0900009001", payload)
        assertFalse(pending.isCompleted)
        camera.receive(frame(150)); yield(); assertFalse(pending.isCompleted)
        camera.receive(frame(400)); pending.await()
        val normal = async { camera.setZoom(100) }; yield()
        assertEquals("0900006400", payload)
        camera.receive(frame()); normal.await()
    }

    @Test fun acceptsStatusBeforeAckButNeverReusesAnOlderStatus(): Unit = runBlocking {
        lateinit var camera: CameraCommands
        camera = CameraCommands({ _, _, _, bytes -> camera.receive(frame(bytes.u16(3))); byteArrayOf(0) }) { 1 }
        camera.receive(frame())
        camera.setZoom(400)
        assertEquals(400, camera.state.value.zoomHundredths)
        val missingPush = CameraCommands({ _, _, _, _ -> byteArrayOf(0) }) { 1 }
        missingPush.receive(frame(400))
        assertEquals(RequestProblem.NOT_APPLIED,
            assertFailsWith<RobotRequestException> { missingPush.setZoom(400) }.problem)
    }

    @Test fun refusesStaleValuesAndStopsWaitingWhenTheSessionCloses(): Unit = runBlocking {
        var requests = 0
        var clock = 1L
        val camera = CameraCommands({ _, _, _, _ -> requests++; byteArrayOf(0) }) { clock }
        assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { camera.setZoom(400) }.problem)
        camera.receive(frame()); clock += 1_500
        assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { camera.setZoom(400) }.problem)
        assertEquals(0, requests)
        clock++; camera.receive(frame())
        val pending = async { runCatching { camera.setZoom(400) } }
        yield(); camera.close()
        assertEquals(RequestProblem.DISCONNECTED, (pending.await().exceptionOrNull() as RobotRequestException).problem)
        assertNull(camera.state.value.zoomHundredths)
        camera.receive(frame(400))
        assertNull(camera.state.value.zoomHundredths)
        assertFailsWith<IllegalArgumentException> { camera.setZoom(401) }
    }
}
