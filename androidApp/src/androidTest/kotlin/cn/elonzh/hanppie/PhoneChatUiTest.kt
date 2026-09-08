package cn.elonzh.hanppie

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Launch through shell on HyperOS, where instrumentation-originated activity launches can stall. */
class PhoneChatUiTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun chatPageAndKeyboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cn.elonzh.hanppie/.MainActivity"
        )).use { it.readBytes() }
        rule.waitUntil(15000) { rule.onAllNodesWithText("对话").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("对话").performClick()
        rule.onNodeWithText("语音", substring = false).assertDoesNotExist()
        rule.onNodeWithContentDescription("语音输入").assertIsDisplayed().performClick()
        rule.onNodeWithText("使用手机麦克风").assertIsDisplayed()
        rule.onNodeWithText("取消", substring = false).performClick()
        rule.onNodeWithTag("chat-input").assertIsDisplayed().performTextReplacement("你好，憨皮")
        rule.onNodeWithContentDescription("发送").assertIsDisplayed()
        screenshot("chat-keyboard")
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("API Key").assertIsDisplayed()
        screenshot("chat-settings")
        rule.onNodeWithText("语音服务").performClick()
        rule.onNodeWithText("系统朗读设置").performClick()
        rule.waitUntil(10000) {
            fun contains(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.text?.toString() == "文字转语音设置") return true
                return (0 until node.childCount).any { contains(node.getChild(it)) }
            }
            contains(instrumentation.uiAutomation.rootInActiveWindow)
        }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val destination = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-$name.png")
        destination.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
