package cn.elonzh.hanppie.ui.robot.scene

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.settings.SceneQuality
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SceneQualityUiTest {
    @Test fun savedQualityReachesTheActualHomepageScene()=runDesktopComposeUiTest(width=960,height=540){
        Localization.initialize("zh","zh")
        val model=testConsoleModel();var observed:SceneQuality?=null
        try{
            waitUntil(timeoutMillis=5000){!model.settingsBusy.value}
            setContent{WorkbenchTheme{CompositionLocalProvider(LocalRobotSceneRenderer provides {_,_,_,_,_,ready,_->
                val quality=LocalSceneQuality.current
                SideEffect{observed=quality}
                LaunchedEffect(Unit){ready()}
            }){RobotExperience(model,model.state.value,false,Modifier.fillMaxSize(),{},{},{},video={})}}}
            for(quality in SceneQuality.entries){
                runOnIdle{model.displaySettings.value=model.displaySettings.value.copy(sceneQuality=quality)}
                waitUntil(timeoutMillis=5000){observed==quality}
            }
        }finally{model.close()}
    }

    @Test fun threeQualityPresetsRenderTheRealGpuSceneWithoutChangingRobotState()=runDesktopComposeUiTest(width=960,height=540){
        assumeTrue("Run with HANPPIE_GPU_TESTS=1",System.getenv("HANPPIE_GPU_TESTS")=="1")
        Localization.initialize("zh","zh");mainClock.autoAdvance=false
        val quality=mutableStateOf(SceneQuality.HIGH)
        setContent{WorkbenchTheme{RobotScene(RobotSceneState(RobotAppearance.TURRET,PoseStatus.PREVIEW),Modifier.fillMaxSize(),quality=quality.value)}}
        val captures=mutableListOf<ImageBitmap>()
        for(value in listOf(SceneQuality.HIGH,SceneQuality.MEDIUM,SceneQuality.LOW)){
            runOnUiThread{quality.value=value}
            waitUntil(timeoutMillis=30000){
                mainClock.advanceTimeByFrame();Thread.sleep(15)
                onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"外观预览 · 连接后查看机器人状态")).fetchSemanticsNodes().isNotEmpty()
            }
            waitUntil(timeoutMillis=30000){
                mainClock.advanceTimeByFrame();Thread.sleep(15)
                val image=onNodeWithTag("robot-scene-viewport").captureToImage();val pixels=IntArray(image.width*image.height);image.readPixels(pixels)
                pixels.count{((it shr 8)and 255)>((it shr 16)and 255)+30 && (it and 255)>((it shr 16)and 255)+30}>50
            }
            repeat(30){mainClock.advanceTimeByFrame();Thread.sleep(15)};waitForIdle()
            val image=onRoot().captureToImage();captures+=image
            val pixels=IntArray(image.width*image.height);image.readPixels(pixels)
            val output=BufferedImage(image.width,image.height,BufferedImage.TYPE_INT_ARGB)
            output.setRGB(0,0,image.width,image.height,pixels,0,image.width)
            val path=File("build/reports/ui/display-scene-quality-${value.name.lowercase()}.png");path.parentFile.mkdirs();ImageIO.write(output,"png",path)
        }
        val first=IntArray(960*540);val last=IntArray(960*540)
        captures.first().readPixels(first);captures.last().readPixels(last)
        assertTrue(first.indices.count{first[it]!=last[it]}>100,"Different renderer presets must reach the GPU output")
    }
}
