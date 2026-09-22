package cn.elonzh.hanppie.ui.robot.scene

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SceneCameraTest {
    @Test fun onboardViewFollowsHeadingAndLooksAheadWithoutSingularFrames() {
        for (heading in listOf(-179f, 0f, 179f)) {
            for (yaw in listOf(-90f, 0f, 90f)) {
                for (pitch in listOf(-20f, 25f)) {
                    for (step in 0..100) {
                        val pose = sceneCamera(38f, 16f, 2.65f, .2f, step / 100f, yaw, pitch, heading, RobotAppearance.TURRET)
                        val components = listOf(pose.eye.x, pose.eye.y, pose.eye.z, pose.target.x, pose.target.y, pose.target.z)
                        assertTrue(components.all { it.isFinite() })
                        val d = sqrt((pose.eye.x-pose.target.x)*(pose.eye.x-pose.target.x) +
                            (pose.eye.y-pose.target.y)*(pose.eye.y-pose.target.y) + (pose.eye.z-pose.target.z)*(pose.eye.z-pose.target.z))
                        assertTrue(d > .05f, "Camera look-at must not collapse during the orbit")
                    }
                }
            }
        }
        val neutral = sceneCamera(38f,16f,2.65f,.2f,1f,0f,0f,0f,RobotAppearance.TURRET)
        assertEquals(neutral.eye.x, neutral.target.x, .001f)
        assertTrue(neutral.target.z > neutral.eye.z)
        val turned = sceneCamera(38f,16f,2.65f,.2f,1f,90f,0f,0f,RobotAppearance.TURRET)
        assertTrue(turned.target.x < turned.eye.x)
        assertTrue(abs(turned.target.z - turned.eye.z) < .001f)
        val raised = sceneCamera(38f,16f,2.65f,.2f,1f,0f,25f,0f,RobotAppearance.TURRET)
        assertTrue(raised.target.y > raised.eye.y)
    }
}
