package cn.elonzh.hanppie.ui

import kotlin.test.*

class DriveSpeedTest {
    @Test fun boundedGearsAndDiagonalSpeed() {
        assertEquals(.25, DriveSpeed.translation(1.0,0.0,1).first)
        assertEquals(.65, DriveSpeed.translation(1.0,0.0,3).first)
        assertEquals(1.0, DriveSpeed.translation(1.0,0.0,5).first)
        val diagonal = DriveSpeed.translation(1.0,1.0,5)
        assertEquals(1.0,kotlin.math.hypot(diagonal.first,diagonal.second),1e-9)
        assertEquals(0.0 to 0.0,DriveSpeed.translation(0.0,0.0,3))
        assertEquals(30.0, DriveSpeed.rotation(1))
        assertEquals(90.0, DriveSpeed.rotation(3))
        assertEquals(150.0, DriveSpeed.rotation(5))
    }

    @Test fun configuredGearsAreApplied() {
        val settings = ControlSettings(
            translationSpeeds = listOf(.1, .2, .3, .4, .5),
            rotationSpeeds = listOf(20.0, 40.0, 60.0, 80.0, 100.0),
        )
        assertEquals(.3, DriveSpeed.translation(1.0, 0.0, 3, settings).first)
        assertEquals(100.0, DriveSpeed.rotation(5, settings))
    }
}
