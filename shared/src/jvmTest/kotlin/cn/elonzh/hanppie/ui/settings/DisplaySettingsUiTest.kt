package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.display.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.display_connect
import cn.elonzh.hanppie.resources.display_headlights
import cn.elonzh.hanppie.resources.display_confirmed
import cn.elonzh.hanppie.resources.display_sight_help
import cn.elonzh.hanppie.resources.display_sight_exit
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.AppLanguage
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.display.*
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DisplaySettingsUiTest {
    @Test fun shortLandscape()=scenario(844,390,"zh",1f,false)
    @Test fun mediumDarkLandscape()=scenario(1024,600,"zh",1f,true)
    @Test fun desktopLandscape()=scenario(1280,720,"zh",1f,false)
    @Test fun largeEnglishLandscape()=scenario(844,390,"en",1.4f,false)

    private fun scenario(width:Int,height:Int,language:String,scale:Float,dark:Boolean)=
        runDesktopComposeUiTest(width=width,height=height){
            Localization.initialize(language,language)
            val store=MemorySettingsStore();val peer=DisplayFixture()
            val session=RobotSessionFixture().apply{display=peer}
            session.cameraState.value=session.cameraState.value.copy(zoomHundredths=100)
            session.zoomRequest={value->session.cameraState.value=session.cameraState.value.copy(zoomHundredths=value)}
            val model=testConsoleModel(settingsStore=store,robotRuntime=powerRuntime(listOf(session)))
            val appearance=AppearanceController(AppearanceSettings(if(dark)NightMode.DARK else NightMode.LIGHT))
            val video=displayFixtureVideo()
            val prefix="display-settings-$width-$height-$language-${if(dark)"dark" else "light"}"
            fun capture(state:String){
                waitForIdle()
                val image=onRoot().captureToImage();val pixels=IntArray(image.width*image.height);image.readPixels(pixels)
                val output=BufferedImage(image.width,image.height,BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0,0,image.width,image.height,pixels,0,image.width)
                val file=File("build/reports/ui/$prefix-$state.png");file.parentFile.mkdirs();ImageIO.write(output,"png",file)
            }
            try{
                model.connect("127.0.0.1","12345678")
                waitUntil(timeoutMillis=5000){model.state.value.connected && !model.state.value.busy && !model.settingsBusy.value && !model.robotDisplay.state.value.busy}
                setContent{
                    LaunchedEffect(model){while(true){model.videoFrames.publish(model.state.value.connectedAddress,video);delay(100)}}
                    WorkbenchTheme(appearance){
                        val density=LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density,scale)){
                            Box(Modifier.fillMaxSize().padding(16.dp)){SettingsPage(model)}
                        }
                    }
                }
                onNodeWithTag("settings-category-display").performScrollTo().performClick()
                onNodeWithTag("display-framing-selector").performScrollTo();capture("host-top")
                onNodeWithTag("display-reticle-selector").performClick();onNodeWithTag("display-reticle-ROUND").performClick()
                onNodeWithTag("display-framing-selector").performClick();onNodeWithTag("display-framing-true").performClick()
                onNodeWithTag("display-angle").performScrollTo().performClick()
                onNodeWithTag("display-arm-position").performScrollTo().performClick()
                onNodeWithTag("display-quality-selector").performScrollTo().performClick();onNodeWithTag("display-quality-LOW").performClick()
                onNodeWithTag("settings-save").performClick()
                waitUntil(timeoutMillis=5000){!model.settingsBusy.value}
                assertEquals(DisplaySettings(ReticleStyle.ROUND,true,false,true,SceneQuality.LOW),runBlocking{store.load()}.display)
                assertEquals(0,peer.writes)
                onNodeWithTag("display-quality-selector").performScrollTo();capture("host-options")
                onNodeWithTag("display-lights-read").performScrollTo().performClick()
                waitUntil(timeoutMillis=5000){model.robotDisplay.state.value.headlights!=null}
                val light = onNodeWithTag("display-lights-slider").performScrollTo().fetchSemanticsNode().config
                assertEquals(listOf(tr(Res.string.display_headlights)), light[SemanticsProperties.ContentDescription])
                assertEquals(0f..100f, light[SemanticsProperties.ProgressBarRangeInfo].range)
                onNodeWithTag("display-lights-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress){it(70f)}
                onNodeWithTag("display-lights-slider").requestFocus()
                onNodeWithTag("display-lights-slider").assertIsFocused()
                    .performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight) }
                assertEquals(0,peer.writes)
                capture("headlights-focus")
                onNodeWithTag("display-lights-apply").performScrollTo().performClick()
                waitUntil(timeoutMillis=5000){model.robotDisplay.state.value.confirmed==DisplayTarget.HEADLIGHTS && !model.state.value.busy}
                assertEquals(HeadlightBrightness(70,70),peer.lights)
                onNodeWithTag("display-lights-current").performScrollTo();capture("headlights")
                onNodeWithTag("display-lights-read").performScrollTo();capture("headlights-actions")
                peer.write={sent->sent();throw RobotRequestException(RequestProblem.TIMED_OUT)}
                onNodeWithTag("display-lights-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress){it(55f)}
                onNodeWithTag("display-lights-apply").performScrollTo().performClick()
                waitUntil(timeoutMillis=5000){model.robotDisplay.state.value.uncertain==DisplayTarget.HEADLIGHTS && !model.state.value.busy}
                onNodeWithTag("display-lights-slider").assertDoesNotExist()
                onNodeWithTag("display-device-error").performScrollTo();capture("error")
                val gate=CompletableDeferred<Unit>();peer.read={if(it==DisplayTarget.HEADLIGHTS)gate.await()}
                onNodeWithTag("display-lights-read").performScrollTo().performClick()
                waitUntil(timeoutMillis=5000){model.robotDisplay.state.value.busy}
                onNodeWithTag("display-cancel-read").performScrollTo();capture("reading")
                onNodeWithTag("display-cancel-read").performClick();gate.complete(Unit);peer.read={};peer.write={it()}

                onNodeWithTag("display-calibrate").performScrollTo().performClick()
                waitUntil(timeoutMillis=5000){model.robotDisplay.state.value.sight!=null && !model.robotDisplay.state.value.busy}
                onNodeWithTag("display-sight-step-1-0").assertIsEnabled().performClick()
                onNodeWithTag("display-sight-step-0-1").performClick()
                val before=peer.writes
                assertEquals(SightCalibration(),peer.sight)
                val languages=if(scale>1f)AppLanguage.entries else listOf(AppLanguage.entries.single{it.preference==language})
                for(selected in languages){
                    runOnIdle{Localization.select(selected.preference)}
                    onNodeWithTag("display-sight-apply").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    onNodeWithTag("display-calibration-zoom-selector").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    val reticle=onNodeWithTag("display-calibration-reticle").fetchSemanticsNode().boundsInRoot
                    val header=onNodeWithTag("display-calibration-header").fetchSemanticsNode().boundsInRoot
                    val adjustments=onNodeWithTag("display-calibration-adjustments").fetchSemanticsNode().boundsInRoot
                    capture("calibration-normal-${selected.preference}")
                    assertTrue(reticle.top>=header.bottom && reticle.bottom<=adjustments.top,
                        "Calibration controls must leave the aiming reference visible in ${selected.preference}, including large text")
                    onNodeWithTag("display-calibration-status").performScrollTo().assertIsDisplayed()
                    capture("calibration-status-${selected.preference}")
                    onNodeWithContentDescription(tr(Res.string.display_sight_exit)).performScrollTo().assertIsDisplayed()
                }
                runOnIdle{Localization.select(language)}
                onNodeWithTag("display-sight-apply").performScrollTo().performClick()
                waitUntil(timeoutMillis=5000){model.robotDisplay.state.value.confirmed==DisplayTarget.SIGHT && !model.state.value.busy}
                assertEquals(SightCalibration(SightPoint(5025,5025),SightPoint(5025,5025)),peer.sight)
                assertEquals(before+1,peer.writes)
                onNodeWithTag("display-calibration-status").assertTextEquals(tr(Res.string.display_confirmed))
                onNodeWithTag("display-sight-step-1-0").performClick()
                onNodeWithTag("display-calibration-status").assertTextEquals(tr(Res.string.display_sight_help))
                assertEquals(before+1,peer.writes,"Moving the draft does not confirm or write it")
                onNodeWithTag("display-sight-step--1-0").performClick()
                onNodeWithTag("display-calibration-status").assertTextEquals(tr(Res.string.display_confirmed))
                onNodeWithTag("display-calibration-zoom-selector").performScrollTo().performClick();onNodeWithTag("display-calibration-zoom-400").performClick()
                waitUntil(timeoutMillis=5000){model.cameraZoom.state.value.hundredths==400 && !model.cameraZoom.state.value.busy}
                onNodeWithTag("display-sight-zero").performScrollTo().performClick()
                assertEquals(before+1,peer.writes,"Zero changes only the draft")
                onNodeWithTag("display-calibration-status").assertTextEquals(tr(Res.string.display_sight_help))
                capture("calibration-zoom")
                onNodeWithTag("display-sight-apply").performScrollTo().performClick()
                waitUntil(timeoutMillis=5000){peer.sight==SightCalibration() && !model.state.value.busy}
                assertFalse(model.remoteEnabled.value);assertTrue(model.remoteInput.value.all{it==0.0})
                peer.write={sent->sent();throw RobotRequestException(RequestProblem.TIMED_OUT)}
                onNodeWithTag("display-sight-step-1-0").performScrollTo().performClick();onNodeWithTag("display-sight-apply").performScrollTo().performClick()
                waitUntil(timeoutMillis=5000){model.robotDisplay.state.value.uncertain==DisplayTarget.SIGHT && !model.state.value.busy}
                onNodeWithTag("display-sight-apply").assertDoesNotExist();capture("calibration-error")
                onNodeWithTag("display-calibration-page").performKeyInput{pressKey(Key.Escape)}
                onNodeWithTag("settings-detail-display").assertExists()
                runBlocking{model.pauseConnection()}
                waitUntil(timeoutMillis=5000){!model.state.value.connected}
                onNodeWithText(tr(Res.string.display_connect)).performScrollTo().assertIsDisplayed();capture("disconnected")
            }finally{model.close()}
        }
}
