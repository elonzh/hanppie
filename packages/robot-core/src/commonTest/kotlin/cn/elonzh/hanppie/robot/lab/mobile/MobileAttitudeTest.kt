package cn.elonzh.hanppie.robot.lab.mobile

import kotlin.math.*
import kotlin.test.*

class MobileAttitudeTest {
    private fun check(point: Triple<Float,Float,Float>?, x: Float, y: Float, z: Float) {
        val actual = assertNotNull(point)
        listOf(actual.first to x, actual.second to y, actual.third to z).forEach { (value, expected) ->
            assertTrue(abs(((value-expected+540)%360)-180)<0.01, "$actual expected $x/$y/$z")
        }
    }
    @Test fun deviceAxesUseZxyDegreesAndQuaternionScaleDoesNotMatter() {
        check(mobileAttitude(0.0,0.0,0.0,1.0),0f,0f,0f)
        val s = sqrt(0.5)
        check(mobileAttitude(s,0.0,0.0,s),90f,0f,0f)
        check(mobileAttitude(0.0,-s,0.0,s),0f,270f,0f)
        check(mobileAttitude(0.0,0.0,s*3,s*3),0f,0f,90f)
        assertNull(mobileAttitude(0.0,0.0,0.0,0.0)); assertNull(mobileAttitude(Double.NaN,0.0,0.0,1.0))
    }
    @Test fun mixedRotationAndSingularPoseReconstructTheSameZxyRotation() {
        // q = Ry(40) * Rx(30) * Rz(20), independently multiplied in this fixture.
        check(mobileAttitude(0.2968829046,0.2831140528,0.0704393378,0.9092553403),30f,40f,20f)
        // At pitch 90, yaw 40 and roll 20 are equivalent to yaw 20 and roll 0.
        val a=10*PI/180; val s=sqrt(0.5)
        check(mobileAttitude(s*cos(a),s*sin(a),-s*sin(a),s*cos(a)),90f,20f,0f)
    }
}
