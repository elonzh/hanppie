package cn.elonzh.hanppie.ui.robot.remote.input

import cn.elonzh.hanppie.ui.settings.AimInputSettings
import kotlin.math.PI
import kotlin.test.*

class GimbalAimInputTest {
    private class Gyro : AimGyroscope {
        override var available = true
        var reading: GyroscopeReading? = null
        var running = false
        override fun start() { running = true }
        override fun read() = reading
        override fun stop() { running = false }
    }

    @Test fun gyroscopeUsesScreenAxesQuantizationSensitivityAndLimits() {
        var now = 0L; val gyro = Gyro(); val input = GimbalAimInput(gyro, { now }, { 0 })
        input.startGyroscope(); assertTrue(gyro.running)
        gyro.reading = GyroscopeReading(.5, .25, 0.0, 10, 0, 1); now = 50
        val result = input.frame(AimInputSettings(), 90)
        assertEquals(.25 * 180 / PI, result.pitch, 1e-6)
        assertEquals(.5 * 180 / PI * 1.4, result.yaw, 1e-6)
        gyro.reading = gyro.reading!!.copy(x = 10.0, y = 10.0, stamp = 11); now = 100
        assertEquals(AimVelocity(-30.0, 30.0), input.frame(AimInputSettings(gyroSensitivity = 100, gyroInvertY = true), 30))
        gyro.reading = gyro.reading!!.copy(x = .009, y = -.009, stamp = 12); now = 150
        assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90))
    }

    @Test fun staleNonFiniteAndOrientationChangesStopSamplingUntilExplicitStart() {
        var now = 0L; val gyro = Gyro(); val input = GimbalAimInput(gyro, { now }, { 0 })
        input.startGyroscope(); gyro.reading = GyroscopeReading(.1, .2, 0.0, 10, 0, 3)
        now = 50; input.frame(AimInputSettings(), 90)
        gyro.reading = gyro.reading!!.copy(ageMillis = 251); now = 100
        assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90)); assertFalse(gyro.running)
        gyro.reading = gyro.reading!!.copy(ageMillis = 0, stamp = 11); now = 150
        assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90))
        input.startGyroscope(); now = 200; input.frame(AimInputSettings(), 90)
        gyro.reading = gyro.reading!!.copy(rotation = 1, stamp = 12); now = 250
        assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90))
        assertEquals(AimInputProblem.ORIENTATION_CHANGED, input.state.value.problem)
        input.startGyroscope(); gyro.reading = gyro.reading!!.copy(x = Double.NaN); now = 3500
        assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90)); assertFalse(gyro.running)
    }

    @Test fun stoppedOrClosedInputNeverRestartsSamplingByItself() {
        val gyro = Gyro(); val input = GimbalAimInput(gyro, { 0 }, { 0 })
        input.startGyroscope(); assertTrue(gyro.running)
        input.stop(); assertFalse(gyro.running)
        gyro.reading = GyroscopeReading(.2, .3, 0.0, 1, 0, 1)
        assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90))
        input.startGyroscope(); assertTrue(gyro.running)
        input.close(); input.startGyroscope()
        assertFalse(gyro.running); assertFalse(input.state.value.active)
        assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90))
    }

    @Test fun unsupportedDevicesNeverManufactureReadinessOrInput() {
        val input = GimbalAimInput(NoAimGyroscope, { 0 }, { 0 })
        assertFalse(input.gyroAvailable)
        input.startGyroscope(); assertEquals(AimInputProblem.SENSOR_UNAVAILABLE, input.state.value.problem)
        assertFalse(input.state.value.ready); assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90))
    }

    @Test fun naturalLandscapeAndBothRotatedScreensUseTheCurrentDisplayAxes() {
        val expected = listOf(.2 to -.3, .3 to .2, -.2 to .3, -.3 to -.2)
        expected.forEachIndexed { rotation, axes ->
            val gyro = Gyro(); val input = GimbalAimInput(gyro, { 50 }, { 0 })
            input.startGyroscope(); gyro.reading = GyroscopeReading(.2, .3, 0.0, 1, 0, rotation)
            val result = input.frame(AimInputSettings(), 90)
            assertEquals(axes.first * 180 / PI, result.pitch, 1e-6)
            assertEquals(axes.second * 180 / PI * 1.4, result.yaw, 1e-6)
            input.close()
        }
    }

    @Test fun clearedControlOwnershipRejectsSamplesBeforeUiRecomposition() {
        var revision = 1L
        val gyro = Gyro()
        val input = GimbalAimInput(gyro, { 50 }, { revision })
        input.startGyroscope(); gyro.reading = GyroscopeReading(.2, .3, 0.0, 1, 0, 1)
        revision++
        assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90))
        assertFalse(gyro.running); assertFalse(input.state.value.active)
    }
}
