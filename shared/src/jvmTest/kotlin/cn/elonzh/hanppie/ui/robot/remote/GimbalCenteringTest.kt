package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.robot.telemetry.GimbalTelemetry
import kotlin.test.*
import org.junit.Test

class GimbalCenteringTest {
    private fun sample(pitch: Double = 10.0, yaw: Double = -30.0) = GimbalTelemetry(0.0, 0.0, yaw, pitch, 0)

    @Test fun convergesWithBoundedSpeedAndStopsAtCenter() {
        val center = GimbalCentering()
        center.start(1_000)
        assertEquals(-30.0 to 60.0, center.velocity(1_010, sample(), 1_000, false))
        assertEquals(-6.0 to 6.0, center.velocity(2_010, sample(2.0, -2.0), 2_000, false))
        assertEquals(0.0 to 0.0, center.velocity(2_110, sample(.5, -.5), 2_100, false))
        assertNull(center.velocity(2_120, sample(), 2_100, false), "Completed centering must not restart after outside movement")
    }

    @Test fun staleFeedbackManualInputTimeoutAndCancellationStayStopped() {
        val center = GimbalCentering()
        center.start(1_000)
        assertNull(center.velocity(1_501, sample(), 1_000, false))
        assertNull(center.velocity(1_600, sample(), 1_600, false))
        center.start(2_000)
        assertNull(center.velocity(2_001, sample(), 2_000, true))
        assertNull(center.velocity(2_100, sample(), 2_100, false))
        center.start(3_000)
        assertNull(center.velocity(8_000, sample(), 8_000, false))
        center.start(9_000); center.cancel()
        assertNull(center.velocity(9_001, sample(), 9_000, false))
        center.start(10_000)
        assertNull(center.velocity(10_001, sample(Double.NaN), 10_000, false))
    }
}
