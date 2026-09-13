package cn.elonzh.hanppie.ui.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface WorkbenchRoute : NavKey {
    val topLevelIndex: Int
}

@Serializable
internal data object RobotRoute : WorkbenchRoute { override val topLevelIndex = 0 }

@Serializable
internal data object ScriptRoute : WorkbenchRoute { override val topLevelIndex = 1 }

@Serializable
internal data object DebugRoute : WorkbenchRoute { override val topLevelIndex = 2 }

@Serializable
internal data object ChatRoute : WorkbenchRoute { override val topLevelIndex = 3 }

@Serializable
internal data object SettingsRoute : WorkbenchRoute { override val topLevelIndex = 4 }

@Serializable
internal data object CockpitRoute : WorkbenchRoute { override val topLevelIndex = 0 }

internal fun workbenchRoute(index: Int): WorkbenchRoute = when (index) {
    0 -> RobotRoute
    1 -> ScriptRoute
    2 -> DebugRoute
    3 -> ChatRoute
    4 -> SettingsRoute
    else -> error("Unknown top-level destination: $index")
}
