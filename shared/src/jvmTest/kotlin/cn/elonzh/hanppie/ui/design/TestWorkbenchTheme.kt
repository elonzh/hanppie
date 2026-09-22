package cn.elonzh.hanppie.ui.design

import androidx.compose.runtime.*
import cn.elonzh.hanppie.ui.robot.scene.LocalRobotSceneRenderer
import cn.elonzh.hanppie.ui.settings.AppearanceController

/** Layout/interaction suites don't need a GPU or Filament's continuously advancing frame clock. */
@Composable
internal fun TestWorkbenchTheme(
    appearance: AppearanceController = remember { AppearanceController() },
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalRobotSceneRenderer provides { _, _, _, _, _, ready, _ ->
        LaunchedEffect(Unit) { ready() }
    }) { WorkbenchTheme(appearance, content) }
}
