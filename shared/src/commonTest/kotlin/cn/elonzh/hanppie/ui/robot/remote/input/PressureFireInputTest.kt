package cn.elonzh.hanppie.ui.robot.remote.input

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import cn.elonzh.hanppie.ui.settings.ControlSettings
import cn.elonzh.hanppie.ui.settings.SavedSettings
import cn.elonzh.hanppie.ui.settings.settingsJson
import kotlinx.serialization.encodeToString
import kotlin.test.*

class PressureFireInputTest {
    private fun contact(id: Long = 1, force: Float = 5f, down: Boolean = false, pressed: Boolean = true) =
        PressureContact(id, pressed, down, force)

    @Test fun exactOfficialThresholdIsStrictAndAllFourSensitivitiesAreDistinct() {
        for (sensitivity in 1..4) {
            val input = PressureFireInput(sensitivity)
            input.update(listOf(contact(force = 7f - sensitivity, down = true)), 1)
            assertFalse(input.isHeld(1))
            input.update(listOf(contact(force = 7.01f - sensitivity)), 1)
            assertTrue(input.isHeld(1))
            input.update(listOf(contact(force = 6.99f - sensitivity)), 1)
            assertFalse(input.isHeld(1))
        }
    }

    @Test fun releaseAndCancellationStopAndRetiredContactsCannotRejoinOnAMove() {
        val input = PressureFireInput(3)
        input.update(listOf(contact(down = true)), 1); assertTrue(input.isHeld(1))
        input.update(listOf(contact(pressed = false)), 1); assertFalse(input.isHeld(1))
        input.update(listOf(contact()), 1); assertFalse(input.isHeld(1))
        input.update(listOf(contact(down = true)), 1); assertTrue(input.isHeld(1))
        input.retire(); assertFalse(input.isHeld(1))
        input.update(listOf(contact()), 1); assertFalse(input.isHeld(1))
        input.update(emptyList(), 1)
        input.update(listOf(contact(down = true)), 1); assertTrue(input.isHeld(1))
    }

    @Test fun aNewControlRevisionRejectsHeldContactsButAcceptsANewFinger() {
        val input = PressureFireInput(3)
        input.update(listOf(contact(down = true)), 4)
        assertTrue(input.isHeld(4)); assertFalse(input.isHeld(5))
        input.update(listOf(contact()), 5); assertFalse(input.isHeld(5))
        input.update(listOf(contact(), contact(id = 2, down = true)), 5); assertTrue(input.isHeld(5))
        input.update(listOf(contact()), 5); assertFalse(input.isHeld(5))
    }

    @Test fun anyPressedFingerCanFireRegardlessOfOrderingAndAnotherReleaseDoesNotStopIt() {
        val input = PressureFireInput(3)
        val low = contact(id = 1, force = 1f, down = true)
        val high = contact(id = 2, down = true)
        input.update(listOf(low, high), 1); assertTrue(input.isHeld(1))
        input.update(listOf(high.copy(freshDown = false), low.copy(pressed = false)), 1); assertTrue(input.isHeld(1))
        input.update(emptyList(), 1); assertFalse(input.isHeld(1))
    }

    @Test fun nonFiniteAndDefaultPressureDoNotManufactureShots() {
        val input = PressureFireInput(4)
        for (force in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 0f, 1f)) {
            input.update(listOf(contact(force = force, down = true)), 1)
            assertFalse(input.isHeld(1))
        }
    }

    @Test fun composeAdapterPreservesRawForceAndContactIdentityWithoutConsumingTheEvent() {
        fun change(type: PointerType, previous: Boolean = false) = PointerInputChange(
            id = PointerId(42), uptimeMillis = 100, position = Offset.Zero, pressed = true, pressure = 5.5f,
            previousUptimeMillis = 90, previousPosition = Offset.Zero, previousPressed = previous,
            isInitiallyConsumed = false, type = type)
        val touch = change(PointerType.Touch)
        assertEquals(PressureContact(42, true, true, 5.5f), pressureContact(touch))
        assertFalse(touch.isConsumed)
        assertFalse(pressureContact(change(PointerType.Touch, true))!!.freshDown)
        assertNull(pressureContact(change(PointerType.Mouse)))
        assertNull(pressureContact(change(PointerType.Stylus)))
    }

    @Test fun preferencesDefaultOffAndRoundTripWithoutAnActiveGesture() {
        val old = settingsJson.decodeFromString<SavedSettings>("""{"control":{"gimbalSpeed":45}}""")
        assertFalse(old.control.pressureFire); assertEquals(3, old.control.pressureFireSensitivity)
        val control = old.control.copy(pressureFire = true, pressureFireSensitivity = 4)
        assertEquals(control, settingsJson.decodeFromString<SavedSettings>(
            settingsJson.encodeToString(SavedSettings(control = control))).control)
        assertFalse(PressureFireInput(control.pressureFireSensitivity).isHeld(1))
    }

    @Test fun invalidSensitivityIsRejectedAtTheSettingsAndInputBoundary() {
        for (invalid in listOf(-1, 0, 5, 100)) {
            assertFailsWith<IllegalArgumentException> { ControlSettings(pressureFireSensitivity = invalid) }
            assertFailsWith<IllegalArgumentException> { PressureFireInput(invalid) }
        }
    }
}
