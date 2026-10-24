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
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.challenge.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.*
import cn.elonzh.hanppie.ui.robot.display.displayFixtureVideo
import cn.elonzh.hanppie.ui.robot.remote.*
import cn.elonzh.hanppie.ui.settings.*
import kotlinx.coroutines.delay
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ChallengeRecoveryUiTest {
    @Test fun compactLandscape() = recovery(844, 390)
    @Test fun tabletDark() = recovery(1024, 600, dark = true)
    @Test fun desktopLandscape() = recovery(1280, 720)
    @Test fun largeNineLanguages() = recovery(844, 390, scale = 1.4f, nineLanguages = true)

    private class InputLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    }

    private fun recovery(width: Int, height: Int, scale: Float = 1f, dark: Boolean = false, nineLanguages: Boolean = false) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize("en", "en")
            val robot = RobotSessionFixture()
            var attempts = 0
            var rounds = 0L
            var reject = true
            robot.challengeStart = { configuration ->
                attempts++
                if (reject) error("OFFLINE start failure / 原始诊断")
                robot.challengeState.value = ChallengeState(phase = ChallengePhase.PREPARING,
                    configuration = configuration, roundId = ++rounds, stopConfirmed = false)
            }
            robot.challengeDismiss = { robot.challengeState.value = ChallengeState() }
            val model = testConsoleModel(robotRuntime = powerRuntime(listOf(robot)))
            val video = displayFixtureVideo()
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val languages = if (nineLanguages) AppLanguage.entries else listOf(AppLanguage.ENGLISH, AppLanguage.GERMAN)
            fun capture(name: String) {
                val tag = if (onAllNodesWithTag("challenge-result").fetchSemanticsNodes().isNotEmpty()) "challenge-result" else "challenge-setup"
                val bitmap = onNode(isRoot() and hasAnyDescendant(hasTestTag(tag))).captureToImage()
                val pixels = IntArray(bitmap.width * bitmap.height); bitmap.readPixels(pixels)
                val output = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
                val file = File("build/reports/ui/challenge-recovery-$width-$height-$scale-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(output, "png", file)
            }
            fun complete(score: Int) {
                runOnIdle { robot.challengeState.value = robot.challengeState.value.copy(phase = ChallengePhase.RESULT,
                    end = ChallengeEnd.TIME_EXPIRED, score = score, stopConfirmed = true) }
                waitUntil(timeoutMillis = 5000) { model.challenge.value.phase == ChallengePhase.RESULT }
                waitForIdle()
            }
            try {
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                setContent {
                    LaunchedEffect(model) { while (true) { model.videoFrames.publish(model.state.value.connectedAddress, video); delay(50) } }
                    TestWorkbenchTheme(appearance) {
                        val owner = remember { InputLifecycle() }
                        CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalDensity provides Density(1f, scale)) {
                            RemotePage(model, videoContent = { Image(video, "Synthetic challenge recovery scene", Modifier.fillMaxSize()) },
                                challengeSoundFactory = { object : ChallengeSoundPlayer {
                                    override fun play(cue: ChallengeCue) {}
                                    override fun close() {}
                                } })
                        }
                    }
                }
                model.enableRemote()
                waitUntil(timeoutMillis = 5000) { model.remoteEnabled.value && !model.state.value.busy }
                onNodeWithTag("challenge-open").assertIsEnabled().performClick()
                onNodeWithTag("challenge-start").performClick()
                onNodeWithTag("challenge-start-error").assertTextEquals(tr(Res.string.challenge_not_ready))
                capture("baseline-error")
                for (language in languages) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithTag("challenge-start-error").assertTextEquals(tr(Res.string.challenge_not_ready))
                    onNodeWithTag("challenge-start").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    onNodeWithTag("challenge-setup-close").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    capture("${language.preference}-start-error")
                }
                assertEquals(1, attempts)
                assertTrue(model.remoteInput.value.all { it == 0.0 }); assertFalse(model.firing.value)
                onNodeWithTag("challenge-setup-close").performClick()
                onNodeWithTag("challenge-open").performClick()
                onNodeWithTag("challenge-start-error").assertDoesNotExist()
                runOnIdle { reject = false }
                onNodeWithTag("challenge-start").performClick()
                waitUntil(timeoutMillis = 5000) { model.challenge.value.phase == ChallengePhase.PREPARING }
                complete(1000)
                onNodeWithTag("challenge-save-score").performScrollTo().performClick()
                onNodeWithTag("challenge-save-score").assertDoesNotExist()
                for (language in languages) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithTag("challenge-ranking-0").performScrollTo().assertTextContains(tr(Res.string.challenge_default_player), substring = true)
                    capture("${language.preference}-anonymous")
                }
                runOnIdle { reject = true }
                onNodeWithTag("challenge-replay").performClick()
                for (language in languages) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithTag("challenge-replay-error").assertTextEquals(tr(Res.string.challenge_not_ready))
                    onNodeWithTag("challenge-replay").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    onNodeWithTag("challenge-dismiss").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    capture("${language.preference}-replay-error")
                }
                assertEquals(3, attempts)
                runOnIdle { reject = false }
                onNodeWithTag("challenge-replay").performClick()
                waitUntil(timeoutMillis = 5000) { rounds == 2L && model.challenge.value.phase == ChallengePhase.PREPARING }
                complete(2000)
                onNodeWithTag("challenge-player-name").performScrollTo().performTextInput("Player 1")
                onNodeWithTag("challenge-save-score").performScrollTo().performClick()
                for (language in languages) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithTag("challenge-ranking-0").performScrollTo().assertTextContains("Player 1", substring = true)
                    onNodeWithTag("challenge-ranking-1").performScrollTo().assertTextContains(tr(Res.string.challenge_default_player), substring = true)
                    capture("${language.preference}-user-name")
                }
                assertEquals(4, attempts)
                assertEquals(0, robot.infraredCount.get()); assertEquals(0, robot.gelCount.get())
                assertTrue(model.remoteInput.value.all { it == 0.0 }); assertFalse(model.firing.value)
                onNodeWithTag("challenge-dismiss").performClick()
                waitUntil(timeoutMillis = 5000) { !model.challenge.value.ownsRound }
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
