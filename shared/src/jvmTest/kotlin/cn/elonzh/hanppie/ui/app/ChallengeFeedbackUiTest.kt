package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.elonzh.hanppie.robot.challenge.*
import cn.elonzh.hanppie.robot.display.SightPoint
import cn.elonzh.hanppie.robot.vision.*
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.display.displayFixtureVideo
import cn.elonzh.hanppie.ui.robot.remote.*
import cn.elonzh.hanppie.ui.settings.*
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlin.test.*

/** Actual cockpit and controller, synthetic video/session, recording audio sink; no OS audio or robot. */
@OptIn(ExperimentalTestApi::class)
class ChallengeFeedbackUiTest {
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
            val cues = CopyOnWriteArrayList<ChallengeCue>(); val opened = AtomicInteger(); val closed = AtomicInteger()
            val factory = {
                opened.incrementAndGet()
                object : ChallengeSoundPlayer {
                    override fun play(cue: ChallengeCue) { cues += cue }
                    override fun close() { closed.incrementAndGet() }
                }
            }
            var inputs by mutableStateOf(true)
            var roundId = 0L
            robot.cameraState.value = robot.cameraState.value.copy(zoomHundredths = 100)
            robot.challengeStart = { configuration -> robot.challengeState.value = ChallengeState(
                phase = ChallengePhase.PREPARING, configuration = configuration, roundId = ++roundId, stopConfirmed = false) }
            val rectangle = VisionRect(.6f, .45f, .18f, .22f, (120 shl 16) or 11)
            fun vision(value: VisionRect) {
                runOnIdle { robot.vision.value = VisionState(phase = VisionPhase.ACTIVE, mode = VisionMode.MARKERS,
                    report = VisionReport(5,0,0,listOf(value)), rectangles = listOf(value), reportAgeMillis = 0) }
                waitUntil(timeoutMillis = 5_000) { model.vision.value == robot.vision.value }
            }
            fun state(change: ChallengeState.() -> ChallengeState) {
                runOnIdle { robot.challengeState.value = robot.challengeState.value.change() }
                waitUntil(timeoutMillis = 5_000) { model.challenge.value == robot.challengeState.value }
                waitForIdle()
            }
            fun capture(name: String) {
                waitForIdle()
                val tag = if (onAllNodesWithTag("challenge-setup").fetchSemanticsNodes().isNotEmpty()) "challenge-setup" else "remote-surface"
                val image = onNode(isRoot() and hasAnyDescendant(hasTestTag(tag))).captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0,0,image.width,image.height,pixels,0,image.width)
                val file = File("build/reports/ui/challenge-feedback-$width-$height-$language-${if (dark) "dark" else "light"}-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(bitmap,"png",file)
            }
            try {
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5_000) { model.state.value.connected && !model.state.value.busy }
                setContent {
                    LaunchedEffect(model) { while (true) { model.videoFrames.publish(model.state.value.connectedAddress, video); delay(50) } }
                    WorkbenchTheme(appearance) {
                        val owner = remember { InputLifecycle() }; val density = LocalDensity.current
                        CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalDensity provides Density(density.density, scale)) {
                            RemotePage(model, inputEnabled = inputs, challengeSoundFactory = factory,
                                videoContent = { Image(video,"Synthetic offline feedback fixture",Modifier.fillMaxSize()) })
                        }
                    }
                }
                model.enableRemote(); waitUntil(timeoutMillis = 5_000) { model.remoteEnabled.value && !model.state.value.busy }
                onNodeWithTag("challenge-open").performClick()
                onNodeWithTag("challenge-sounds").performScrollTo().assertIsOn().assertHeightIsAtLeast(48.dp); capture("sounds-on")
                onNodeWithTag("challenge-start").performClick()
                waitUntil(timeoutMillis = 5_000) { opened.get() == 1 }
                vision(rectangle)
                state { copy(phase = ChallengePhase.COUNTDOWN, remainingMillis = 3000, sight = SightPoint(6000,5500)) }
                waitUntil(timeoutMillis = 5_000) { ChallengeCue.COUNTDOWN in cues }
                state { copy(phase = ChallengePhase.PLAYING, stage = 21, score = 900, aimedMarkerId = 11, remainingMillis = 4000,
                    targets = listOf(ChallengeTarget(11),ChallengeTarget(12))) }
                onNodeWithTag("challenge-aimed").assertExists(); capture("aimed")
                mainClock.autoAdvance = false
                state { copy(aimedMarkerId = null, score = 1000, targets = listOf(ChallengeTarget(11,true),ChallengeTarget(12)),
                    hit = ChallengeHit(1,11,rectangle,true,100), hitAgeMillis = 0) }
                mainClock.advanceTimeBy(120)
                onNodeWithTag("challenge-hit").assertTextContains("+100",substring = true); capture("hit")
                mainClock.autoAdvance = true
                waitUntil(timeoutMillis = 5_000) { ChallengeCue.HIT in cues }
                mainClock.autoAdvance = false
                val foreign = rectangle.copy(info = (120 shl 16) or 20)
                vision(foreign)
                state { copy(hit = ChallengeHit(2,20,foreign,false), hitAgeMillis = 0) }
                mainClock.advanceTimeBy(120); capture("unscored")
                mainClock.autoAdvance = true
                val finalTarget = rectangle.copy(info = (120 shl 16) or 12)
                vision(finalTarget)
                state { copy(phase = ChallengePhase.WAVE_CLEAR, remainingMillis = 1000, score = 1100,
                    targets = targets.map { it.copy(completed = true) }, hit = ChallengeHit(3,12,finalTarget,true,100), hitAgeMillis = 0) }; capture("wave-clear")
                waitUntil(timeoutMillis = 5_000) { ChallengeCue.CLEAR in cues }
                state { copy(hitAgeMillis = 1000) }; onNodeWithTag("challenge-hit").assertDoesNotExist()
                runOnIdle { inputs = false }
                waitUntil(timeoutMillis = 5_000) { closed.get() == 1 }
                val count = cues.size
                runOnIdle { inputs = true }
                waitUntil(timeoutMillis = 5_000) { opened.get() == 2 }
                waitForIdle(); assertEquals(count,cues.size,"Returning to the page must not replay an old sound")
                state { ChallengeState() }
                waitUntil(timeoutMillis = 5_000) { closed.get() == 2 }
                onNodeWithTag("challenge-open").performClick()
                onNodeWithTag("challenge-sounds").performScrollTo().performClick().assertIsOff(); capture("sounds-off")
                onNodeWithTag("challenge-start").performClick()
                vision(rectangle)
                state { copy(phase = ChallengePhase.PLAYING, stage = 21, score = 1000, sight = SightPoint(6000,5500), hitAgeMillis = 0,
                    hit = ChallengeHit(4,11,rectangle,true,100), targets = listOf(ChallengeTarget(11,true),ChallengeTarget(12)), remainingMillis = 4000) }
                onNodeWithTag("challenge-hit").assertExists(); capture("muted-hit")
                assertEquals(2,opened.get()); assertEquals(count,cues.size)
            } finally { mainClock.autoAdvance = true; model.close() }
        }
}
