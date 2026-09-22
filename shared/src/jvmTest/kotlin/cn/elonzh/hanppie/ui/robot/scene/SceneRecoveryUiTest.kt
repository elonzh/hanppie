package cn.elonzh.hanppie.ui.robot.scene

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.device.DevicePage
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SceneRecoveryUiTest {
    @Test fun rendererFailureLeavesConnectionAvailableAndRetryRecreatesRenderer() = runDesktopComposeUiTest(width = 900, height = 700) {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        var attempts = 0
        try {
            setContent {
                CompositionLocalProvider(LocalRobotSceneRenderer provides { _, _, _, _, _, ready, error ->
                    LaunchedEffect(Unit) { if (attempts++ == 0) error() else ready() }
                }) {
                    WorkbenchTheme { DevicePage(model, model.state.value, false, Modifier.fillMaxSize(), {}, {}) }
                }
            }
            onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "三维预览暂不可用 · 仍可连接机器人")).assertIsDisplayed()
            onNodeWithTag("auto-connect").assertIsEnabled()
            onNodeWithTag("connection-status").assertIsEnabled()
            onNodeWithTag("robot-scene-reset").assertDoesNotExist()
            onNodeWithText("重新加载").performClick()
            onNodeWithTag("robot-scene-reset").assertDoesNotExist()
            onNodeWithText("重新加载").assertDoesNotExist()
            assertEquals(2, attempts)
        } finally { model.close() }
    }
}
