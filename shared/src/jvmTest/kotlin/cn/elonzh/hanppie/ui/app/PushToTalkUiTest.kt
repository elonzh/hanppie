package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.display.displayFixtureVideo
import cn.elonzh.hanppie.ui.robot.remote.*
import cn.elonzh.hanppie.ui.settings.*
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.*

/** Actual touch/key/semantic controls with a synthetic microphone and robot transport. */
@OptIn(ExperimentalTestApi::class)
class PushToTalkUiTest {
    @Test fun compact() = scenario(844, 390, 1f, false)
    @Test fun tabletDark() = scenario(1024, 768, 1f, true)
    @Test fun desktop() = scenario(1280, 720, 1f, false)
    @Test fun largeNineLanguages() = scenario(844, 390, 1.4f, true)
    @Test fun unsupportedInputHasNoTouchEntry() = scenario(844, 390, 1f, false, supported = false)

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    }
    private class Input(override val available: Boolean) : SpeakerInput {
        val ready = mutableListOf<() -> Unit>()
        var failure: Exception? = null
        override fun start(onReady: () -> Unit, onError: (Throwable) -> Unit) { failure?.let { throw it }; ready += onReady }
        fun announceReady() { ready.last().invoke() }
        override suspend fun finish(): ByteArray = byteArrayOf(1, 2, 3)
        override fun cancel() = Unit
    }

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean, supported: Boolean = true) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize("en", "en")
            val microphoneFixture = Input(supported); val robot = RobotSessionFixture()
            val model = testConsoleModel(robotRuntime = powerRuntime(listOf(robot)), speakerInput = microphoneFixture)
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val video = displayFixtureVideo(); val sent = CopyOnWriteArrayList<ByteArray>(); val release = CompletableDeferred<Unit>()
            var failSend = false
            robot.speakerPlayback = { bytes -> sent += bytes; release.await(); if (failSend) error("synthetic SEND EIO"); 3 }
            fun capture(name: String) {
                waitForIdle()
                val tag = if (onAllNodesWithTag("push-to-talk-error").fetchSemanticsNodes().isEmpty()) "remote-surface" else "push-to-talk-error"
                val image = onNode(isRoot() and hasAnyDescendant(hasTestTag(tag))).captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/talk-$width-$height-$scale-${if (dark) "dark" else "light"}-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(bitmap, "png", file)
            }
            fun phase(value: TalkPhase) {
                try { waitUntil(timeoutMillis = 5_000) { model.pushToTalk.state.value.phase == value } }
                catch (error: Throwable) {
                    println("Expected $value, actual=${model.pushToTalk.state.value}, starts=${microphoneFixture.ready.size}, remote=${model.remoteEnabled.value}, foreground=${model.isForeground}")
                    throw error
                }
                waitForIdle()
            }
            try {
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5_000) { model.state.value.connected && !model.state.value.busy }
                setContent {
                    LaunchedEffect(model) { while (true) { model.videoFrames.publish(model.state.value.connectedAddress, video); delay(50) } }
                    WorkbenchTheme(appearance) {
                        val owner = remember { Owner() }
                        CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalDensity provides Density(1f, scale)) {
                            RemotePage(model, videoContent = { Image(video, "Synthetic offline talk fixture", Modifier.fillMaxSize()) })
                        }
                    }
                }
                model.enableRemote(); waitUntil(timeoutMillis = 5_000) { model.remoteEnabled.value && !model.state.value.busy }
                if (!supported) { onNodeWithTag("push-to-talk-button").assertDoesNotExist(); return@runDesktopComposeUiTest }
                onNodeWithTag("push-to-talk-button").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                onNodeWithTag("push-to-talk-button").performTouchInput { down(center) }
                phase(TalkPhase.PREPARING); capture("preparing")
                onNodeWithTag("push-to-talk-button").performTouchInput { up() }
                phase(TalkPhase.IDLE); assertTrue(sent.isEmpty())
                onNodeWithTag("push-to-talk-button").performTouchInput { down(center) }
                phase(TalkPhase.PREPARING); runOnIdle { microphoneFixture.announceReady() }
                phase(TalkPhase.RECORDING); capture("recording")
                val starts = microphoneFixture.ready.size
                onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.T) }
                assertEquals(starts, microphoneFixture.ready.size)
                onNodeWithTag("push-to-talk-button").performTouchInput { up() }
                phase(TalkPhase.RECORDING)
                onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.T) }
                phase(TalkPhase.SENDING); onNodeWithTag("push-to-talk-button").assertIsNotEnabled()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)); capture("sending")
                release.complete(Unit); phase(TalkPhase.IDLE)
                assertEquals(1, sent.size); assertContentEquals(byteArrayOf(1, 2, 3), sent.single())
                runOnIdle { microphoneFixture.failure = IllegalStateException("synthetic EACCES " + "device/".repeat(180)) }
                onNodeWithTag("push-to-talk-button").performTouchInput { down(center); up() }
                waitUntil(timeoutMillis = 5_000) { model.pushToTalk.state.value.error != null }
                onNodeWithTag("push-to-talk-problem").assertHeightIsAtLeast(48.dp); capture("capture-error")
                onNodeWithTag("push-to-talk-problem").performClick()
                onNodeWithTag("push-to-talk-error").assertTextEquals(checkNotNull(model.pushToTalk.state.value.error).resolve())
                onNodeWithTag("push-to-talk-error-close").assertHeightIsAtLeast(48.dp); capture("capture-details")
                if (scale > 1f) for (language in listOf("zh", "zh-Hant", "en", "ja", "de", "es", "fr", "ko", "ru")) {
                    runOnIdle { Localization.select(language) }
                    onNodeWithTag("push-to-talk-error").assertTextEquals(checkNotNull(model.pushToTalk.state.value.error).resolve())
                    onNodeWithTag("push-to-talk-error-close").assertIsDisplayed(); capture("error-$language")
                }
                runOnIdle { Localization.select("en") }
                onNodeWithTag("push-to-talk-error-close").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("push-to-talk-error-close").fetchSemanticsNodes().isEmpty() }
                assertNull(model.pushToTalk.state.value.error)
                runOnIdle { microphoneFixture.failure = null; failSend = true }
                onNodeWithTag("push-to-talk-button").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }; phase(TalkPhase.PREPARING)
                runOnIdle { microphoneFixture.announceReady() }; phase(TalkPhase.RECORDING)
                onNodeWithTag("push-to-talk-button").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }; phase(TalkPhase.IDLE)
                assertNotNull(model.pushToTalk.state.value.error); capture("send-error")
                runOnIdle { failSend = false }
                onNodeWithTag("push-to-talk-button").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }; phase(TalkPhase.PREPARING)
                assertNull(model.pushToTalk.state.value.error)
                runOnIdle { microphoneFixture.announceReady() }; phase(TalkPhase.RECORDING)
                onNodeWithTag("push-to-talk-button").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }; phase(TalkPhase.IDLE)
                assertEquals(3, sent.size); assertNull(model.pushToTalk.state.value.error); capture("recovered")
                runOnIdle { microphoneFixture.failure = IllegalStateException("retry fixture") }
                onNodeWithTag("push-to-talk-button").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
                assertNotNull(model.pushToTalk.state.value.error)
                runOnIdle { microphoneFixture.failure = null }
                onNodeWithTag("push-to-talk-button").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }; phase(TalkPhase.PREPARING)
                runOnIdle { model.setForeground(false); microphoneFixture.announceReady() }
                phase(TalkPhase.IDLE); onNodeWithTag("push-to-talk-button").assertIsNotEnabled()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)); capture("background")
                runOnIdle { model.setForeground(true) }
                phase(TalkPhase.IDLE); assertEquals(3, sent.size)
            } finally { release.complete(Unit); model.close() }
        }
}
