package cn.elonzh.hanppie.ui.chat

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.agent.runtime.SessionHistory
import cn.elonzh.hanppie.agent.runtime.TestSessionHistory
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.Console
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.*
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import cn.elonzh.hanppie.ui.settings.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Real workbench and a failed local history; no model, robot, or native speech service. */
@OptIn(ExperimentalTestApi::class)
class ChatFeedbackUiTest {
    @Test fun compactLandscape() = feedback(844, 390)
    @Test fun tabletDark() = feedback(1024, 768, dark = true)
    @Test fun desktopLandscape() = feedback(1280, 720)
    @Test fun largeSupportedLanguages() = feedback(844, 390, 1.4f, dark = true)

    private fun feedback(width: Int, height: Int, scale: Float = 1f, dark: Boolean = false) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize("zh", "zh")
            val failure = IllegalStateException("offline history EIO / 原始诊断 / détails\n".repeat(40))
            val history = object : SessionHistory by TestSessionHistory() {
                override suspend fun initialize(): Unit = throw failure
            }
            val model = testConsoleModel(sessionHistory = history)
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            fun picture(name: String) {
                val image = onNode(isRoot() and hasAnyDescendant(hasTestTag("chat-surface"))).captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/chat-feedback-$width-$height-$scale-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(out, "png", file)
            }
            try {
                setContent { TestWorkbenchTheme(appearance) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        Console(model, remember { mutableStateOf(EditorDocument()) })
                    }
                } }
                onNodeWithTag("navigate-3").performClick()
                waitUntil(timeoutMillis = 5_000) { model.chat.state.value.ready && model.chat.state.value.error != null }
                val retainedError = model.chat.state.value.error
                val languages = AppLanguage.entries
                for (language in languages) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithText(tr(Res.string.conversation_history_could_not_be_loaded, failure.toString()), substring = true).assertExists()
                    assertSame(retainedError, model.chat.state.value.error)
                    onNodeWithTag("chat-input").assertIsDisplayed().assertIsEnabled()
                    onNodeWithTag("chat-error-close").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    assertTrue(onNodeWithTag("chat-error").fetchSemanticsNode().boundsInRoot.height <= 80.5f)
                    picture(language.preference)
                }
                onNodeWithTag("chat-error").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 500f) }
                onNodeWithTag("chat-input").performTextInput("Draft survives dismissal")
                onNodeWithTag("chat-error-close").performClick()
                assertNull(model.chat.state.value.error)
                assertEquals(ChatPhase.IDLE, model.chat.state.value.phase)
                assertEquals("Draft survives dismissal", model.chat.state.value.draft)
                onNodeWithTag("chat-error").assertDoesNotExist()
                onNodeWithTag("chat-input").assertIsDisplayed().assertTextContains("Draft survives dismissal")
                picture("dismissed")
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
