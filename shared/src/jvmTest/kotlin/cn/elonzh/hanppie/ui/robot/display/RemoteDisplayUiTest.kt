package cn.elonzh.hanppie.ui.robot.display

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import cn.elonzh.hanppie.robot.display.*
import cn.elonzh.hanppie.robot.tracking.*
import cn.elonzh.hanppie.robot.vision.VisionRect
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.remote.*
import cn.elonzh.hanppie.ui.settings.*
import kotlinx.coroutines.delay
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class RemoteDisplayUiTest {
    @Test fun compactCockpit()=cockpit(844,390,"zh",1f)
    @Test fun largeEnglishCockpit()=cockpit(1280,720,"en",1.4f)

    private fun cockpit(width:Int,height:Int,language:String,scale:Float)=runDesktopComposeUiTest(width=width,height=height){
        Localization.initialize(language,language)
        val peer=DisplayFixture().apply{sight=SightCalibration(SightPoint(5250,4900),SightPoint(5250,4900))}
        val session=RobotSessionFixture().apply{display=peer};val model=testConsoleModel(robotRuntime=powerRuntime(listOf(session)))
        val video=displayFixtureVideo()
        fun capture(name:String){
            val bitmap=onRoot().captureToImage();val pixels=IntArray(bitmap.width*bitmap.height);bitmap.readPixels(pixels)
            val output=BufferedImage(bitmap.width,bitmap.height,BufferedImage.TYPE_INT_ARGB)
            output.setRGB(0,0,bitmap.width,bitmap.height,pixels,0,bitmap.width)
            val file=File("build/reports/ui/display-cockpit-$width-$height-$language-$name.png");file.parentFile.mkdirs();ImageIO.write(output,"png",file)
        }
        try{
            model.connect("127.0.0.1","12345678")
            waitUntil(timeoutMillis=5000){model.state.value.connected && !model.state.value.busy && model.robotDisplay.state.value.sight!=null && !model.settingsBusy.value}
            setContent{
                LaunchedEffect(model){while(true){model.videoFrames.publish(model.state.value.connectedAddress,video);delay(100)}}
                WorkbenchTheme{
                    val density=LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density,scale)){
                        RemotePage(model,videoContent={
                            val display by model.displaySettings.collectAsState()
                            VideoPicture(1280,720,display.fillVideo){Image(video,null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
                        },manageSession=false)
                    }
                }
            }
            onNodeWithTag("recenter-gimbal").assertExists().assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            val fit=VideoViewport.forImage(width.toFloat(),height.toFloat(),1280,720)
            val first=onNodeWithTag("remote-crosshair").fetchSemanticsNode().boundsInRoot.center
            assertTrue(abs(first.x-width/2f-fit.width*.025f)<2f)
            capture("fit-cross")
            runOnIdle{model.displaySettings.value=model.displaySettings.value.copy(fillVideo=true,reticle=ReticleStyle.ROUND,showGimbalAngle=false)}
            onNodeWithTag("recenter-gimbal").assertDoesNotExist()
            onNodeWithContentDescription(if(language=="zh")"云台回中" else "Recenter gimbal").assertIsDisplayed()
            val crop=VideoViewport.forImage(width.toFloat(),height.toFloat(),1280,720,true)
            val second=onNodeWithTag("remote-crosshair").fetchSemanticsNode().boundsInRoot.center
            assertTrue(abs(second.x-width/2f-crop.width*.025f)<2f)
            capture("crop-round")
            assertEquals(0,peer.writes,"Changing host display choices must not write device settings")
        }finally{model.close()}
    }

    @Test fun croppedTargetsKeepHitTestingAndAccessibilityWithinTheVisibleImage()=runDesktopComposeUiTest(width=1000,height=500){
        Localization.initialize("zh","zh")
        val targets=listOf(VisionRect(.5f,.05f,.1f,.05f,1),VisionRect(.5f,.5f,.2f,.2f,1))
        var selected:Pair<Float,Float>?=null
        setContent{WorkbenchTheme{ObjectTrackingTargets(TrackingState(TrackingPhase.SEARCHING,candidates=targets),1000,1000,true,
            {x,y->selected=x to y},{},{},fillVideo=true)}}
        onNodeWithTag("tracking-target-0").assertDoesNotExist()
        onNodeWithTag("tracking-target-1").assertHasClickAction()
        onNodeWithTag("tracking-targets").performTouchInput{click(Offset(500f,2f))}
        assertNull(selected)
        onNodeWithTag("tracking-targets").performTouchInput{click(center)}
        assertEquals(.5f to .5f,selected)
    }
}
