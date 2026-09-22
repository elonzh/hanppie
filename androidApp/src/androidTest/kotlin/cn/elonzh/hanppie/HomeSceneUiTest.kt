package cn.elonzh.hanppie

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Exercises native scene resources and lifecycle, not compositor pixels; no robot connection. */
class HomeSceneUiTest {
    private val localeRule = TestLocaleRule()
    private val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(localeRule).around(rule)

    @Test(timeout = 60_000) fun landscapePreviewSurvivesNavigationBackgroundAndReverseLandscape() {
        // Filament has a continuous frame loop; manual time lets UI assertions reach idle.
        // Native compositor output is checked separately with the normally running app.
        rule.mainClock.autoAdvance = false
        awaitPreview()
        org.junit.Assert.assertEquals(Configuration.ORIENTATION_LANDSCAPE, rule.activity.resources.configuration.orientation)
        org.junit.Assert.assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, rule.activity.requestedOrientation)
        rule.onNodeWithTag("auto-connect").assertIsDisplayed()
        rule.onNodeWithTag("robot-scene-viewport").performTouchInput {
            swipe(center, center.copy(x = center.x + 80f))
        }
        rule.onNodeWithTag("robot-scene-reset").assertDoesNotExist()
        rule.onNodeWithContentDescription("设置").assertIsDisplayed().performClick()
        rule.waitUntil(10_000) {
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodesWithTag("robot-scene").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithContentDescription("设备").performClick()
        awaitPreview()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitPreview()
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        awaitPreview()
        settleFrames()
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
    }

    private fun settleFrames() {
        repeat(30) { rule.mainClock.advanceTimeByFrame(); Thread.sleep(16) }
    }

    private fun awaitPreview() {
        rule.waitUntil(30_000) {
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "外观预览 · 连接后查看机器人状态")).fetchSemanticsNodes().isNotEmpty()
        }
        repeat(6) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag("robot-scene-viewport").assertIsDisplayed()
        settleFrames()
    }
}
