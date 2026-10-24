package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.elonzh.hanppie.robot.remote.*
import cn.elonzh.hanppie.robot.challenge.*
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.display.displayFixtureVideo
import cn.elonzh.hanppie.ui.robot.remote.*
import cn.elonzh.hanppie.ui.settings.*
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlin.test.*

/** Actual cockpit/controller with synthetic heat, session and video; no robot or physical cooling. */
@OptIn(ExperimentalTestApi::class)
class GunCoolingUiTest {
    @Test fun compact() = scenario(844, 390, "zh", 1f, false)
    @Test fun tabletDark() = scenario(1024, 768, "zh", 1f, true)
    @Test fun desktop() = scenario(1280, 720, "zh", 1f, false)
    @Test fun largeEnglish() = scenario(844, 390, "en", 1.4f, false)

    private class InputLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    }

    private fun scenario(width: Int, height: Int, language: String, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, language)
            val robot = RobotSessionFixture()
            val model = testConsoleModel(robotRuntime = powerRuntime(listOf(robot)))
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val video = displayFixtureVideo()
            val requests = AtomicInteger()
            robot.gunCoolingRequest = {
                requests.incrementAndGet()
                robot.gunCooling.value = robot.gunCooling.value.copy(phase =
                    if (robot.gunCooling.value.canRetryReset) GunCoolingPhase.RESETTING else GunCoolingPhase.REQUESTING)
            }
            fun state(value: GunCoolingState) {
                runOnIdle { robot.gunCooling.value = value }
                waitUntil(timeoutMillis = 5_000) { model.gunCooling.value == value }
                waitForIdle()
            }
            fun capture(name: String) {
                waitForIdle()
                val image = onNode(isRoot() and hasAnyDescendant(hasTestTag("remote-surface"))).captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0,0,image.width,image.height,pixels,0,image.width)
                val file = File("build/reports/ui/gun-cooling-$width-$height-$language-${if (dark) "dark" else "light"}-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(bitmap,"png",file)
            }
            try {
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5_000) { model.state.value.connected && !model.state.value.busy }
                setContent {
                    LaunchedEffect(model) { while (true) { model.videoFrames.publish(model.state.value.connectedAddress, video); delay(50) } }
                    WorkbenchTheme(appearance) {
                        val density = LocalDensity.current
                        val owner = remember { InputLifecycle() }
                        CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalDensity provides Density(density.density, scale)) {
                            RemotePage(model, challengeSoundFactory = { object : ChallengeSoundPlayer {
                                override fun play(cue: ChallengeCue) = Unit
                                override fun close() = Unit
                            } }, videoContent = { Image(video,"Synthetic offline cooling fixture",Modifier.fillMaxSize()) })
                        }
                    }
                }
                model.enableRemote(); waitUntil(timeoutMillis = 5_000) { model.remoteEnabled.value && !model.state.value.busy }
                onNodeWithTag("gun-cooling").assertDoesNotExist()
                state(GunCoolingState(500, true))
                onNodeWithTag("gun-heat").assertTextContains("25%", substring = true)
                onNodeWithTag("gun-cooling-action").assertIsEnabled().assertHeightIsAtLeast(48.dp)
                capture("heat")
                onNodeWithTag("gun-cooling-action").performClick()
                waitUntil(timeoutMillis = 5_000) { requests.get() == 1 }
                onNodeWithTag("gun-cooling-action").assertDoesNotExist(); capture("requesting")
                state(GunCoolingState(200, true, GunCoolingPhase.COOLING)); capture("cooling")
                state(GunCoolingState(0, true, GunCoolingPhase.RESETTING)); capture("resetting")
                state(GunCoolingState(0, false, GunCoolingPhase.UNCONFIRMED, GunCoolingProblem.TIMED_OUT))
                onNodeWithTag("gun-cooling-action").assertIsNotEnabled(); capture("unconfirmed-stale")
                state(GunCoolingState(0, true, GunCoolingPhase.UNCONFIRMED, GunCoolingProblem.TIMED_OUT))
                onNodeWithTag("gun-cooling-action").assertIsEnabled(); capture("unconfirmed")
                onNodeWithTag("gun-cooling-action").performClick()
                waitUntil(timeoutMillis = 5_000) { requests.get() == 2 }
                state(GunCoolingState(500, false)); onNodeWithTag("gun-cooling-action").assertIsNotEnabled(); capture("stale")
                state(GunCoolingState(500, true, problem = GunCoolingProblem.REJECTED))
                onNodeWithTag("gun-cooling-action").assertIsEnabled(); capture("rejected")
                state(GunCoolingState(2000, true)); onNodeWithTag("gun-cooling-action").assertIsNotEnabled(); capture("overheated")
                state(GunCoolingState(500, true))
                onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.Spacebar) }
                waitUntil(timeoutMillis = 5_000) { robot.infraredCount.get() >= 2 }
                // A held key is retired on cooling ownership and cannot resume when cooling finishes.
                state(GunCoolingState(500, true, GunCoolingPhase.COOLING))
                onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.Spacebar); keyDown(Key.Spacebar) }
                val stopped = robot.infraredCount.get()
                state(GunCoolingState(0, true))
                onNodeWithTag("gun-cooling").assertDoesNotExist()
                Thread.sleep(250); waitForIdle(); assertEquals(stopped, robot.infraredCount.get())
                onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.Spacebar); keyDown(Key.Spacebar) }
                waitUntil(timeoutMillis = 5_000) { robot.infraredCount.get() > stopped }
                onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.Spacebar) }
                state(GunCoolingState(700, true)); onNodeWithTag("gun-cooling-action").assertIsDisplayed(); capture("keyboard")
                runOnIdle { robot.challengeState.value = ChallengeState(phase = ChallengePhase.PLAYING,
                    configuration = ChallengeConfiguration(ChallengeMode.TARGET_PRACTICE), roundId = 1) }
                waitUntil(timeoutMillis = 5_000) { model.challenge.value.phase == ChallengePhase.PLAYING }
                onNodeWithTag("gun-cooling-action").performClick()
                waitUntil(timeoutMillis = 5_000) { requests.get() == 3 }
                assertEquals(ChallengePhase.PLAYING, model.challenge.value.phase)
                capture("challenge")
            } finally { model.close() }
        }
}
