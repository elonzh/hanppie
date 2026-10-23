package cn.elonzh.hanppie.ui.robot.remote.input

import cn.elonzh.hanppie.ui.settings.AimInputSettings
import kotlin.math.PI
import kotlin.test.*

class GimbalAimInputTest {
    private class Pointer : RelativePointer {
        lateinit var ready: () -> Unit
        lateinit var motion: (Double, Double) -> Unit
        lateinit var button: (Boolean, Boolean) -> Unit
        lateinit var lost: () -> Unit
        var closed = 0
        override fun capture(onReady: () -> Unit, onMotion: (Double, Double) -> Unit,
            onButton: (Boolean, Boolean) -> Unit, onLost: () -> Unit): AutoCloseable {
            ready = onReady; motion = onMotion; button = onButton; lost = onLost
            return AutoCloseable { closed++ }
        }
    }
    private class Gyro : AimGyroscope {
        override var available = true
        var reading: GyroscopeReading? = null
        var running = false
        override fun start() { running = true }
        override fun read() = reading
        override fun stop() { running = false }
    }

    @Test fun mouseNeedsNativeAcknowledgementAndConsumesEachDeltaOnce() {
        var now = 0L; val pointer = Pointer()
        val input = GimbalAimInput(pointer, NoAimGyroscope, { now }, { 0 })
        input.startMouse(); pointer.motion(10.0, -5.0)
        now = 50; assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90))
        pointer.ready(); pointer.motion(10.0, -5.0)
        now = 100; assertEquals(AimVelocity(20.0, 40.0), input.frame(AimInputSettings(), 90))
        now = 150; assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90))
        pointer.motion(10.0, -5.0)
        now = 200; assertEquals(AimVelocity(-10.0, 20.0), input.frame(AimInputSettings(mouseSensitivity = 25, mouseInvertY = true), 90))
    }

    @Test fun stalledFramesDiscardMotionAndLimitLargeDeltasWithoutAQueue() {
        var now = 0L; val pointer = Pointer(); val input = GimbalAimInput(pointer, NoAimGyroscope, { now }, { 0 })
        input.startMouse(); pointer.ready(); pointer.motion(9999.0, -9999.0)
        now = 50; assertEquals(AimVelocity(30.0, 30.0), input.frame(AimInputSettings(), 30))
        pointer.motion(9999.0, -9999.0); now = 500
        assertEquals(AimVelocity(), input.frame(AimInputSettings(), 30))
        now = 550; assertEquals(AimVelocity(), input.frame(AimInputSettings(), 30))
        pointer.motion(Double.NaN, 1.0); now = 600
        assertEquals(AimVelocity(), input.frame(AimInputSettings(), 30))
    }

    @Test fun stopRetiresCallbacksButtonsAndAcquisitionWithoutAutomaticRestart() {
        var now = 0L; var fired = 0; var zoomed = 0; val pointer = Pointer()
        val input = GimbalAimInput(pointer, NoAimGyroscope, { now }, { 0 }, { fired++ }, { zoomed++ })
        input.startMouse(); pointer.ready(); pointer.button(true, true); pointer.button(true, false)
        assertEquals(1, fired); assertFalse(input.state.value.repeatPrimary)
        pointer.button(false, true); pointer.button(false, true); assertEquals(1, zoomed)
        pointer.button(true, true); now = 300; input.frame(AimInputSettings(), 90)
        assertTrue(input.state.value.repeatPrimary)
        val oldMotion = pointer.motion; val oldReady = pointer.ready; val oldButton = pointer.button
        input.stop(); assertEquals(1, pointer.closed)
        oldReady(); oldMotion(99.0, 99.0); oldButton(true, true)
        now = 350; assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90))
        assertEquals(AimInputMode.OFF, input.state.value.mode); assertEquals(2, fired)
    }

    @Test fun pointerFailureAndTimeoutReleaseTheHandle() {
        var now = 0L; val pointer = Pointer(); val input = GimbalAimInput(pointer, NoAimGyroscope, { now }, { 0 })
        input.startMouse(); now = 3000; input.frame(AimInputSettings(), 90)
        assertEquals(AimInputProblem.POINTER_UNAVAILABLE, input.state.value.problem)
        assertEquals(1, pointer.closed)
        input.startMouse(); pointer.ready(); pointer.lost()
        assertEquals(2, pointer.closed); assertEquals(AimInputMode.OFF, input.state.value.mode)
    }

    @Test fun gyroscopeUsesScreenAxesQuantizationSensitivityAndLimits() {
        var now = 0L; val gyro = Gyro(); val input = GimbalAimInput(null, gyro, { now }, { 0 })
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
        var now = 0L; val gyro = Gyro(); val input = GimbalAimInput(null, gyro, { now }, { 0 })
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

    @Test fun changingInputModesReleasesThePreviousPlatformResource() {
        val pointer = Pointer(); val gyro = Gyro(); val input = GimbalAimInput(pointer, gyro, { 0 }, { 0 })
        input.startMouse(); pointer.ready(); input.startGyroscope()
        assertEquals(1, pointer.closed); assertTrue(gyro.running)
        input.startMouse(); assertFalse(gyro.running)
        input.close(); input.startMouse(); input.startGyroscope()
        assertEquals(2, pointer.closed); assertFalse(gyro.running)
    }

    @Test fun unsupportedDevicesNeverManufactureReadinessOrInput() {
        val input = GimbalAimInput(null, NoAimGyroscope, { 0 }, { 0 })
        assertFalse(input.mouseAvailable); assertFalse(input.gyroAvailable)
        input.startGyroscope(); assertEquals(AimInputProblem.SENSOR_UNAVAILABLE, input.state.value.problem)
        input.startMouse(); assertEquals(AimInputProblem.POINTER_UNAVAILABLE, input.state.value.problem)
        assertFalse(input.state.value.ready); assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90))
    }

    @Test fun naturalLandscapeAndBothRotatedScreensUseTheCurrentDisplayAxes() {
        val expected = listOf(.2 to -.3, .3 to .2, -.2 to .3, -.3 to -.2)
        expected.forEachIndexed { rotation, axes ->
            val gyro = Gyro(); val input = GimbalAimInput(null, gyro, { 50 }, { 0 })
            input.startGyroscope(); gyro.reading = GyroscopeReading(.2, .3, 0.0, 1, 0, rotation)
            val result = input.frame(AimInputSettings(), 90)
            assertEquals(axes.first * 180 / PI, result.pitch, 1e-6)
            assertEquals(axes.second * 180 / PI * 1.4, result.yaw, 1e-6)
            input.close()
        }
    }

    @Test fun clearedControlOwnershipRejectsNativeCallbacksAndFramesBeforeUiRecomposition() {
        var revision = 1L; var fired = 0
        val pointer = Pointer(); val gyro = Gyro()
        val input = GimbalAimInput(pointer, gyro, { 50 }, { revision }, { fired++ })
        input.startMouse(); pointer.ready(); pointer.motion(10.0, 10.0)
        revision++
        pointer.button(true, true)
        assertEquals(0, fired); assertEquals(1, pointer.closed)
        assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90))
        input.startGyroscope(); gyro.reading = GyroscopeReading(.2, .3, 0.0, 1, 0, 1)
        revision++
        assertEquals(AimVelocity(), input.frame(AimInputSettings(), 90)); assertFalse(gyro.running)
    }
}
