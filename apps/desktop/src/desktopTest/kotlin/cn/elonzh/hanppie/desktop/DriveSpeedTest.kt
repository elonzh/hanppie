package cn.elonzh.hanppie.desktop

import kotlin.test.*

class DriveSpeedTest {
    @Test fun boundedGearsCreepAndDiagonalSpeed() {
        assertEquals(.15, DriveSpeed.translation(1.0,0.0,1,false).first)
        assertEquals(.3, DriveSpeed.translation(1.0,0.0,2,false).first)
        assertEquals(.6, DriveSpeed.translation(1.0,0.0,3,false).first)
        assertEquals(.075, DriveSpeed.translation(1.0,0.0,2,true).first)
        val diagonal = DriveSpeed.translation(1.0,1.0,3,false)
        assertEquals(.6,kotlin.math.hypot(diagonal.first,diagonal.second),1e-9)
        assertEquals(0.0 to 0.0,DriveSpeed.translation(0.0,0.0,3,false))
    }
}
