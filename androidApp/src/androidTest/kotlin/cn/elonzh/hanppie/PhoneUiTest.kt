package cn.elonzh.hanppie

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Exercises only local UI; never discovers, connects to, or moves a robot. */
class PhoneUiTest {
    private val localeRule = TestLocaleRule()
    val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(localeRule).around(rule)

    @Test fun phonePagesAndKeyboard() {
        rule.onNodeWithTag("bottom-navigation").assertIsDisplayed()
        rule.onNodeWithText("查找设备").assertIsDisplayed()
        screenshot("device")
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithContentDescription("language-selector").performClick()
        rule.onNodeWithContentDescription("language-en").performClick()
        rule.onNodeWithText("Language", substring=false).assertIsDisplayed()
        screenshot("settings-english")
        rule.onNodeWithContentDescription("language-selector").performClick()
        rule.onNodeWithContentDescription("language-zh").performClick()
        rule.onNodeWithText("语言", substring=false).assertIsDisplayed()
        rule.onNodeWithContentDescription("脚本").performClick()
        rule.onNodeWithTag("script-new").performClick()
        rule.onNodeWithTag("script-editor").performTextReplacement("def start():\n    print('Hello S1')")
        rule.onNodeWithText("新脚本 · 未保存").assertExists()
        screenshot("script-keyboard")
        rule.runOnUiThread { rule.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
        rule.onNodeWithContentDescription("诊断").performClick()
        rule.onNodeWithText("暂无记录").assertIsDisplayed()
        screenshot("diagnostics")
        rule.onNodeWithContentDescription("对话").performClick()
        rule.onNodeWithContentDescription("语音输入").assertIsDisplayed()
        screenshot("chat")
        rule.onNodeWithContentDescription("脚本").performClick()
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        rule.onNodeWithText("新脚本 · 未保存").assertExists()
        rule.onNodeWithTag("script-editor").assertIsDisplayed()
        screenshot("script-landscape")
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test fun systemBackReturnsFromUnmodifiedPresetWithoutDiscardDialog() {
        rule.onNodeWithContentDescription("脚本").performClick()
        rule.onNodeWithContentDescription("script-preset-battery-mood-show").performScrollTo().performClick()
        rule.onNodeWithTag("script-editor").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("script-library").assertIsDisplayed()
        rule.onNodeWithText("替换未保存的脚本？").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        // Compose idle does not wait for SurfaceFlinger rotation/page animations.
        Thread.sleep(700)
        rule.waitForIdle()
        captureActivityScreenshot(name)
    }
}
