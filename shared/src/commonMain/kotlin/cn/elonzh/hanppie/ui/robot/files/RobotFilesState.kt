package cn.elonzh.hanppie.ui.robot.files

import cn.elonzh.hanppie.robot.files.RobotFileEntry
import cn.elonzh.hanppie.ui.i18n.UiText

internal data class RobotFilesState(
    val path: String = "/",
    val entries: List<RobotFileEntry> = emptyList(),
    val selectedPath: String? = null,
    val busy: Boolean = false,
    val operation: UiText? = null,
    val message: UiText? = null,
    val error: String? = null,
) {
    val selected: RobotFileEntry? get() = entries.firstOrNull { it.path == selectedPath }
}
