package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.telemetry.GimbalTelemetry
import kotlin.test.*

class ConsoleStateTest {
    @Test fun disconnectedNeverEnablesDeviceWrites() {
        val state = ConsoleState()
        assertFalse(state.canRun("pass"))
        assertFalse(state.canStop)
    }

    @Test fun runButtonIsTheExplicitActionAndRequiresNonBlankSource() {
        val state = ConsoleState(connected = true)
        assertTrue(state.canRun("pass"))
        assertFalse(state.canRun(""))
        assertFalse(state.copy(busy = true).canRun("pass"))
    }

    @Test fun activeOrUnknownRunAllowsStopButNotAnotherRun() {
        val state = ConsoleState(connected = true, scriptRunPhase = ScriptRunPhase.RUNNING)
        assertFalse(state.canRun("pass"))
        assertTrue(state.canStop)
        assertTrue(state.copy(scriptRunPhase = ScriptRunPhase.COMPLETED).canRun("pass"))
        assertFalse(state.copy(scriptRunPhase = ScriptRunPhase.COMPLETING).canStop)
    }

    @Test fun lossInvalidatesStaleTelemetryAndRunPermission() {
        val state = ConsoleState(connected = true, battery = 90, signalQuality = 36, values = listOf("raw" to "1"),
            gimbal = GimbalTelemetry(0.0,0.0,10.0,20.0,0),
            scriptRunPhase = ScriptRunPhase.RUNNING).lost("timeout")
        assertFalse(state.connected)
        assertNull(state.battery)
        assertNull(state.signalQuality)
        assertTrue(state.values.isEmpty())
        assertNull(state.gimbal)
        assertEquals(ScriptRunPhase.UNKNOWN, state.scriptRunPhase)
        assertFalse(state.canRun("pass"))
        assertFalse(state.canStop)
        assertEquals("timeout", state.error)
    }
}
