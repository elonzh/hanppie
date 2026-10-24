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
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.elonzh.hanppie.robot.challenge.*
import cn.elonzh.hanppie.robot.display.SightPoint
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.display.displayFixtureVideo
import cn.elonzh.hanppie.ui.robot.remote.RemotePage
import cn.elonzh.hanppie.ui.robot.remote.ChallengeCue
import cn.elonzh.hanppie.ui.robot.remote.ChallengeSoundPlayer
import cn.elonzh.hanppie.ui.settings.*
import kotlinx.coroutines.delay
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Real cockpit and ConsoleModel; robot states, video and effects are synthetic. */
@OptIn(ExperimentalTestApi::class)
class ChallengeUiTest {
    @Test fun compactLandscape() = scenario(844, 390, "zh", 1f, false)
    @Test fun mediumDarkLandscape() = scenario(1024, 768, "zh", 1f, true)
    @Test fun desktopLandscape() = scenario(1280, 720, "zh", 1f, false)
    @Test fun largeEnglishLandscape() = scenario(844, 390, "en", 1.4f, false)
    @Test fun traditionalChineseLandscape() = scenario(844, 390, "zh-Hant", 1.4f, true)
    @Test fun japaneseLandscape() = scenario(844, 390, "ja", 1.4f, true)
    @Test fun germanLandscape() = scenario(844, 390, "de", 1.4f, true)
    @Test fun spanishLandscape() = scenario(844, 390, "es", 1.4f, true)
    @Test fun frenchLandscape() = scenario(844, 390, "fr", 1.4f, true)
    @Test fun koreanLandscape() = scenario(844, 390, "ko", 1.4f, true)
    @Test fun russianLandscape() = scenario(844, 390, "ru", 1.4f, true)

    private class InputLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun scenario(width: Int, height: Int, language: String, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, language)
            val robot = RobotSessionFixture()
            robot.cameraState.value = robot.cameraState.value.copy(zoomHundredths = 100)
            var starts = 0; var stops = 0; var dismisses = 0
            robot.challengeStart = { configuration ->
                starts++
                robot.challengeState.value = ChallengeState(phase = ChallengePhase.PREPARING, configuration = configuration,
                    stopConfirmed = false, roundId = starts.toLong())
            }
            robot.challengeStop = { stops++; robot.challengeState.value = robot.challengeState.value.copy(phase = ChallengePhase.STOPPING) }
            robot.challengeDismiss = { dismisses++; robot.challengeState.value = ChallengeState() }
            val model = testConsoleModel(robotRuntime = powerRuntime(listOf(robot)))
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val video = displayFixtureVideo()
            var publishing by mutableStateOf(true)
            lateinit var lifecycle: InputLifecycle
            val prefix = "challenge-$width-$height-$language-${if (dark) "dark" else "light"}"
            fun capture(name: String) {
                waitForIdle()
                val tag = when {
                    onAllNodesWithTag("challenge-result").fetchSemanticsNodes().isNotEmpty() -> "challenge-result"
                    onAllNodesWithTag("challenge-setup").fetchSemanticsNodes().isNotEmpty() -> "challenge-setup"
                    else -> "remote-surface"
                }
                val image = onNode(isRoot() and hasAnyDescendant(hasTestTag(tag))).captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/$prefix-$name.png"); file.parentFile.mkdirs(); ImageIO.write(bitmap, "png", file)
            }
            fun state(change: ChallengeState.() -> ChallengeState) {
                runOnIdle { robot.challengeState.value = robot.challengeState.value.change() }
                waitUntil(timeoutMillis = 5_000) { model.challenge.value == robot.challengeState.value }
                waitForIdle()
            }
            try {
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5_000) { model.state.value.connected && !model.state.value.busy }
                setContent {
                    LaunchedEffect(model) { while (true) { if (publishing) model.videoFrames.publish(model.state.value.connectedAddress, video); delay(50) } }
                    WorkbenchTheme(appearance) {
                        val owner = remember { InputLifecycle() }; SideEffect { lifecycle = owner }
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalDensity provides Density(density.density, scale)) {
                            RemotePage(model, videoContent = { Image(video, "Synthetic validation scene", Modifier.fillMaxSize()) },
                                challengeSoundFactory = { object : ChallengeSoundPlayer {
                                    override fun play(cue: ChallengeCue) = Unit
                                    override fun close() = Unit
                                } })
                        }
                    }
                }
                runOnIdle { lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
                model.enableRemote(); waitUntil(timeoutMillis = 5_000) { model.remoteEnabled.value && !model.state.value.busy }
                onNodeWithTag("challenge-open").assertIsEnabled().assertIsDisplayed(); capture("entry")
                onNodeWithTag("challenge-open").performClick(); capture("practice-setup")
                onNodeWithTag("challenge-mode-target_race").performClick()
                onNodeWithTag("challenge-count-3").performScrollTo().performClick()
                onNodeWithTag("challenge-firing-gel").performScrollTo().performClick(); capture("race-options")
                onNodeWithTag("challenge-start").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { model.challenge.value.phase == ChallengePhase.PREPARING }
                assertFalse(model.gelSelected.value)
                assertEquals(ChallengeFiring.GEL, model.challenge.value.firing); capture("preparing")
                val driveCount = robot.driveCount.get()
                waitUntil(timeoutMillis = 3_000) { robot.driveCount.get() > driveCount + 2 }
                assertTrue(model.remoteInput.value.all { it == 0.0 })
                state { copy(phase = ChallengePhase.COUNTDOWN, remainingMillis = 2000, sight = SightPoint(6000, 5500)) }
                onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.W) }; capture("countdown")
                assertFalse(onNodeWithTag("challenge-hud").fetchSemanticsNode().boundsInRoot.overlaps(
                    onNodeWithTag("remote-crosshair").fetchSemanticsNode().boundsInRoot))
                state { copy(phase = ChallengePhase.PLAYING, remainingMillis = 0, elapsedMillis = 2340,
                    targets = (11..13).map { ChallengeTarget(it, it == 11) }, checkpoints = listOf(ChallengeCheckpoint(11, 1230))) }
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodesWithTag("challenge-targets").fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodes(hasTestTag("remote-surface") and isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle(); assertTrue(model.remoteInput.value.all { it == 0.0 }, "A key held during countdown must be released first")
                onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.W); keyDown(Key.W) }
                waitUntil(timeoutMillis = 3_000) { model.remoteInput.value[0] > 0 }
                onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.W) }; capture("race-playing")
                val shots = robot.gelCount.get()
                model.fireSelected()
                waitUntil(timeoutMillis = 3_000) { robot.gelCount.get() == shots + 1 }
                assertEquals(0, robot.infraredCount.get())
                onNodeWithTag("challenge-stop").performClick(); capture("stopping")
                state { copy(phase = ChallengePhase.RESULT, end = ChallengeEnd.COMPLETED, stopConfirmed = false,
                    elapsedMillis = 12340, targets = targets.map { it.copy(completed = true) },
                    checkpoints = listOf(ChallengeCheckpoint(11, 1230), ChallengeCheckpoint(13, 8000), ChallengeCheckpoint(12, 12340))) }
                onNodeWithTag("challenge-replay").assertDoesNotExist()
                onNodeWithTag("challenge-retry-stop").assertIsDisplayed(); capture("stop-unconfirmed")
                onNodeWithTag("challenge-retry-stop").performClick(); assertEquals(2, stops)
                state { copy(phase = ChallengePhase.RESULT, stopConfirmed = true) }; capture("result")
                onNodeWithTag("challenge-player-name").performScrollTo().performTextInput("Robo")
                onNodeWithTag("challenge-save-score").performScrollTo().performClick()
                onNodeWithTag("challenge-save-score").assertDoesNotExist()
                onNodeWithTag("challenge-ranking-0").performScrollTo().assertTextContains("Robo", substring = true); capture("ranking")
                onNodeWithTag("challenge-replay").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { starts == 2 && model.challenge.value.phase == ChallengePhase.PREPARING }
                runOnIdle { publishing = false }
                waitUntil(timeoutMillis = 5_000) { stops >= 3 }
                runOnIdle { publishing = true }
                state { copy(phase = ChallengePhase.RESULT, end = ChallengeEnd.CANCELLED, stopConfirmed = true) }
                onNodeWithTag("challenge-dismiss").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { !model.challenge.value.ownsRound }
                assertFalse(model.gelSelected.value); assertEquals(1, dismisses)
                onNodeWithTag("challenge-open").performClick()
                onNodeWithTag("challenge-mode-target_practice").performClick()
                onNodeWithTag("challenge-start").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { starts == 3 }
                state { copy(phase = ChallengePhase.PLAYING, stage = 23, wave = 2, score = 2400,
                    remainingMillis = 4300, targets = listOf(ChallengeTarget(12), ChallengeTarget(15))) }
                onNodeWithTag("challenge-targets").assertIsDisplayed(); capture("practice-playing")
                assertFalse(onNodeWithTag("challenge-hud").fetchSemanticsNode().boundsInRoot.overlaps(
                    onNodeWithTag("remote-crosshair").fetchSemanticsNode().boundsInRoot))
                assertFalse(model.gelSelected.value)
            } finally { model.close() }
        }
}
