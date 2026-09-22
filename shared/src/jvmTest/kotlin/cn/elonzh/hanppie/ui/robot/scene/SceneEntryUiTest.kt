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
import cn.elonzh.hanppie.ui.robot.remote.LocalVideoStreamEnabled
import cn.elonzh.hanppie.ui.robot.remote.LocalVideoInputEnabled
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
        var stream = false
        var input = false
        try {
            setContent {
                val state by model.state.collectAsState()
                CompositionLocalProvider(LocalRobotSceneRenderer provides { _, _, _, _, _, loaded, _ ->
                    progress = LocalSceneEntryProgress.current
                    LaunchedEffect(Unit) { loaded() }
                }) {
                    WorkbenchTheme {
                        RobotExperience(controller, state, false, Modifier.fillMaxSize(), {}, {}, { cockpit = it }, video = {
                            stream = LocalVideoStreamEnabled.current
                            input = LocalVideoInputEnabled.current
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
            assertTrue(model.remoteEnabled.value, "Remote preparation should run during entry, not after HUD reveal")
            assertEquals(1f, progress, "Camera duration must not stretch to hide video startup")
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
            runOnUiThread { model.setForeground(false) }
            mainClock.advanceTimeBy(500)
            assertTrue(cockpit)
            assertEquals(1f, progress, "Window deactivation must not start an exit transition")
            assertFalse(input)
            assertTrue(stream)
            assertEquals(0, stops)
            onNodeWithContentDescription("返回设备").assertIsNotEnabled()
            runOnUiThread { model.setForeground(true) }
            mainClock.advanceTimeBy(500)
            assertTrue(input)
            onNodeWithContentDescription("返回设备").performClick()
            mainClock.advanceTimeBy(250)
            assertFalse(model.remoteEnabled.value, "Exit must stop robot input before animating")
            assertEquals(0, stops, "Keep the last live video until the scene covers it")
            assertTrue(progress < 1f, "Exit camera must already be moving while video fades")
            mainClock.advanceTimeBy(650)
            assertEquals(0, stops, "Keep live decoding warm on return to the homepage")
            assertTrue(progress > 0f)
            repeat(12) {
                mainClock.advanceTimeBy(25)
                onNodeWithTag("enter-remote").assertTextContains("开始操控")
                onNodeWithText("正在准备画面…").assertDoesNotExist()
                onNodeWithText("等待视频帧…").assertDoesNotExist()
            }
            mainClock.advanceTimeBy(950)
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
            assertEquals(0, stops, "Losing activation must retain the cockpit decoder")
            assertTrue(stream)
            assertFalse(input)
            runOnUiThread { media!!.videoReady(true); model.setForeground(true) }
            mainClock.advanceTimeBy(800)
            assertTrue(cockpit)
            assertEquals(1f, progress)
            assertTrue(input)
            assertTrue(model.remoteInput.value.all { it == 0.0 })
        } finally { model.close() }
    }

    @Test fun inactiveEntryDoesNotNavigateAwayAndDisconnectStillReleasesControl() = runDesktopComposeUiTest(width = 900, height = 550) {
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
            assertTrue(active, "Focus loss must preserve the in-progress camera entry")
            assertFalse(model.remoteEnabled.value)
            assertEquals(0f, hudAlpha)
            runOnUiThread { model.setForeground(true) }
            mainClock.advanceTimeBy(400)
            runOnUiThread { model.state.value = model.state.value.copy(connected = false) }
            mainClock.advanceTimeBy(2_500)
            assertFalse(active)
            assertFalse(model.remoteEnabled.value)
            assertEquals(0f, hudAlpha)
        } finally { model.close() }
    }
    @Test fun homepageWarmsBrieflyAndRefreshesAfterReturningFromAnotherPage() = runDesktopComposeUiTest(width = 900, height = 550) {
        mainClock.autoAdvance = false
        val model = testConsoleModel()
        model.state.value = model.state.value.copy(connected = true, connectedAddress = "192.0.2.1")
        var showHome by mutableStateOf(true)
        var stream = false
        var controller: RemoteMediaController? = null
        var input = false
        var progress = 0f
        val robot = object : ConsoleController by model {
            override fun enableRemote() { model.remoteEnabled.value = true }
        }
        try {
            setContent {
                val state by model.state.collectAsState()
                if (showHome) CompositionLocalProvider(LocalRobotSceneRenderer provides { _, _, _, _, _, loaded, _ ->
                    progress = LocalSceneEntryProgress.current
                    LaunchedEffect(Unit) { loaded() }
                }) {
                    WorkbenchTheme { RobotExperience(robot, state, false, Modifier.fillMaxSize(), {}, {}, {}, video = {
                        controller = it
                        input = LocalVideoInputEnabled.current
                        stream = LocalVideoStreamEnabled.current
                        DisposableEffect(stream) {
                            if (!stream) it.videoReady(false)
                            onDispose { }
                        }
                    }) }
                }
            }
            mainClock.advanceTimeBy(100)
            assertTrue(stream)
            assertFalse(model.remoteEnabled.value)
            val first = controller!!
            runOnUiThread {
                first.videoReady(true)
                model.videoFrames.publish(model.state.value.connectedAddress, androidx.compose.ui.graphics.ImageBitmap(2, 2))
            }
            mainClock.advanceTimeBy(5_200)
            assertFalse(stream, "Idle homepage must stop requesting stream and decoding")
            assertFalse(first.state.value.videoReady)
            assertTrue(first.state.value.videoFrameAtEpochMillis > 0, "Keep one still after stopping the stream")
            runOnUiThread { first.state.value = first.state.value.copy(videoFrameAtEpochMillis = 1) }
            onNodeWithTag("enter-remote").performClick()
            mainClock.advanceTimeBy(300)
            assertTrue(stream)
            assertTrue(progress > 0f, "An old cached image must still bridge stream restart")
            mainClock.advanceTimeBy(2_000)
            assertFalse(input, "A cached still must never unlock control without a new live frame")
            runOnUiThread {
                first.videoReady(true)
                model.videoFrames.publish(model.state.value.connectedAddress, androidx.compose.ui.graphics.ImageBitmap(2, 2))
            }
            mainClock.advanceTimeBy(600)
            assertTrue(input)
            // Leaving the page retains the still for the same robot address.
            runOnUiThread { showHome = false }
            mainClock.advanceTimeBy(100)
            runOnUiThread { showHome = true }
            mainClock.advanceTimeBy(100)
            assertTrue(stream)
            assertNotSame(first, controller)
            assertNotNull(model.videoFrames.image(model.state.value.connectedAddress))
            assertEquals(0, controller.state.value.videoFrameAtEpochMillis)
            runOnUiThread { model.state.value = model.state.value.copy(connectedAddress = "192.0.2.2") }
            mainClock.advanceTimeBy(100)
            assertEquals(0, controller.state.value.videoFrameAtEpochMillis)
        } finally { model.close() }
    }

}
