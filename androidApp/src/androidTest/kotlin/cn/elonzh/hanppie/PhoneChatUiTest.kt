package cn.elonzh.hanppie

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Local chat behavior only; reply TTS is intentionally absent. */
class PhoneChatUiTest {
    private val localeRule = TestLocaleRule()
    val rule = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(localeRule).around(rule)
    private var activity: AutoCloseable? = null

    @Before fun launchActivity() { activity = launchMainActivityForTest() }
    @After fun closeActivity() { activity?.close(); activity = null }

    @Test fun chatPageAndKeyboard() {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("bottom-navigation").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("对话").performClick()
        rule.onNodeWithText("语音", substring = false).assertDoesNotExist()
        rule.onNodeWithContentDescription("语音输入").assertIsDisplayed().performClick()
        rule.onNodeWithText("使用手机麦克风").assertIsDisplayed()
        rule.onNodeWithText("取消", substring = false).performClick()
        rule.onNodeWithTag("chat-input").assertIsDisplayed().performTextReplacement("你好，憨皮")
        rule.onNodeWithContentDescription("发送").assertIsDisplayed()
        screenshot("chat-keyboard")
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithText("API Key").performScrollTo().assertIsDisplayed()
        screenshot("chat-settings")
        rule.onNodeWithText("语音服务").performScrollTo().performClick()
        rule.onNodeWithText("语音识别").assertIsDisplayed()
        rule.onNodeWithText("系统朗读设置").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        captureActivityScreenshot(name)
    }
}
