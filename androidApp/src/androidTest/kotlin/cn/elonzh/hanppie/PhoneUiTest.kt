package cn.elonzh.hanppie

import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Exercises only local UI; never discovers, connects to, or moves a robot. */
class PhoneUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()


    @Test fun phonePagesAndKeyboard() {
        rule.onNodeWithTag("bottom-navigation").assertIsDisplayed()
        rule.onNodeWithText("搜索设备").assertIsDisplayed()
        screenshot("device")
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithContentDescription("language-en").performClick()
        rule.onNodeWithText("Language", substring=false).assertIsDisplayed()
        screenshot("settings-english")
        rule.onNodeWithContentDescription("language-zh").performClick()
        rule.onNodeWithText("语言", substring=false).assertIsDisplayed()
        rule.onNodeWithText("脚本").performClick()
        rule.onNodeWithTag("script-editor").performTextReplacement("def start():\n    print('Hello S1')")
        rule.onNodeWithText("新脚本 · 未保存").assertExists()
        screenshot("script-keyboard")
        rule.runOnUiThread { rule.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
        rule.onNodeWithText("诊断").performClick()
        rule.onNodeWithText("暂无记录").assertIsDisplayed()
        screenshot("diagnostics")
        rule.onNodeWithText("对话").performClick()
        rule.onNodeWithContentDescription("语音输入").assertIsDisplayed()
        screenshot("chat")
        rule.onNodeWithText("脚本").performClick()
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        rule.onNodeWithText("新脚本 · 未保存").assertExists()
        rule.onNodeWithTag("script-editor").assertIsDisplayed()
        screenshot("script-landscape")
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        // Compose idle does not wait for SurfaceFlinger rotation/page animations.
        Thread.sleep(700)
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val destination = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-$name.png")
        destination.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
