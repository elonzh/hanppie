package cn.elonzh.hanppie.ui.robot.display

import androidx.compose.ui.geometry.Offset
import cn.elonzh.hanppie.robot.display.SightPoint
import cn.elonzh.hanppie.robot.vision.VisionRect
import cn.elonzh.hanppie.ui.robot.remote.VideoViewport
import cn.elonzh.hanppie.ui.settings.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

class DisplayGeometryTest {
    @Test fun cropAndFitMapVisiblePointsRectanglesAndReticlesToTheSameSource(): Unit {
        val fit=VideoViewport.forImage(1000f,500f,1000,1000)
        assertNull(fit.point(0f,250f));assertEquals(.5f to .5f,fit.point(500f,250f))
        val crop=VideoViewport.forImage(1000f,500f,1000,1000,true)
        assertEquals(0f to .25f,crop.point(0f,0f));assertEquals(1f to .75f,crop.point(1000f,500f))
        assertNull(crop.point(500f,-1f));assertNull(crop.point(500f,501f))
        val hidden=crop.bounds(VisionRect(.5f,.05f,.1f,.05f))
        assertEquals(0f,hidden.height)
        val visible=crop.bounds(VisionRect(.5f,.5f,.2f,.2f))
        assertEquals(400f,visible.left);assertEquals(150f,visible.top);assertEquals(200f,visible.width)
        assertEquals(Offset(50f,-50f),fit.sightOffset(SightPoint(6000,6000),100))
        assertEquals(Offset(400f,-400f),crop.sightOffset(SightPoint(6000,6000),400))
    }

    @Test fun fineAdjustmentUsesActualZoomBudgetAndZeroIsOnlyADraft(): Unit {
        var point=SightPoint()
        repeat(80){point=SightAdjustment(point,100).move(1,0)}
        assertEquals(SightPoint(6250,5000),point)
        assertEquals(50,SightAdjustment(point,100).xSteps)
        assertFalse(SightAdjustment(point,400).adjustable)
        assertFailsWith<IllegalArgumentException>{SightAdjustment(point,400).move(-1,0)}
        val up=SightAdjustment(SightPoint(),400).move(0,1)
        assertEquals(SightPoint(5000,5006),up)
        assertEquals(1,SightAdjustment(up,400).ySteps)
        assertEquals(SightPoint(4993,5000),SightAdjustment(SightPoint(),400).move(-1,0))
        assertFailsWith<IllegalArgumentException>{SightAdjustment(SightPoint(),200)}
    }

    @Test fun savedPreferencesKeepPublishedDefaultsAndExcludeDeviceCalibration(): Unit {
        val old=settingsJson.decodeFromString<SavedSettings>("{}")
        assertEquals(DisplaySettings(),old.display)
        assertTrue(old.display.showGimbalAngle);assertFalse(old.display.fillVideo)
        assertEquals(SceneQuality.HIGH,old.display.sceneQuality)
        val changed=old.copy(display=DisplaySettings(ReticleStyle.ROUND,true,false,true,SceneQuality.LOW))
        val json=settingsJson.encodeToString(changed)
        assertEquals(changed,settingsJson.decodeFromString<SavedSettings>(json))
        assertFalse("headlight" in json || "zoomIn" in json || "zoomOut" in json)
    }
}
