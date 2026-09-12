package cn.elonzh.hanppie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class NameOperation { SAVE, RENAME }

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ScriptPage(
    model: ConsoleModel,
    document: MutableState<EditorDocument>,
    compact: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit,
    fileError: String?,
    onFileError: (String?) -> Unit,
    onConnectionDetails: () -> Unit = {},
) {
    val robotState by model.state.collectAsState()
    val libraryState by model.scriptLibrary.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var editorOpen by rememberSaveable { mutableStateOf(document.value != EditorDocument()) }
    var pendingReplacement by remember { mutableStateOf<(() -> Unit)?>(null) }
    var nameOperation by remember { mutableStateOf<NameOperation?>(null) }
    var nameDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<StoredScript?>(null) }
    var showRun by rememberSaveable { mutableStateOf(robotState.scriptRunPhase.visible) }

    LaunchedEffect(document.value) {
        if (!editorOpen && document.value != EditorDocument()) editorOpen = true
    }
    LaunchedEffect(robotState.scriptRunId) {
        if (robotState.scriptRunId != null) showRun = true
    }

    fun showEditor(next: EditorDocument) {
        document.value = next
        onFileError(null)
        editorOpen = true
    }

    fun replaceDocument(action: () -> Unit) {
        if (editorOpen && document.value.dirty) pendingReplacement = action else action()
    }

    fun report(error: Throwable) {
        onFileError(error.message ?: error.javaClass.simpleName)
    }

    fun saveToLibrary(name: String? = null) {
        val snapshot = document.value
        document.value = snapshot.copy(busy = true)
        scope.launch {
            try {
                val saved = if (snapshot.scriptId == null) {
                    model.scriptLibrary.create(checkNotNull(name), snapshot.source)
                } else {
                    model.scriptLibrary.update(snapshot.scriptId, snapshot.source)
                }
                document.value = snapshot.stored(saved)
                onFileError(null)
            } catch (error: Exception) {
                document.value = snapshot.copy(busy = false)
                report(error)
            }
        }
    }

    val navigateBack = { replaceDocument { editorOpen = false } }
    PlatformBackHandler(
        enabled = showRun && robotState.scriptRunPhase.visible && pendingReplacement == null &&
            nameOperation == null && deleteTarget == null,
        onBack = { showRun = false },
    )
    PlatformBackHandler(
        enabled = !showRun && editorOpen && pendingReplacement == null && nameOperation == null && deleteTarget == null,
        onBack = navigateBack,
    )

    WorkbenchDialog(
        show = pendingReplacement != null,
        onDismissRequest = { pendingReplacement = null },
        title = tr(Res.string.replace_unsaved_script),
        summary = tr(Res.string.your_changes_have_not_been_saved),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ pendingReplacement = null }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(tr(Res.string.back))
            }
            Button({
                val action = pendingReplacement
                pendingReplacement = null
                action?.invoke()
            }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(tr(Res.string.discard_changes))
            }
        }
    }

    WorkbenchDialog(
        show = nameOperation != null,
        onDismissRequest = { nameOperation = null },
        title = tr(if (nameOperation == NameOperation.RENAME) Res.string.rename_script else Res.string.save_to_script_library),
    ) {
        val currentId = document.value.scriptId
        val nameAvailable = validScriptName(nameDraft) && libraryState.scripts.none {
            it.id != currentId && it.name.equals(nameDraft.trim(), ignoreCase = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = nameDraft,
                onValueChange = { if (it.length <= 64) nameDraft = it },
                label = tr(Res.string.script_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "script-name" },
            )
            if (!nameAvailable && nameDraft.isNotBlank()) {
                Text(tr(Res.string.script_name_unavailable), color = MiuixTheme.colorScheme.error, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ nameOperation = null }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(tr(Res.string.cancel))
                }
                Button({
                    val operation = nameOperation
                    nameOperation = null
                    if (operation == NameOperation.SAVE) {
                        saveToLibrary(nameDraft.trim())
                    } else {
                        val snapshot = document.value
                        document.value = snapshot.copy(busy = true)
                        scope.launch {
                            try {
                                val renamed = model.scriptLibrary.rename(checkNotNull(snapshot.scriptId), nameDraft.trim())
                                document.value = snapshot.stored(renamed).copy(source = snapshot.source, savedSource = snapshot.savedSource)
                                onFileError(null)
                            } catch (error: Exception) {
                                document.value = snapshot.copy(busy = false)
                                report(error)
                            }
                        }
                    }
                }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = nameAvailable,
                    colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(tr(Res.string.save))
                }
            }
        }
    }

    WorkbenchDialog(
        show = deleteTarget != null,
        onDismissRequest = { deleteTarget = null },
        title = tr(Res.string.delete_script_question),
        summary = deleteTarget?.let { tr(Res.string.delete_script_summary, it.name) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ deleteTarget = null }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(tr(Res.string.cancel))
            }
            Button({
                val target = deleteTarget ?: return@Button
                deleteTarget = null
                scope.launch {
                    try {
                        model.scriptLibrary.delete(target.id)
                        if (document.value.scriptId == target.id) {
                            document.value = EditorDocument()
                            editorOpen = false
                        }
                        onFileError(null)
                    } catch (error: Exception) {
                        report(error)
                    }
                }
            }, Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(tr(Res.string.delete))
            }
        }
    }

    if (showRun && robotState.scriptRunPhase.visible) {
        ScriptRunScreen(
            state = robotState,
            compact = compact,
            onBack = { showRun = false },
            onStop = model::stop,
            onConnectionDetails = onConnectionDetails,
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        ScriptTopBar(
            title = if (editorOpen) document.value.displayName ?: tr(Res.string.new_script) else tr(Res.string.script),
            dirty = editorOpen && document.value.dirty,
            state = robotState,
            onConnectionDetails = onConnectionDetails,
            onBack = navigateBack.takeIf { editorOpen },
        )
        if (robotState.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        (fileError ?: robotState.error)?.let {
            Text(it, Modifier.padding(vertical = 8.dp), color = MiuixTheme.colorScheme.error, fontSize = 13.sp)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (editorOpen) {
                ScriptEditor(
                    document = document,
                    robotState = robotState,
                    libraryBusy = libraryState.busy,
                    onImport = { replaceDocument { onImport() } },
                    onExport = onExport,
                    onSave = {
                        if (document.value.scriptId == null) {
                            nameDraft = model.scriptLibrary.uniqueName(document.value.displayName ?: tr(Res.string.new_script))
                            nameOperation = NameOperation.SAVE
                        } else saveToLibrary()
                    },
                    onRename = document.value.scriptId?.let {
                        {
                            nameDraft = document.value.displayName.orEmpty()
                            nameOperation = NameOperation.RENAME
                        }
                    },
                    onDelete = document.value.scriptId?.let { id ->
                        { deleteTarget = libraryState.scripts.firstOrNull { it.id == id } }
                    },
                    onShowRun = { showRun = true },
                    onStop = model::stop,
                    onRun = {
                        showRun = true
                        model.runScript(
                            document.value.source,
                            document.value.displayName ?: "Hanppie Script",
                        )
                    },
                )
            } else {
                ScriptLibraryView(
                    state = libraryState,
                    compact = compact,
                    onNew = { showEditor(EditorDocument(title = tr(Res.string.new_script))) },
                    onImport = onImport,
                    onOpen = { showEditor(EditorDocument.from(it)) },
                    onRename = {
                        showEditor(EditorDocument.from(it))
                        nameDraft = it.name
                        nameOperation = NameOperation.RENAME
                    },
                    onDelete = { deleteTarget = it },
                    onPreset = {
                        showEditor(EditorDocument(source = it.source, title = tr(it.name)))
                    },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ScriptLibraryView(
    state: ScriptLibraryState,
    compact: Boolean,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onOpen: (StoredScript) -> Unit,
    onRename: (StoredScript) -> Unit,
    onDelete: (StoredScript) -> Unit,
    onPreset: (PresetScript) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize().testTag("script-library"),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = tr(Res.string.my_scripts),
                actions = {
                    WorkbenchIconButton(
                        label = tr(Res.string.new_program),
                        glyph = WorkbenchGlyph.ADD,
                        onClick = onNew,
                        primary = true,
                        tag = "script-new",
                    )
                    Spacer(Modifier.width(6.dp))
                    WorkbenchIconButton(
                        label = tr(Res.string.import_py),
                        glyph = WorkbenchGlyph.IMPORT,
                        onClick = onImport,
                        tag = "script-import",
                    )
                },
            )
        }
        if (state.loading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth().heightIn(min = 2.dp)) }
        } else if (state.scripts.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        WorkbenchIcon(WorkbenchGlyph.FILE_TEXT, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(32.dp))
                        Text(tr(Res.string.no_saved_scripts), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
                    }
                }
            }
        } else {
            item {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.scripts.forEach { script ->
                        StoredScriptCard(script, compact, state.busy, onOpen, onRename, onDelete)
                    }
                }
            }
        }
        state.error?.let { error ->
            item { Text(error, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        }
        item { SectionHeader(tr(Res.string.preset_scripts)) }
        item {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                presetScripts.forEach { preset -> PresetScriptCard(preset, compact, onPreset) }
            }
        }
    }
}

@Composable
private fun ScriptTopBar(
    title: String,
    dirty: Boolean,
    state: ConsoleState,
    onConnectionDetails: () -> Unit,
    onBack: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            WorkbenchIconButton(
                label = tr(Res.string.back_to_script_library),
                glyph = WorkbenchGlyph.BACK,
                onClick = onBack,
                tag = "script-back",
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title + if (dirty) tr(Res.string.unsaved) else "",
            Modifier.weight(1f),
            fontSize = if (onBack == null) 26.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ConnectionStatusChip(state, onConnectionDetails)
    }
}

@Composable
private fun SectionHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        actions()
    }
}

@Composable
private fun StoredScriptCard(
    script: StoredScript,
    compact: Boolean,
    busy: Boolean,
    onOpen: (StoredScript) -> Unit,
    onRename: (StoredScript) -> Unit,
    onDelete: (StoredScript) -> Unit,
) {
    ProgramCardModifier(compact).let { modifier ->
        Card(modifier.clickable { if (!busy) onOpen(script) }
            .semantics { contentDescription = "script-open-${script.id}" }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(script.name, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    CapabilityBadge(tr(Res.string.local_script))
                }
                Text(formatUpdatedTime(script.updatedAtEpochMillis), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    WorkbenchIconButton(
                        label = tr(Res.string.rename_script),
                        glyph = WorkbenchGlyph.EDIT,
                        onClick = { onRename(script) },
                        enabled = !busy,
                        tag = "script-rename-${script.id}",
                    )
                    Spacer(Modifier.width(8.dp))
                    WorkbenchIconButton(
                        label = tr(Res.string.delete_script_question),
                        glyph = WorkbenchGlyph.DELETE,
                        onClick = { onDelete(script) },
                        enabled = !busy,
                        danger = true,
                        tag = "script-delete-${script.id}",
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetScriptCard(preset: PresetScript, compact: Boolean, onOpen: (PresetScript) -> Unit) {
    Card(ProgramCardModifier(compact).clickable { onOpen(preset) }
        .semantics { contentDescription = "script-preset-${preset.id}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(preset.name), Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                CapabilityBadge(tr(when (preset.capability) {
                    PresetCapability.EFFECT -> Res.string.light_or_sound
                    PresetCapability.GIMBAL_MOTION -> Res.string.moves_gimbal
                    PresetCapability.CHASSIS_MOTION -> Res.string.moves_robot
                }))
            }
            Text(tr(preset.summary), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, MiuixTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ProgramCardModifier(compact: Boolean): Modifier =
    (if (compact) Modifier.fillMaxWidth() else Modifier.width(300.dp))
        .heightIn(min = 142.dp)

@Composable
private fun CapabilityBadge(label: String) {
    Box(Modifier.background(MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 4.dp)) {
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ScriptEditor(
    document: MutableState<EditorDocument>,
    robotState: ConsoleState,
    libraryBusy: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSave: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onShowRun: () -> Unit,
    onStop: () -> Unit,
    onRun: () -> Unit,
) {
    val disabled = document.value.busy || libraryBusy
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkbenchIconButton(
                label = tr(Res.string.save_to_library),
                glyph = WorkbenchGlyph.SAVE,
                onClick = onSave,
                enabled = !disabled && (document.value.scriptId == null || document.value.dirty),
                primary = true,
                tag = "script-save",
            )
            if (onRename != null) {
                WorkbenchIconButton(tr(Res.string.rename_script), WorkbenchGlyph.EDIT, onRename,
                    enabled = !disabled, tag = "script-rename")
            }
            WorkbenchIconButton(tr(Res.string.import_py), WorkbenchGlyph.IMPORT, onImport,
                enabled = !disabled, tag = "script-import")
            WorkbenchIconButton(tr(Res.string.export_py), WorkbenchGlyph.EXPORT, onExport,
                enabled = !disabled, tag = "script-export")
            if (onDelete != null) {
                WorkbenchIconButton(tr(Res.string.delete_script_question), WorkbenchGlyph.DELETE, onDelete,
                    enabled = !disabled, danger = true, tag = "script-delete")
            }
            if (robotState.scriptRunPhase.visible) {
                WorkbenchIconButton(tr(Res.string.view_script_run), WorkbenchGlyph.ACTIVITY, onShowRun,
                    tag = "script-show-run")
            }
            if (robotState.canStop) {
                WorkbenchIconButton(tr(Res.string.stop_script), WorkbenchGlyph.STOP, onStop,
                    danger = true, tag = "script-stop")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(HanppieDesignTokens.CardRadius)).padding(16.dp)) {
            BasicTextField(
                value = document.value.source,
                onValueChange = { document.value = document.value.copy(source = it) },
                modifier = Modifier.fillMaxSize().testTag("script-editor").verticalScroll(rememberScrollState()),
                enabled = !disabled,
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 23.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
        Button(onRun, Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = robotState.canRun(document.value.source), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(tr(Res.string.run_script), fontSize = 13.sp)
        }
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun ScriptRunScreen(
    state: ConsoleState,
    compact: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onConnectionDetails: () -> Unit = {},
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val lines = state.scriptMessages
    val listState = rememberLazyListState()
    LaunchedEffect(state.scriptRunPhase.active, state.scriptStartedAtEpochMillis) {
        while (state.scriptRunPhase.active) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    val elapsed = state.scriptStartedAtEpochMillis?.let { ((now - it).coerceAtLeast(0) / 1_000) }
    val elapsedText = elapsed?.let { "%d:%02d".format(it / 60, it % 60) }

    Column(Modifier.fillMaxSize().testTag("script-run-screen")) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkbenchIconButton(
                label = tr(Res.string.back_to_script_editor),
                glyph = WorkbenchGlyph.BACK,
                onClick = onBack,
                tag = "script-run-back",
            )
            Spacer(Modifier.width(12.dp))
            Text(tr(Res.string.script_run), Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            ConnectionStatusChip(state, onConnectionDetails)
            if (state.canStop) {
                WorkbenchIconButton(
                    label = tr(Res.string.stop_script),
                    glyph = WorkbenchGlyph.STOP,
                    onClick = onStop,
                    danger = true,
                    tag = "script-run-stop",
                )
            }
        }
        if (compact) {
            RunStatusCard(state, elapsedText, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            RunLogCard(lines, listState, Modifier.weight(1f).fillMaxWidth())
        } else {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RunStatusCard(state, elapsedText, Modifier.widthIn(min = 260.dp, max = 320.dp).fillMaxHeight())
                RunLogCard(lines, listState, Modifier.weight(1f).fillMaxHeight())
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RunStatusCard(state: ConsoleState, elapsedText: String?, modifier: Modifier) {
    val color = scriptRunColor(state.scriptRunPhase)
    Card(modifier, colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.scriptRunPhase.active) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(color, RoundedCornerShape(50)))
                Spacer(Modifier.width(10.dp))
                Text(state.scriptStatus, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
            }
            Text(state.scriptTitle ?: tr(Res.string.script), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            elapsedText?.let {
                Text(it, fontSize = 32.sp, fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun RunLogCard(
    lines: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
) {
    Card(modifier.testTag("script-run-log"),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(Res.string.run_log), Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(lines.size.toString(), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(12.dp))
            if (lines.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(tr(Res.string.waiting_for_script_output), fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            } else {
                Box(Modifier.weight(1f)) {
                SelectionContainer(Modifier.fillMaxSize().padding(end = 12.dp)) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(lines) { index, line ->
                            Row(Modifier.fillMaxWidth()) {
                                Text((index + 1).toString().padStart(2, '0'), Modifier.width(30.dp), fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontFamily = FontFamily.Monospace)
                                Text(line, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                DesktopListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
internal fun scriptRunColor(phase: ScriptRunPhase) = when (phase) {
    ScriptRunPhase.COMPLETED -> androidx.compose.ui.graphics.Color(0xff32aa78)
    ScriptRunPhase.FAILED, ScriptRunPhase.UNKNOWN -> MiuixTheme.colorScheme.error
    ScriptRunPhase.STOPPED -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    else -> MiuixTheme.colorScheme.primary
}

private fun formatUpdatedTime(epochMillis: Long): String {
    val pattern = if (Localization.english) "MMM d, yyyy HH:mm" else "yyyy-MM-dd HH:mm"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}
