package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CameraSettingsProtocolTest {
    @Test fun readsSignedUnknownAntiFlickerAndVerifiesNativeWrites(): Unit = runBlocking {
        var code = 2
        var malformed = false
        var apply = true
        val camera = CameraCommands({ receiver, set, id, payload ->
            assertEquals(1, receiver); assertEquals(2, set)
            when (id) {
                0x47 -> { assertTrue(payload.isEmpty()); if (malformed) byteArrayOf(0) else byteArrayOf(0, code.toByte()) }
                0x46 -> { assertEquals(1, payload.size); if (apply) code = payload.u8(0); byteArrayOf(0) }
                else -> error("Unexpected camera command $id")
            }
        }) { 1 }
        assertEquals(2, camera.getAntiFlicker())
        for (mode in AntiFlicker.entries) assertEquals(mode.wireValue, camera.setAntiFlicker(mode))
        code = 255; assertEquals(-1, camera.getAntiFlicker()); assertNull(AntiFlicker.fromWire(-1))
        malformed = true
        assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { camera.getAntiFlicker() }.problem)
        malformed = false; apply = false
        assertEquals(RequestProblem.NOT_APPLIED,
            assertFailsWith<RobotRequestException> { camera.setAntiFlicker(AntiFlicker.HZ_50) }.problem)
    }

    @Test fun decodesBinaryYearFromLiveReadbackAndValidatesCalendarFields(): Unit = runBlocking {
        var response = "00ea070a17000102".hexBytes()
        val camera = CameraCommands({ receiver, set, id, payload ->
            assertEquals(1, receiver); assertEquals(2, set); assertEquals(0x55, id); assertTrue(payload.isEmpty())
            response
        }) { 1 }
        assertEquals(CameraDate(2026, 10, 23, 0, 1, 2), camera.getDate())
        response = "00e807021d173b3b0000".hexBytes()
        assertEquals(CameraDate(2024, 2, 29, 23, 59, 59), camera.getDate())
        response = "00bc070101000b1d".hexBytes()
        assertEquals(CameraDate(1980, 1, 1, 0, 11, 29), camera.getDate())
        response = ByteArray(8); assertNull(camera.getDate())
        for (invalid in listOf("00e907021d000000", "00ffff0a17000000", "00ea070a17200000", "00")) {
            response = invalid.hexBytes()
            assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { camera.getDate() }.problem)
        }
    }

    @Test fun writesBinaryYearAndAcceptsElapsedTimeAcrossMidnight(): Unit = runBlocking {
        var clock = 1_000L
        var response = "00eb070101000001".hexBytes()
        val payloads = mutableListOf<String>()
        val expected = CameraDate(2026, 12, 31, 23, 59, 59)
        val camera = CameraCommands(request = { receiver, set, id, payload ->
            assertEquals(1, receiver); assertEquals(2, set)
            when (id) {
                0x54 -> { payloads += payload.hex(); byteArrayOf(0) }
                0x55 -> { clock += 1_200; response }
                else -> error("Unexpected camera command $id")
            }
        }, localNow = { expected }, nowMillis = { clock })
        assertEquals(CameraDate(2027, 1, 1, 0, 0, 1), camera.syncClock())
        assertEquals("ea070c1f173b3b0000", payloads.single())
        response = "00eb070101010000".hexBytes()
        assertEquals(RequestProblem.NOT_APPLIED, assertFailsWith<RobotRequestException> { camera.syncClock() }.problem)
        response = ByteArray(8)
        assertEquals(RequestProblem.NOT_APPLIED, assertFailsWith<RobotRequestException> { camera.syncClock() }.problem)
    }

    @Test fun synchronizationFlagExpiresEvenWhenZoomIsUnknownAndCloseRejectsConfiguration(): Unit = runBlocking {
        var clock = 1L
        val camera = CameraCommands({ _, _, _, _ -> error("No command after close") }) { clock }
        fun status(flag: Int) = DussFrame(0, 1, 2, 1, 0, 2, 0xe4, ByteArray(26).apply { this[5] = flag.toByte() }, true)
        camera.receive(status(0x40)); assertEquals(true, camera.state.value.timeSynced)
        assertNull(camera.state.value.zoomHundredths)
        clock += 1_500; camera.expire(); assertNull(camera.state.value.timeSynced)
        camera.receive(status(0x80)); assertEquals(true, camera.state.value.timeSynced)
        camera.receive(status(0x20)); assertEquals(false, camera.state.value.timeSynced)
        camera.close(); camera.receive(status(0x40)); assertNull(camera.state.value.timeSynced)
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { camera.getAntiFlicker() }.problem)
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { camera.syncClock() }.problem)
    }
}
