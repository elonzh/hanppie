package cn.elonzh.hanppie.ui.settings

import cn.elonzh.hanppie.ui.robot.remote.DriveSpeed
import kotlinx.serialization.encodeToString
import kotlin.test.*

class InputSensitivityTest {
    @Test fun standardKeepsAllPublishedAxesAndHostLimits(): Unit {
        val defaults = InputSensitivities()
        for (axis in InputAxis.entries) for (screen in listOf(-1.0, -.5, 0.0, .5, 1.0)) for (key in -1..1) {
            assertEquals((screen + key).coerceIn(-1.0, 1.0), defaults.combine(axis, screen, key.toDouble()))
        }
        val custom = InputSensitivities(InputSensitivity(SensitivityPreset.CUSTOM, AxisSensitivity(100, 100, 100, 100)))
        val x = custom.combine(InputAxis.FORWARD, 1.0, 1.0)
        val y = custom.combine(InputAxis.LATERAL, 1.0, 1.0)
        val velocity = DriveSpeed.translation(x, y, 3)
        assertEquals(.65, kotlin.math.hypot(velocity.first, velocity.second), 1e-9)
        assertEquals(1.0, custom.combine(InputAxis.PITCH, 1.0, 1.0))
        assertEquals(InputSensitivities(), settingsJson.decodeFromString<SavedSettings>("{}").control.inputSensitivities)
    }

    @Test fun eachSourceAndAxisHasIndependentResponseAndCustomMemory(): Unit {
        val screen = InputSensitivity(SensitivityPreset.CUSTOM, AxisSensitivity(25, 75, 100, 0))
        val keyboard = InputSensitivity(SensitivityPreset.CUSTOM, AxisSensitivity(0, 50, 25, 100))
        val settings = InputSensitivities(screen, keyboard)
        assertEquals(.5, settings.combine(InputAxis.FORWARD, 1.0, 1.0))
        assertEquals(.25, settings.combine(InputAxis.LATERAL, .5, -.5))
        assertEquals(.5, settings.combine(InputAxis.YAW, 0.0, 1.0))
        assertEquals(0.0, settings.combine(InputAxis.PITCH, .5, 0.0))
        assertEquals(screen.custom, screen.copy(preset = SensitivityPreset.STANDARD).copy(preset = SensitivityPreset.CUSTOM).custom)
        val saved = SavedSettings(control = ControlSettings(inputSensitivities = settings))
        assertEquals(saved, settingsJson.decodeFromString<SavedSettings>(settingsJson.encodeToString(saved)))
        assertFailsWith<IllegalArgumentException> { AxisSensitivity(-1) }
        assertFailsWith<IllegalArgumentException> { AxisSensitivity(pitch = 101) }
        assertFailsWith<IllegalArgumentException> { settings.combine(InputAxis.YAW, Double.NaN, 0.0) }
    }
}
