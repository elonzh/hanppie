package cn.elonzh.hanppie.ui

import kotlin.test.*

class DriveSpeedTest {
    @Test fun boundedGearsCreepAndDiagonalSpeed() {
        assertEquals(.25, DriveSpeed.translation(1.0,0.0,1,false).first)
        assertEquals(.65, DriveSpeed.translation(1.0,0.0,3,false).first)
        assertEquals(1.0, DriveSpeed.translation(1.0,0.0,5,false).first)
        assertEquals(.1625, DriveSpeed.translation(1.0,0.0,3,true).first)
        val diagonal = DriveSpeed.translation(1.0,1.0,5,false)
        assertEquals(1.0,kotlin.math.hypot(diagonal.first,diagonal.second),1e-9)
        assertEquals(0.0 to 0.0,DriveSpeed.translation(0.0,0.0,3,false))
        assertEquals(30.0, DriveSpeed.rotation(1, false))
        assertEquals(90.0, DriveSpeed.rotation(3, false))
        assertEquals(150.0, DriveSpeed.rotation(5, false))
        assertEquals(37.5, DriveSpeed.rotation(5, true))
    }

    @Test fun configuredGearsAndCreepAreApplied() {
        val settings = ControlSettings(
            translationSpeeds = listOf(.1, .2, .3, .4, .5),
            rotationSpeeds = listOf(20.0, 40.0, 60.0, 80.0, 100.0),
            creepMultiplier = .5,
        )
        assertEquals(.15, DriveSpeed.translation(1.0, 0.0, 3, true, settings).first)
        assertEquals(50.0, DriveSpeed.rotation(5, true, settings))
    }
}
