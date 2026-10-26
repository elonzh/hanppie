package cn.elonzh.hanppie.ui.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import org.jetbrains.compose.resources.StringResource

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
internal data class UsageGuideRoute(val topic: cn.elonzh.hanppie.ui.guide.GuideTopic? = null, val step: Int = 0) : WorkbenchRoute {
    override val topLevelIndex = 4
}

@Serializable
internal data object RobotAlbumRoute : WorkbenchRoute { override val topLevelIndex = 5 }

@Serializable
internal data object RobotWifiRoute : WorkbenchRoute { override val topLevelIndex = 4 }


@Serializable
internal data object RobotExtensionRoute : WorkbenchRoute { override val topLevelIndex = 4 }

@Serializable
internal data object RobotArmRoute : WorkbenchRoute { override val topLevelIndex = 4 }

@Serializable
internal data object RobotFirmwareRoute : WorkbenchRoute { override val topLevelIndex = 4 }

@Serializable
internal data object RobotCalibrationRoute : WorkbenchRoute { override val topLevelIndex = 4 }

@Serializable
internal data object RobotAddressingRoute : WorkbenchRoute { override val topLevelIndex = 4 }

@Serializable
internal data object ConnectionGuideRoute : WorkbenchRoute { override val topLevelIndex = 0 }

@Serializable
internal data object DirectConnectionGuideRoute : WorkbenchRoute { override val topLevelIndex = 0 }

@Serializable
internal data object RouterConnectionGuideRoute : WorkbenchRoute { override val topLevelIndex = 0 }

internal data class WorkbenchDestination(val route: WorkbenchRoute, val title: StringResource, val glyph: WorkbenchGlyph)

internal val workbenchDestinations = listOf(
    WorkbenchDestination(RobotRoute, Res.string.robot, WorkbenchGlyph.ROBOT),
    WorkbenchDestination(ScriptRoute, Res.string.script, WorkbenchGlyph.CODE),
    WorkbenchDestination(ChatRoute, Res.string.chat, WorkbenchGlyph.CHAT),
    WorkbenchDestination(RobotAlbumRoute, Res.string.robot_album, WorkbenchGlyph.FOLDER),
    WorkbenchDestination(DebugRoute, Res.string.debug, WorkbenchGlyph.ACTIVITY),
    WorkbenchDestination(SettingsRoute, Res.string.settings, WorkbenchGlyph.SETTINGS),
)

internal fun workbenchRoute(index: Int): WorkbenchRoute = workbenchDestinations.first { it.route.topLevelIndex == index }.route
