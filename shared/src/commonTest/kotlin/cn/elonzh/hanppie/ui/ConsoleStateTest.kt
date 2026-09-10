package cn.elonzh.hanppie.ui

import kotlin.test.*
import cn.elonzh.hanppie.robot.GimbalTelemetry

class ConsoleStateTest {
    @Test fun disconnectedNeverEnablesDeviceWrites() {
        val state = ConsoleState(uploadedSource = "pass")
        assertFalse(state.canUpload("pass"))
        assertFalse(state.canStart("pass", true))
        assertFalse(state.canStop)
    }

    @Test fun startRequiresArmAndExactUploadedSource() {
        val state = ConsoleState(connected = true, uploadedSource = "pass")
        assertTrue(state.canStart("pass", true))
        assertFalse(state.canStart("pass", false))
        assertFalse(state.canStart("pass\n", true))
        assertFalse(state.copy(uploadedSource = null).canStart("pass", true))
        assertFalse(state.copy(busy = true).canStart("pass", true))
    }

    @Test fun uncertainStartAllowsStopButNotReplayOrOverwrite() {
        val state = ConsoleState(connected = true, uploadedSource = "pass", executionUncertain = true)
        assertFalse(state.canStart("pass", true))
        assertFalse(state.canUpload("new source"))
        assertTrue(state.canStop)
        assertTrue(state.copy(executionUncertain = false).canUpload("new source"))
    }

    @Test fun lossInvalidatesUploadAndStaleTelemetry() {
        val state = ConsoleState(connected = true, battery = 90, signalQuality = 36, values = listOf("raw" to "1"),
            gimbal = GimbalTelemetry(0.0,0.0,10.0,20.0,0),
            uploadedSource = "pass", executionUncertain = true).lost("timeout")
        assertFalse(state.connected)
        assertNull(state.battery)
        assertNull(state.signalQuality)
        assertTrue(state.values.isEmpty())
        assertNull(state.gimbal)
        assertNull(state.uploadedSource)
        assertTrue(state.executionUncertain)
        assertFalse(state.canStart("pass", true))
        assertFalse(state.canStop)
        assertEquals("timeout", state.error)
    }
}
