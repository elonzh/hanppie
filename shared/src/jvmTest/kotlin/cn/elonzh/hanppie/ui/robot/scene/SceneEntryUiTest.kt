package cn.elonzh.hanppie.ui.robot.scene

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.robot.remote.LocalVideoHudAlpha
import cn.elonzh.hanppie.ui.robot.remote.RemoteMediaController
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SceneEntryUiTest {
    @Test fun roundTripKeepsOneDecoderAndWaitsForVideoBeforeRevealingControls() = runDesktopComposeUiTest(width = 900, height = 550) {
        mainClock.autoAdvance = false
        val model = testConsoleModel()
        model.state.value = model.state.value.copy(connected = true)
        val controller = object : ConsoleController by model {
            override fun enableRemote() { model.remoteEnabled.value = true }
        }
        var media: RemoteMediaController? = null
        var starts = 0
        var stops = 0
        var progress = 0f
        var hudAlpha = 0f
        var cockpit = false
        try {
            setContent {
                val state by model.state.collectAsState()
                CompositionLocalProvider(LocalRobotSceneRenderer provides { _, _, _, _, _, loaded, _ ->
                    progress = LocalSceneEntryProgress.current
                    LaunchedEffect(Unit) { loaded() }
                }) {
                    WorkbenchTheme {
                        RobotExperience(controller, state, false, Modifier.fillMaxSize(), {}, {}, { cockpit = it }, video = {
                            media = it
                            hudAlpha = LocalVideoHudAlpha.current
                            DisposableEffect(Unit) { starts++; onDispose { stops++ } }
                        })
                    }
                }
            }
            waitForIdle()
            assertEquals(1, starts, "Connected home must prewarm the decoder without enabling control")
            assertFalse(model.remoteEnabled.value)
            onNodeWithTag("remote-surface").assertDoesNotExist()
            onNodeWithTag("enter-remote").performClick()
            mainClock.advanceTimeBy(1_600)
            waitForIdle()
            assertEquals(1, starts)
            assertEquals(0f, progress, "Camera must remain on the home composition until video is ready")
            assertEquals(0f, hudAlpha)
            onNodeWithTag("scene-entry-transition").assertExists()
            // Hidden cockpit keys cannot inject motion while video is preparing.
            onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.W); keyUp(Key.W) }
            assertTrue(model.remoteInput.value.all { it == 0.0 })
            runOnUiThread { media!!.videoReady(true) }
            mainClock.advanceTimeBy(2_200)
            waitForIdle()
            assertEquals(1f, hudAlpha)
            onNodeWithTag("scene-entry-transition").assertDoesNotExist()
            assertTrue(cockpit)
            onNodeWithContentDescription("返回设备").performClick()
            mainClock.advanceTimeBy(250)
            assertFalse(model.remoteEnabled.value, "Exit must stop robot input before animating")
            assertEquals(0, stops, "Keep the last live video until the scene covers it")
            assertTrue(progress < 1f, "Exit camera must already be moving while video fades")
            mainClock.advanceTimeBy(650)
            assertEquals(0, stops, "Keep live decoding warm on return to the homepage")
            assertTrue(progress > 0f)
            mainClock.advanceTimeBy(1_250)
            waitForIdle()
            assertEquals(0f, progress)
            assertFalse(cockpit)
            onNodeWithTag("enter-remote").assertIsDisplayed()
            assertEquals(1, starts)
            runOnUiThread { media!!.videoReady(true) }
            onNodeWithTag("enter-remote").performClick()
            mainClock.advanceTimeBy(300)
            assertTrue(progress > 0f, "A prewarmed stream must start the camera without another decoder startup")
            runOnUiThread { model.setForeground(false) }
            mainClock.advanceTimeBy(1_500)
            assertEquals(1, stops, "Backgrounding must release prewarmed media")
        } finally { model.close() }
    }

    @Test fun cancellingEntryAndDisconnectingReleaseControlAndNeverRevealHud() = runDesktopComposeUiTest(width = 900, height = 550) {
        mainClock.autoAdvance = false
        val model = testConsoleModel()
        model.state.value = model.state.value.copy(connected = true)
        val controller = object : ConsoleController by model {
            override fun enableRemote() { model.remoteEnabled.value = true }
        }
        var hudAlpha = 0f
        var active = false
        try {
            setContent {
                val state by model.state.collectAsState()
                CompositionLocalProvider(LocalRobotSceneRenderer provides { _, _, _, _, _, loaded, _ -> LaunchedEffect(Unit) { loaded() } }) {
                    WorkbenchTheme { RobotExperience(controller, state, false, Modifier.fillMaxSize(), {}, {}, { active = it },
                        video = { hudAlpha = LocalVideoHudAlpha.current }) }
                }
            }
            onNodeWithTag("enter-remote").performClick()
            mainClock.advanceTimeBy(400)
            onNodeWithTag("cancel-scene-entry").assertDoesNotExist()
            runOnUiThread { model.setForeground(false) }
            mainClock.advanceTimeBy(2_500)
            assertFalse(active)
            assertFalse(model.remoteEnabled.value)
            assertEquals(0f, hudAlpha)
            runOnUiThread { model.setForeground(true) }
            onNodeWithTag("enter-remote").performClick()
            mainClock.advanceTimeBy(400)
            runOnUiThread { model.state.value = model.state.value.copy(connected = false) }
            mainClock.advanceTimeBy(2_500)
            assertFalse(active)
            assertFalse(model.remoteEnabled.value)
            assertEquals(0f, hudAlpha)
        } finally { model.close() }
    }
}
