package cn.elonzh.hanppie.ui.robot.remote.input

import cn.elonzh.hanppie.robot.gamepad.*
import cn.elonzh.hanppie.ui.settings.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

class GamepadDriveInputTest {
    private class Fixture {
        var now = 0L; var revision = 0L; var number = 1L
        var report = GamepadReport(true, true, 88, 0xd8, 1024, 1024, 1024, 1024, 0)
        var current = GamepadState(connection = 1, reportNumber = 1, phase = GamepadPhase.CONNECTED, report = report, reportAgeMillis = 0)
        var freshEvent = true
        var primary = 0; var secondary = 0
        val input = GamepadDriveInput({ current }, { freshEvent }, { now }, { revision }, { primary++ }, { secondary++ })
        fun update(value: GamepadReport = report, emit: Boolean = true) {
            report = value; current = current.copy(report = value, reportNumber = ++number)
            if (emit) input.event(GamepadButtonEvent(current.connection, number, now, report), GamepadInputSettings(), true)
        }
        fun frame(settings: GamepadInputSettings = GamepadInputSettings(), allowed: Boolean = true) = input.frame(settings, allowed)
    }
    @Test fun fixedNativeResponseVectorsAndHostBounds() {
        val linear = GamepadAxisCurve()
        assertEquals(0.0, linear.apply(1024)); assertEquals(.5, linear.apply(1354)); assertEquals(-.5, linear.apply(694))
        assertEquals(.25, GamepadAxisCurve(exponent = 2.0).apply(1354))
        assertEquals(-.25, GamepadAxisCurve(exponent = 2.0).apply(694))
        assertEquals(0.0, GamepadAxisCurve(deadZone = 1.0).apply(1354))
        assertEquals(.5, GamepadAxisCurve(gain = 2.0, exponent = 2.0, deadZone = 1.0).apply(1519))
        assertEquals(1.0, GamepadAxisCurve(gain = 2.0).apply(32767))
        assertEquals(-1.0, GamepadAxisCurve(gain = 2.0).apply(-32768))
        assertEquals(.5, GamepadInputSettings(lateralGain = .5).lateral(1684))
        assertEquals(1.0, GamepadInputSettings(forwardGain = 2.0).forward(1354))
    }
    @Test fun responsePersistenceHasEightParametersAndNeverPersistsActivity() {
        val response = GamepadInputSettings(.5, 1.75, GamepadAxisCurve(1.2, 2.5, .25), GamepadAxisCurve(.8, .5, .75))
        val control = ControlSettings(gamepadInput = response)
        val text = settingsJson.encodeToString(SavedSettings(control = control))
        assertEquals(control, settingsJson.decodeFromString<SavedSettings>(text).control)
        val preferences = settingsJson.encodeToString(response)
        assertFalse(preferences.contains("active")); assertFalse(preferences.contains("connection"))
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 2.01)) {
            assertFailsWith<IllegalArgumentException> { GamepadInputSettings(lateralGain = bad) }
            assertFailsWith<IllegalArgumentException> { GamepadAxisCurve(gain = bad) }
        }
        assertFailsWith<IllegalArgumentException> { GamepadAxisCurve(exponent = 0.0) }
        assertFailsWith<IllegalArgumentException> { GamepadAxisCurve(deadZone = 1.1) }
    }
    @Test fun startRequiresCurrentCenteredReleasedInput() {
        val f = Fixture(); f.current = f.current.copy(reportAgeMillis = 251); f.input.start(GamepadInputSettings())
        assertEquals(GamepadDriveProblem.NO_DATA, f.input.state.value.problem)
        f.current = f.current.copy(reportAgeMillis = 0, report = f.report.copy(forward = 1400)); f.input.start(GamepadInputSettings())
        assertEquals(GamepadDriveProblem.RECENTER, f.input.state.value.problem)
        f.current = f.current.copy(report = f.report.copy(buttons = 0x2000)); f.input.start(GamepadInputSettings())
        assertEquals(GamepadDriveProblem.RECENTER, f.input.state.value.problem)
        f.current = f.current.copy(report = f.report.copy(buttons = 1)); f.input.start(GamepadInputSettings())
        assertTrue(f.input.state.value.active, "Unknown reserved bits must not be invented as controls")
    }
    @Test fun groupFlagsGateAllFourAxesAndOutputIsNormalized() {
        val f = Fixture(); f.input.start(GamepadInputSettings())
        f.update(f.report.copy(forward = 1684, lateral = 364, yaw = 1354, pitch = 694))
        assertEquals(GamepadDriveFrame(1.0, -1.0, -.5, .5, true), f.frame())
        f.update(f.report.copy(rawFlags = 0x18))
        assertEquals(GamepadDriveFrame(), f.frame())
    }
    @Test fun shortPressEdgesDoNotDisappearBetweenDriveFramesOrRepeatWithoutHold() {
        val f = Fixture(); f.input.start(GamepadInputSettings())
        f.update(f.report.copy(buttons = 0x8000)); f.update(f.report.copy(buttons = 0))
        assertEquals(1, f.primary); assertEquals(GamepadDriveFrame(0.0, 0.0, 0.0, 0.0, true), f.frame())
        f.now = 500; f.frame(); assertFalse(f.input.state.value.repeatPrimary); assertEquals(1, f.primary)
    }
    @Test fun holdUsesExistingInitialPressThenRepeatAndReleaseContract() {
        val f = Fixture(); f.input.start(GamepadInputSettings()); f.update(f.report.copy(buttons = 0x80))
        f.now = 249; f.frame(); assertFalse(f.input.state.value.repeatPrimary)
        f.now = 250; f.frame(); assertTrue(f.input.state.value.repeatPrimary); assertEquals(1, f.primary)
        f.update(f.report.copy(buttons = 0)); assertFalse(f.input.state.value.repeatPrimary)
        f.update(f.report.copy(buttons = 0x40)); f.frame(); assertEquals(1, f.secondary)
        f.update(); assertEquals(1, f.secondary)
        f.update(f.report.copy(buttons = 0)); f.update(f.report.copy(buttons = 0x40)); assertEquals(2, f.secondary)
    }
    @Test fun unmountedMouseAndSceneSpecificButtonsHaveNoInventedActions() {
        val f = Fixture(); f.input.start(GamepadInputSettings())
        f.update(f.report.copy(mouseConnected = false, buttons = 0xf0c0 and 0x7fff)); f.frame()
        assertEquals(0, f.primary); assertEquals(0, f.secondary)
        f.update(f.report.copy(buttons = 0x8000)); assertEquals(1, f.primary)
    }
    @Test fun queuedOldDuplicateForeignAndExpiredEventsCannotFire() {
        val f = Fixture(); f.input.start(GamepadInputSettings())
        val report = f.report.copy(buttons = 0x8000)
        f.input.event(GamepadButtonEvent(1, 1, 0, report), GamepadInputSettings(), true)
        f.input.event(GamepadButtonEvent(2, 2, 0, report), GamepadInputSettings(), true)
        f.freshEvent = false; f.input.event(GamepadButtonEvent(1, 2, 0, report), GamepadInputSettings(), true)
        assertEquals(0, f.primary)
        f.freshEvent = true; f.input.event(GamepadButtonEvent(1, 2, 0, report), GamepadInputSettings(), true)
        f.input.event(GamepadButtonEvent(1, 2, 0, report), GamepadInputSettings(), true)
        assertEquals(1, f.primary)
    }
    @Test fun staleAndLostLinksRetireOwnershipWithoutAutomaticResume() {
        val f = Fixture(); f.input.start(GamepadInputSettings()); f.update(f.report.copy(forward = 1684, buttons = 0x8000))
        f.current = f.current.copy(reportAgeMillis = 251); assertEquals(GamepadDriveFrame(), f.frame())
        assertEquals(GamepadDriveProblem.STALE, f.input.state.value.problem)
        f.current = f.current.copy(reportAgeMillis = 0); f.update(f.report.copy(buttons = 0)); f.frame()
        assertFalse(f.input.state.value.active); assertEquals(1, f.primary)
        f.update(f.report.copy(forward = 1024)); f.input.start(GamepadInputSettings())
        f.current = f.current.copy(phase = GamepadPhase.DISCONNECTED); f.frame()
        assertFalse(f.input.state.value.active)
    }
    @Test fun availabilityAndRetirementUseTheSameExactSnapshot() {
        val f = Fixture(); f.frame()
        assertTrue(f.input.state.value.fresh); assertTrue(f.input.state.value.gimbalActive)
        f.input.start(GamepadInputSettings())
        f.current = f.current.copy(reportAgeMillis = 251); f.frame()
        assertFalse(f.input.state.value.fresh); assertFalse(f.input.state.value.active)
        assertEquals(GamepadDriveProblem.STALE, f.input.state.value.problem)
        f.frame(); assertFalse(f.input.state.value.fresh)
        f.current = f.current.copy(reportAgeMillis = 0, reportNumber = 2); f.frame()
        assertTrue(f.input.state.value.fresh); assertFalse(f.input.state.value.active)
        assertEquals(GamepadDriveProblem.STALE, f.input.state.value.problem)
    }

    @Test fun ownerConnectionEligibilityAndSettingsChangesRetireWithoutDispatch() {
        for (change in 0..3) {
            val f = Fixture(); f.input.start(GamepadInputSettings())
            if (change == 0) f.revision++
            if (change == 1) f.current = f.current.copy(connection = 2)
            val result = f.frame(if (change == 3) GamepadInputSettings(forwardGain = .5) else GamepadInputSettings(), change != 2)
            assertEquals(GamepadDriveFrame(), result); assertFalse(f.input.state.value.active)
            f.update(f.report.copy(buttons = 0x8000)); assertEquals(0, f.primary)
        }
    }
}
