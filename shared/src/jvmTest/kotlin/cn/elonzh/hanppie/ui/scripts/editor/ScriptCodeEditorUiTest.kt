package cn.elonzh.hanppie.ui.scripts.editor

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import cn.elonzh.hanppie.ui.settings.AppearanceController
import cn.elonzh.hanppie.ui.settings.AppearanceSettings
import cn.elonzh.hanppie.ui.settings.NightMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.app.Console
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ScriptCodeEditorUiTest {
    @Test fun scriptAndChatUseExtraWindowWidthAndKeepBottomSpace() = runDesktopComposeUiTest(width = 1600, height = 760) {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val width = mutableStateOf(1100.dp)
        val document = mutableStateOf(EditorDocument(source = "def start():\n    pass", title = "编辑测试"))
        try {
            setContent { WorkbenchTheme { Box(Modifier.width(width.value).fillMaxHeight()) { Console(model, document) } } }
            onNodeWithContentDescription("脚本").performClick()
            val narrowEditor = onNodeWithTag("script-editor").fetchSemanticsNode().boundsInRoot.width
            runOnIdle { width.value = 1500.dp }
            waitForIdle()
            assertTrue(onNodeWithTag("script-editor").fetchSemanticsNode().boundsInRoot.width >= narrowEditor + 390f)
            assertTrue(onNodeWithTag("script-console").fetchSemanticsNode().boundsInRoot.bottom <= 740f)
            onNodeWithTag("editor-undo").assertDoesNotExist()
            snapshot("script-expanded-layout", onRoot())
            onNodeWithContentDescription("对话").performClick()
            val wideChat = onNodeWithTag("chat-input").fetchSemanticsNode().boundsInRoot
            assertTrue(wideChat.bottom <= 740f)
            snapshot("chat-expanded-layout", onRoot())
            runOnIdle { width.value = 1100.dp }
            waitForIdle()
            assertTrue(wideChat.width >= onNodeWithTag("chat-input").fetchSemanticsNode().boundsInRoot.width + 390f)
        } finally { model.close() }
    }

    @Test fun keyboardTouchSearchAndExternalEditsShareOneDocument() = runDesktopComposeUiTest(width = 900, height = 640) {
        Localization.initialize("zh", null)
        val field = mutableStateOf(TextFieldValue("def start():\n    pass", TextRange(21)))
        var saved = false
        setContent { WorkbenchTheme { ScriptCodeEditor(field, true, { field.value = it }, { saved = true }, Modifier.fillMaxSize()) } }
        onNodeWithTag("script-editor").performClick().performTextReplacement("def start():\n    # 中文输入\n    pass")
        onNodeWithTag("script-editor").performClick().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Z); keyUp(Key.CtrlLeft) }
        runOnIdle { assertEquals("def start():\n    pass", field.value.text) }
        onNodeWithTag("script-editor").performClick().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Y); keyUp(Key.CtrlLeft) }
        runOnIdle { assertContains(field.value.text, "中文输入") }
        onNodeWithTag("script-editor").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.S); keyUp(Key.CtrlLeft) }
        runOnIdle { assertTrue(saved) }
        onNodeWithTag("script-editor").performClick().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.F); keyUp(Key.CtrlLeft) }
        onNodeWithTag("editor-query").performTextInput("pass")
        onNodeWithText("下一个").performClick()
        onNodeWithTag("editor-replacement").performTextInput("log_ctrl.print_msg('你好')")
        onNodeWithTag("editor-replace").performClick()
        runOnIdle { assertContains(field.value.text, "print_msg('你好')") }
        onNodeWithText("关闭").performClick()
        runOnIdle { field.value = field.value.copy(text = field.value.text + "\n# audio") }
        waitForIdle()
        onNodeWithTag("script-editor").performClick().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Z); keyUp(Key.CtrlLeft) }
        runOnIdle { assertFalse(field.value.text.contains("# audio")) }
        snapshot("code-editor-desktop", onRoot())
    }

    @Test fun darkPhoneSearchRemainsUsableWithLargeText() = runDesktopComposeUiTest(width = 393, height = 650) {
        Localization.initialize("zh", null)
        val field = mutableStateOf(TextFieldValue("def start():\n    # 中文注释\n    log_ctrl.print_msg('你好')\n"))
        val appearance = AppearanceController(AppearanceSettings(NightMode.DARK))
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WorkbenchTheme(appearance) { ScriptCodeEditor(field, true, { field.value = it }, {}, Modifier.fillMaxSize().padding(16.dp)) }
            }
        }
        onNodeWithTag("script-editor").assertIsDisplayed()
        snapshot("code-editor-dark-phone", onRoot())
        onNodeWithTag("script-editor").performClick().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.F); keyUp(Key.CtrlLeft) }
        onNodeWithTag("editor-query").performTextInput("你好")
        onNodeWithText("下一个").performClick()
        runOnIdle { assertEquals("你好", field.value.text.substring(field.value.selection.min, field.value.selection.max)) }
        onNodeWithText("关闭").assertIsDisplayed()
        snapshot("code-editor-search-phone", onAllNodes(isRoot()).onLast())
    }

    private fun snapshot(name: String, node: SemanticsNodeInteraction) {
        val image = node.captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        bitmap.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val output = File("build/reports/ui/$name.png")
        output.parentFile.mkdirs()
        ImageIO.write(bitmap, "png", output)
    }

    @Test fun phoneLayoutKeepsKeyboardEditingAndLongLinesAccessible() = runDesktopComposeUiTest(width = 393, height = 650) {
        Localization.initialize("en", null)
        val field = mutableStateOf(TextFieldValue("def start():\n    # A long line " + "x".repeat(160) + "\n    pass"))
        val enabled = mutableStateOf(true)
        setContent { WorkbenchTheme { ScriptCodeEditor(field, enabled.value, { field.value = it }, {}, Modifier.fillMaxSize().padding(16.dp)) } }
        onNodeWithTag("editor-find").assertDoesNotExist()
        onNodeWithTag("script-editor").assertIsDisplayed()
        onNodeWithTag("script-editor").performClick().performTextInputSelection(TextRange.Zero)
        onNodeWithTag("script-editor").performKeyInput { pressKey(Key.Tab) }
        runOnIdle { assertTrue(field.value.text.startsWith("    def")) }
        onNodeWithTag("script-editor").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
        runOnIdle { assertTrue(field.value.text.startsWith("def")); enabled.value = false }
        onNodeWithTag("editor-indent").assertDoesNotExist()
        onNodeWithTag("script-editor").assertIsNotEnabled()
    }
}
