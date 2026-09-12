package cn.elonzh.hanppie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.RobotFileEntry
import cn.elonzh.hanppie.robot.RobotFileKind
import cn.elonzh.hanppie.robot.RobotFileSystem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class RobotFileFilter { ALL, AUDIO, PROGRAM }

private val audioExtensions = setOf("aac", "flac", "m4a", "mp3", "ogg", "opus", "wav")
private val programExtensions = setOf("dsp", "py")

internal fun filteredRobotFiles(
    entries: List<RobotFileEntry>,
    query: String,
    filter: RobotFileFilter,
): List<RobotFileEntry> {
    val needle = query.trim()
    return entries.filter { entry ->
        val queryMatches = needle.isEmpty() || entry.name.contains(needle, ignoreCase = true)
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        queryMatches && when (filter) {
            RobotFileFilter.ALL -> true
            RobotFileFilter.AUDIO -> entry.isDirectory || extension in audioExtensions
            RobotFileFilter.PROGRAM -> entry.isDirectory || extension in programExtensions
        }
    }
}

internal fun formatRobotFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = -1
    do { value /= 1024.0; unit++ } while (value >= 1024 && unit < units.lastIndex)
    return if (value >= 10) "%.0f %s".format(Locale.ROOT, value, units[unit])
    else "%.1f %s".format(Locale.ROOT, value, units[unit])
}

@Composable
internal fun RobotFilesPage(
    model: ConsoleModel,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onUpload: ((String) -> Unit)? = null,
    onDownload: ((RobotFileEntry) -> Unit)? = null,
    onOpen: ((RobotFileEntry) -> Unit)? = null,
) {
    val state by model.robotFilesState.collectAsState()
    var query by remember(state.path) { mutableStateOf("") }
    var filter by remember { mutableStateOf(RobotFileFilter.ALL) }
    var createFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var renameEntry by remember { mutableStateOf<RobotFileEntry?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteEntry by remember { mutableStateOf<RobotFileEntry?>(null) }
    val visible = remember(state.entries, query, filter) { filteredRobotFiles(state.entries, query, filter) }
    val nameValid: (String) -> Boolean = { runCatching { RobotFileSystem.requireName(it) }.isSuccess }

    WorkbenchDialog(show = createFolder, onDismissRequest = { createFolder = false }, title = tr(Res.string.new_folder)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(folderName, { folderName = it }, label = tr(Res.string.folder_name), singleLine = true,
                modifier = Modifier.testTag("robot-folder-name"))
            if (folderName.isNotEmpty() && !nameValid(folderName)) Text(tr(Res.string.robot_file_name_invalid),
                color = MiuixTheme.colorScheme.error, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ createFolder = false }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(Res.string.cancel)) }
                Button({ model.createRobotDirectory(folderName); createFolder = false; folderName = "" },
                    Modifier.weight(1f).heightIn(min = 48.dp), enabled = nameValid(folderName),
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.create)) }
            }
        }
    }

    WorkbenchDialog(show = renameEntry != null, onDismissRequest = { renameEntry = null }, title = tr(Res.string.rename_file)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(renameValue, { renameValue = it }, label = tr(Res.string.rename), singleLine = true,
                modifier = Modifier.testTag("robot-rename-value"))
            if (renameValue.isNotEmpty() && !nameValid(renameValue)) Text(tr(Res.string.robot_file_name_invalid),
                color = MiuixTheme.colorScheme.error, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ renameEntry = null }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(Res.string.cancel)) }
                Button({ renameEntry?.let { model.renameRobotFile(it, renameValue) }; renameEntry = null },
                    Modifier.weight(1f).heightIn(min = 48.dp), enabled = nameValid(renameValue),
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.rename)) }
            }
        }
    }

    WorkbenchDialog(show = deleteEntry != null, onDismissRequest = { deleteEntry = null },
        title = tr(Res.string.delete_robot_file_question),
        summary = deleteEntry?.let { tr(Res.string.delete_robot_file_summary, it.name) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ deleteEntry = null }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(Res.string.cancel)) }
            Button({ deleteEntry?.let(model::deleteRobotFile); deleteEntry = null }, Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.errorContainer,
                    contentColor = MiuixTheme.colorScheme.onErrorContainer)) { Text(tr(Res.string.delete)) }
        }
    }

    Column(modifier.fillMaxSize().testTag("robot-files-page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkbenchIconButton(tr(Res.string.back), WorkbenchGlyph.BACK, model::openRobotParentDirectory,
                    enabled = state.path != "/" && !state.busy, tag = "robot-files-up")
                Text(state.path, Modifier.weight(1f).testTag("robot-files-path"), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold)
                WorkbenchIconButton(tr(Res.string.refresh), WorkbenchGlyph.REFRESH, model::refreshRobotFiles,
                    enabled = !state.busy, tag = "robot-files-refresh")
                WorkbenchIconButton(tr(Res.string.upload_file), WorkbenchGlyph.UPLOAD,
                    { onUpload?.invoke(state.path) }, enabled = !state.busy && onUpload != null, tag = "robot-files-upload")
                WorkbenchIconButton(tr(Res.string.new_folder), WorkbenchGlyph.ADD,
                    { folderName = ""; createFolder = true }, enabled = !state.busy, tag = "robot-files-new-folder")
            }
        }

        Text(tr(Res.string.ftp_data_boundary), color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
        TextField(query, { query = it }, label = tr(Res.string.search_files), singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("robot-files-search"))
        TabRowWithContour(
            tabs = listOf(tr(Res.string.all_files), tr(Res.string.audio_files), tr(Res.string.program_files)),
            selectedTabIndex = filter.ordinal,
            onTabSelected = { filter = RobotFileFilter.entries[it] },
            modifier = Modifier.fillMaxWidth().testTag("robot-files-filter"),
            minWidth = if (compact) 72.dp else 96.dp,
            maxWidth = if (compact) 120.dp else 160.dp,
            height = HanppieDesignTokens.TouchTarget,
            itemSpacing = 4.dp,
            colors = TabRowDefaults.tabRowColors(selectedBackgroundColor = MiuixTheme.colorScheme.primaryVariant,
                selectedContentColor = MiuixTheme.colorScheme.onPrimaryVariant),
        )
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        state.operation?.let { Text(it.resolve(), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        state.error?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.error, modifier = Modifier.testTag("robot-files-error")) }
        state.message?.let { Text(it.resolve(), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.testTag("robot-files-message")) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                if (visible.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        WorkbenchIcon(WorkbenchGlyph.FILE, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(36.dp))
                        Text(tr(if (state.entries.isEmpty()) Res.string.no_robot_files else Res.string.no_matching_robot_files),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
                    }
                }
                items(visible, key = { it.path }) { entry ->
                    RobotFileRow(entry, state.selectedPath == entry.path, state.busy,
                        onOpen = { model.openRobotDirectory(entry) },
                        onSelect = { model.selectRobotFile(entry) },
                        onQuickOpen = { onOpen?.invoke(entry) },
                        quickOpenAvailable = onOpen != null)
                }
            }
            DesktopListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("robot-files-scrollbar"))
        }

        state.selected?.let { selected ->
            RobotFileActions(selected, state.busy, onDownload, onOpen,
                onRename = { renameValue = selected.name; renameEntry = selected },
                onDelete = { deleteEntry = selected })
        }
    }
}

@Composable
private fun RobotFileRow(entry: RobotFileEntry, selected: Boolean, busy: Boolean,
    onOpen: () -> Unit, onSelect: () -> Unit, onQuickOpen: () -> Unit, quickOpenAvailable: Boolean) {
    val colors = MiuixTheme.colorScheme
    val click = if (entry.isDirectory) onOpen else onSelect
    val action = if (entry.isDirectory) tr(Res.string.open_folder_value, entry.name) else tr(Res.string.select_file_value, entry.name)
    Card(Modifier.fillMaxWidth().semantics { this.selected = selected }, insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = if (selected) colors.primaryVariant else colors.surfaceContainer)) {
        BasicComponent(
            modifier = Modifier.fillMaxWidth().testTag("robot-file-${entry.path}"),
            startAction = { WorkbenchIcon(if (entry.isDirectory) WorkbenchGlyph.FOLDER else WorkbenchGlyph.FILE,
                colors.onSurfaceVariantSummary, Modifier.padding(end = 8.dp)) },
            endActions = {
                if (entry.isDirectory) {
                    WorkbenchIconButton(tr(Res.string.select_file_value, entry.name), WorkbenchGlyph.MORE,
                        onSelect, enabled = !busy, modifier = Modifier.padding(start = 4.dp))
                } else if (entry.isRegularFile) {
                    WorkbenchIconButton(tr(Res.string.open_file_value, entry.name), WorkbenchGlyph.OPEN,
                        onQuickOpen, enabled = !busy && quickOpenAvailable, tag = "robot-file-quick-open",
                        modifier = Modifier.padding(start = 4.dp))
                }
            },
            onClick = click,
            onClickLabel = action,
            role = Role.Button,
            enabled = !busy,
        ) {
            Text(entry.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(fileMetadata(entry), fontSize = 11.sp, color = colors.onSurfaceVariantSummary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (entry.isProtected) Text(tr(Res.string.reserved_lab_slot), fontSize = 11.sp,
                color = colors.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RobotFileActions(entry: RobotFileEntry, busy: Boolean, onDownload: ((RobotFileEntry) -> Unit)?,
    onOpen: ((RobotFileEntry) -> Unit)?,
    onRename: () -> Unit, onDelete: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Card(Modifier.fillMaxWidth().testTag("robot-file-actions"), insideMargin = PaddingValues(14.dp),
        colors = CardDefaults.defaultColors(color = colors.surfaceContainerHigh)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (entry.isProtected) Text(tr(Res.string.reserved_lab_slot_summary), fontSize = 11.sp,
                        color = colors.onSurfaceVariantSummary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entry.isRegularFile) WorkbenchIconButton(tr(Res.string.open_file_value, entry.name),
                        WorkbenchGlyph.OPEN, { onOpen?.invoke(entry) }, enabled = !busy && onOpen != null,
                        primary = true, tag = "robot-file-open")
                    if (entry.isRegularFile) WorkbenchIconButton(tr(Res.string.download_file_value, entry.name),
                        WorkbenchGlyph.DOWNLOAD, { onDownload?.invoke(entry) }, enabled = !busy && onDownload != null,
                        tag = "robot-file-download")
                    WorkbenchIconButton(tr(Res.string.rename_file_value, entry.name), WorkbenchGlyph.EDIT,
                        onRename, enabled = !busy && !entry.isProtected, tag = "robot-file-rename")
                    WorkbenchIconButton(tr(Res.string.delete), WorkbenchGlyph.DELETE, onDelete,
                        enabled = !busy && !entry.isProtected, danger = true, tag = "robot-file-delete")
                }
            }
        }
    }
}

@Composable
private fun fileMetadata(entry: RobotFileEntry): String {
    val type = when (entry.kind) {
        RobotFileKind.DIRECTORY -> tr(Res.string.folder)
        RobotFileKind.FILE -> formatRobotFileSize(entry.size)
        RobotFileKind.LINK -> tr(Res.string.symbolic_link)
        RobotFileKind.UNKNOWN -> tr(Res.string.unknown_file_type)
    }
    val time = entry.modifiedAtEpochMillis?.let {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.forLanguageTag(Localization.languageTag))
            .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    }
    return if (time == null) type else tr(Res.string.file_size_and_time_value, type, time)
}
