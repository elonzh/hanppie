package cn.elonzh.hanppie.ui.chat

import ai.koog.prompt.message.MessagePart
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
import cn.elonzh.hanppie.ui.app.MemoryScriptRepository
import cn.elonzh.hanppie.ui.app.Console
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.*
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import cn.elonzh.hanppie.ui.scripts.StoredScript
import cn.elonzh.hanppie.ui.settings.*
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.coroutines.runBlocking
import javax.imageio.ImageIO
import kotlin.test.*

/** Real workbench history and script handoff; no model service, robot, or native speech. */
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

    @Test fun scriptHandoffCompactLandscape() = scriptHandoff(844, 390)
    @Test fun scriptHandoffTabletDark() = scriptHandoff(1024, 768, dark = true)
    @Test fun scriptHandoffDesktop() = scriptHandoff(1280, 720, language = "en")
    @Test fun scriptHandoffLargeEnglish() = scriptHandoff(844, 390, 1.4f, dark = true, language = "en")

    private fun scriptHandoff(width: Int, height: Int, scale: Float = 1f, dark: Boolean = false, language: String = "zh") =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, language)
            val script = StoredScript("handoff-script", "Inspection / 巡检", "def start(): pass", 1, 1)
            val model = testConsoleModel(scriptRepository = MemoryScriptRepository(listOf(script)))
            val document = mutableStateOf(EditorDocument(source = "unsaved work", savedSource = ""))
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            fun picture(name: String, tag: String = "chat-surface") {
                val subject = if (name == "unsaved") hasText(tr(Res.string.discard_changes)) else hasTestTag(tag)
                val image = onNode(isRoot() and hasAnyDescendant(subject)).captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/chat-script-$width-$height-$scale-$language-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(out, "png", file)
            }
            try {
                setContent { TestWorkbenchTheme(appearance) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { Console(model, document) }
                } }
                waitUntil(timeoutMillis = 5000) { model.chat.state.value.ready && !model.scriptLibrary.state.value.loading }
                runOnIdle { model.chat.state.value = model.chat.state.value.copy(lines = listOf(ChatLine(ChatRole.TOOL,
                    toolCall = MessagePart.Tool.Call("list", "list_lab_scripts", "{}"),
                    toolResult = MessagePart.Tool.Result("list", "list_lab_scripts",
                        """{"scripts":[{"id":"${script.id}","name":"${script.name}","sourceLength":17}]}""")))) }
                runBlocking {
                    model.scriptLibrary.rename(script.id, "Renamed / 已重命名")
                    model.scriptLibrary.create(script.name, "def start(): print('another script')")
                }
                onNodeWithTag("navigate-3").performClick()
                picture("list")
                onNodeWithTag("tool-message-header-list_lab_scripts").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                onNodeWithTag("tool-open-script-${script.id}").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
                onNodeWithText(tr(Res.string.discard_changes)).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                picture("unsaved", "chat-surface")
                onNodeWithText(tr(Res.string.back)).assertHeightIsAtLeast(48.dp).performClick()
                assertEquals("unsaved work", document.value.source)
                assertTrue(document.value.dirty)
                onNodeWithTag("tool-open-script-${script.id}").performClick()
                onNodeWithText(tr(Res.string.discard_changes)).performClick()
                onNodeWithTag("script-editor").assertIsDisplayed()
                assertEquals(script.id, document.value.scriptId)
                assertEquals(script.source, document.value.source)
                assertEquals("Renamed / 已重命名", document.value.displayName)
                picture("opened", "script-editor")
                onNodeWithContentDescription(tr(Res.string.back_to_chat)).performClick()
                onNodeWithTag("tool-open-script-${script.id}").performClick()
                onNodeWithTag("script-editor").assertIsDisplayed()
                runBlocking { model.scriptLibrary.delete(script.id) }
                onNodeWithTag("navigate-3").performClick()
                onNodeWithTag("chat-input").performTextInput("Retained chat draft")
                onNodeWithTag("tool-open-script-${script.id}").performClick()
                onNodeWithText(tr(Res.string.chat_script_unavailable)).assertIsDisplayed()
                picture("unavailable-initial")
                for (choice in AppLanguage.entries) {
                    runOnIdle { Localization.select(choice.preference) }
                    onNodeWithText(tr(Res.string.chat_script_unavailable)).assertIsDisplayed()
                    onNodeWithTag("chat-input").assertIsDisplayed().assertTextContains("Retained chat draft")
                    assertEquals(script.source, document.value.source)
                    picture("unavailable-${choice.preference}")
                }
                runBlocking { model.scriptLibrary.create("Recovered / 恢复", "def start(): print('recovered')") }
                val recovered = model.scriptLibrary.state.value.scripts.first { it.source.contains("recovered") }
                runOnIdle { model.chat.state.value = model.chat.state.value.copy(lines = listOf(ChatLine(ChatRole.TOOL,
                    toolCall = MessagePart.Tool.Call("retry", "list_lab_scripts", "{}"),
                    toolResult = MessagePart.Tool.Result("retry", "list_lab_scripts",
                        """{"scripts":[{"id":"${recovered.id}","name":"${recovered.name}","sourceLength":36}]}""")))) }
                onNodeWithTag("tool-open-script-${recovered.id}").performClick()
                onNodeWithTag("script-editor").assertIsDisplayed()
                assertEquals(recovered.id, document.value.scriptId)
                assertEquals(recovered.source, document.value.source)
                onNodeWithContentDescription(tr(Res.string.back_to_chat)).performClick()
                onNodeWithText(tr(Res.string.chat_script_unavailable)).assertDoesNotExist()
                onNodeWithTag("chat-input").assertTextContains("Retained chat draft")
                onNodeWithTag("tool-open-script-${recovered.id}").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                assertFalse(model.state.value.connected)
                assertFalse(model.remoteEnabled.value)
                picture("recovered")
            } finally { model.close(); Localization.initialize("zh", null) }
        }

}
