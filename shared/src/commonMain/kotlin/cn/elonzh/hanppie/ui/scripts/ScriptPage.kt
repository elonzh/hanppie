package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.ui.scripts.editor.CodeHistory
import cn.elonzh.hanppie.ui.scripts.editor.ScriptCodeEditor
import androidx.compose.foundation.combinedClickable
import cn.elonzh.hanppie.ui.design.WorkbenchActionRow
import cn.elonzh.hanppie.ui.design.WorkbenchSmallIconButton
import cn.elonzh.hanppie.ui.design.secondaryClick
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.app.ConsoleState
import cn.elonzh.hanppie.ui.app.PlatformBackHandler
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.DateTimeStyle
import cn.elonzh.hanppie.ui.i18n.formatLocalDateTime
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.device.ConnectionStatusChip
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
    model: ConsoleController,
    document: MutableState<EditorDocument>,
    compact: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit,
    fileError: String?,
    onFileError: (String?) -> Unit,
    onConnectionDetails: () -> Unit = {},
    onImportAudio: () -> Unit = {},
) {
    val robotState by model.state.collectAsState()
    val libraryState by model.scriptLibrary.state.collectAsState()
    val audioState by model.scriptAudio.state.collectAsState()
    val audioPlaybackState by model.scriptAudio.playbackState.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var editorOpen by rememberSaveable { mutableStateOf(document.value != EditorDocument()) }
    var pendingReplacement by remember { mutableStateOf<(() -> Unit)?>(null) }
    var nameOperation by remember { mutableStateOf<NameOperation?>(null) }
    var nameDraft by remember { mutableStateOf("") }
    var monitorOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var scriptMenu by remember { mutableStateOf<StoredScript?>(null) }
    var audioOpen by rememberSaveable { mutableStateOf(false) }
    var audioRename by remember { mutableStateOf<LabAudioClip?>(null) }
    var audioNameDraft by remember { mutableStateOf("") }
    var audioDelete by remember { mutableStateOf<LabAudioClip?>(null) }
    var editorGeneration by remember { mutableStateOf(0) }
    val editorField = remember {
        mutableStateOf(TextFieldValue(document.value.source, TextRange(document.value.source.length)))
    }

    val editorHistory = remember(editorGeneration, document.value.path) { CodeHistory(editorField.value) }

    LaunchedEffect(document.value) {
        if (!editorOpen && document.value != EditorDocument()) editorOpen = true
    }
    LaunchedEffect(document.value.scriptId, document.value.initialAudio, editorOpen) {
        model.scriptAudio.open(if (editorOpen) document.value.scriptId else null, document.value.initialAudio)
    }
    LaunchedEffect(document.value.source) {
        if (editorField.value.text != document.value.source) {
            editorField.value = TextFieldValue(document.value.source, TextRange(document.value.source.length))
            // A loaded/replaced document must never undo into the previous script.
            // Local edits (including audio insertion) update both values together.
            editorHistory.reset(editorField.value)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { model.scriptAudio.stop() }
    }

    fun showEditor(next: EditorDocument) {
        editorGeneration++
        editorField.value = TextFieldValue(next.source, TextRange(next.source.length))
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

    fun deleteScript(target: StoredScript) {
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
    }

    fun audioOperation(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                onFileError(null)
            } catch (error: Exception) {
                report(error)
            }
        }
    }

    fun saveToLibrary(name: String? = null) {
        val snapshot = document.value
        document.value = snapshot.copy(busy = true)
        scope.launch {
            try {
                val saved = if (snapshot.scriptId == null) {
                    val created = model.scriptLibrary.create(checkNotNull(name), snapshot.source, snapshot.initialAudio)
                    if (snapshot.initialAudio.isNotEmpty()) {
                        model.scriptAudio.saveClipsFor(created.id, snapshot.initialAudio)
                    }
                    created
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
        enabled = editorOpen && pendingReplacement == null && nameOperation == null &&
            audioRename == null && audioDelete == null &&
            !monitorOpen && !audioOpen,
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
        show = audioRename != null,
        onDismissRequest = { audioRename = null },
        title = tr(Res.string.script_audio_rename),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = audioNameDraft,
                onValueChange = { if (it.length <= LabAudioClip.MAX_NAME_LENGTH) audioNameDraft = it },
                label = tr(Res.string.script_audio_rename),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "script-audio-name" },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ audioRename = null }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(tr(Res.string.cancel))
                }
                Button({
                    val target = audioRename ?: return@Button
                    audioRename = null
                    val name = audioNameDraft
                    audioOperation { model.scriptAudio.rename(target.id, name) }
                }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = audioNameDraft.isNotBlank(),
                    colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(tr(Res.string.save))
                }
            }
        }
    }

    WorkbenchDialog(
        show = audioDelete != null,
        onDismissRequest = { audioDelete = null },
        title = tr(Res.string.script_audio_delete_question),
        summary = audioDelete?.let { tr(Res.string.script_audio_delete_summary, it.name) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ audioDelete = null }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(tr(Res.string.cancel))
            }
            Button({
                val target = audioDelete ?: return@Button
                audioDelete = null
                audioOperation { model.scriptAudio.delete(target.id) }
            }, Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(tr(Res.string.delete))
            }
        }
    }

    WorkbenchDialog(
        show = scriptMenu != null,
        onDismissRequest = { scriptMenu = null },
        title = scriptMenu?.name ?: tr(Res.string.script),
        summary = scriptMenu?.let { formatUpdatedTime(it.createdAtEpochMillis) },
    ) {
        val target = scriptMenu
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (target != null) {
                WorkbenchActionRow(tr(Res.string.rename_script), WorkbenchGlyph.EDIT,
                    "script-rename-${target.id}") {
                    scriptMenu = null
                    showEditor(EditorDocument.from(target))
                    nameDraft = target.name
                    nameOperation = NameOperation.RENAME
                }
                WorkbenchActionRow(tr(Res.string.delete_script), WorkbenchGlyph.DELETE,
                    "script-delete-${target.id}", danger = true) {
                    // Managing a script is explicit twice over (long press, then the menu row), so deleting
                    // acts at once instead of stacking another dialog on top of the menu.
                    scriptMenu = null
                    deleteScript(target)
                }
            }
            Button({ scriptMenu = null }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(tr(Res.string.close))
            }
        }
    }

    WorkbenchDialog(
        show = moreOpen,
        onDismissRequest = { moreOpen = false },
        title = tr(Res.string.more_actions),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkbenchActionRow(tr(Res.string.import_py), WorkbenchGlyph.IMPORT, "script-import") {
                moreOpen = false
                replaceDocument { onImport() }
            }
            WorkbenchActionRow(tr(Res.string.export_py), WorkbenchGlyph.EXPORT, "script-export") {
                moreOpen = false
                onExport()
            }
            document.value.scriptId?.let { id ->
                WorkbenchActionRow(tr(Res.string.delete_script), WorkbenchGlyph.DELETE, "script-delete",
                    danger = true) {
                    moreOpen = false
                    libraryState.scripts.firstOrNull { it.id == id }?.let(::deleteScript)
                }
            }
            Button({ moreOpen = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(tr(Res.string.close))
            }
        }
    }

    WorkbenchDialog(
        show = monitorOpen,
        onDismissRequest = { monitorOpen = false },
        title = tr(Res.string.script_monitor),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScriptMonitorPanel(model, robotState, modifier = Modifier.fillMaxWidth())
            Button({ monitorOpen = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(tr(Res.string.close))
            }
        }
    }

    WorkbenchDialog(
        show = audioOpen,
        onDismissRequest = {
            audioOpen = false
            model.scriptAudio.stop()
        },
        title = tr(Res.string.script_audio_manage),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScriptAudioPanel(
                audio = audioState,
                scriptId = document.value.scriptId,
                sourceLength = document.value.source.length,
                playbackState = audioPlaybackState,
                onPlay = model.scriptAudio::play,
                onPause = model.scriptAudio::pause,
                onResume = model.scriptAudio::resume,
                onMove = { from, to -> scope.launch { model.scriptAudio.move(from, to) } },
                onImport = onImportAudio,
                onRename = { clip -> audioNameDraft = clip.name; audioRename = clip },
                onDelete = { audioDelete = it },
            )
            Button({
                audioOpen = false
                model.scriptAudio.stop()
            }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(tr(Res.string.close))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScriptTopBar(
            title = if (editorOpen) document.value.displayName ?: tr(Res.string.new_script) else tr(Res.string.script),
            dirty = editorOpen && document.value.dirty,
            state = robotState,
            onRename = if (editorOpen) document.value.scriptId?.let {
                {
                    nameDraft = document.value.displayName.orEmpty()
                    nameOperation = NameOperation.RENAME
                }
            } else null,
            onConnectionDetails = onConnectionDetails,
            onBack = navigateBack.takeIf { editorOpen },
        )
        fileError?.let {
            Text(it, Modifier.padding(vertical = 8.dp), color = MiuixTheme.colorScheme.error, fontSize = 13.sp)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val availableHeight = maxHeight
            // With the view already inline there is nothing left for a "monitor" entry to reveal.
            val monitorInline = !compact && availableHeight >= 520.dp
            val editor: @Composable (Modifier) -> Unit = { editorModifier ->
                    ScriptEditor(
                        document = document,
                        field = editorField,
                        history = editorHistory,
                        robotState = robotState,
                        libraryBusy = libraryState.busy,
                        onSave = {
                            if (document.value.scriptId == null) {
                                nameDraft = model.scriptLibrary.uniqueName(document.value.displayName ?: tr(Res.string.new_script))
                                nameOperation = NameOperation.SAVE
                            } else saveToLibrary()
                        },
                        onMore = { moreOpen = true },
                        onMonitor = if (monitorInline) null else ({ monitorOpen = true }),
                        onAudio = { audioOpen = true },
                        onRun = {
                            model.runScript(
                                document.value.source,
                                document.value.displayName ?: "Hanppie Script",
                                audioState.clips,
                            )
                        },
                        onStop = model::stop,
                        modifier = editorModifier,
                    )
            }

            // A run must never replace the page: the console and the monitor sit around the editor on
            // phones (below) and on wide layouts (right), and the library keeps the same console while a
            // run is in flight so the global run bar always leads somewhere that shows the run.
            val monitoring = editorOpen || robotState.scriptRunPhase.visible
            val library: @Composable (Modifier) -> Unit = { libraryModifier ->
                ScriptLibraryView(
                    state = libraryState,
                    compact = compact,
                    onNew = { showEditor(EditorDocument(title = tr(Res.string.new_script))) },
                    onImport = onImport,
                    onOpen = { showEditor(EditorDocument.from(it)) },
                    onManage = { scriptMenu = it },
                    onPreset = {
                        showEditor(EditorDocument(source = it.source, title = it.name, initialAudio = it.audioClips))
                    },
                    modifier = libraryModifier,
                )
            }
            val primary: @Composable (Modifier) -> Unit = { primaryModifier ->
                if (editorOpen) editor(primaryModifier) else library(primaryModifier)
            }
            if (compact) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Keep code primary, while retaining a visible result/stop context.
                    val consoleHeight = (availableHeight * 0.25f).coerceAtMost(160.dp)
                    Box(Modifier.weight(1f).fillMaxWidth()) { primary(Modifier.fillMaxSize()) }
                    if (monitoring) ScriptConsolePanel(robotState,
                        (if (editorOpen) Modifier.height(consoleHeight) else Modifier.weight(
                            if (availableHeight < 460.dp) 1.1f else 0.85f
                        )).fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { primary(Modifier.fillMaxSize()) }
                    if (monitoring) {
                        Column(Modifier.width(380.dp).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ScriptMonitorPanel(model, robotState, showVideo = monitorInline,
                                modifier = Modifier.fillMaxWidth())
                            ScriptConsolePanel(robotState, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
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
    onManage: (StoredScript) -> Unit,
    onPreset: (StoredScript) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier.testTag("script-library"),
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
                        StoredScriptCard(script, compact, state.busy, onOpen, onManage)
                    }
                }
            }
        }
        state.error?.let { error ->
            item { Text(error, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        }
        if (state.presets.isNotEmpty()) {
            item { SectionHeader(tr(Res.string.preset_scripts)) }
            item {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.presets.forEach { preset -> PresetScriptCard(preset, compact, onPreset) }
                }
            }
        }
    }
}

@Composable
private fun ScriptTopBar(
    title: String,
    dirty: Boolean,
    state: ConsoleState,
    onRename: (() -> Unit)?,
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
        // Title and its rename affordance form one filled group: the title takes only its own width (so
        // the icon trails the text) while the group keeps the connection chip pinned to the far edge.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title + if (dirty) tr(Res.string.unsaved) else "",
                Modifier.weight(1f, fill = false),
                fontSize = if (onBack == null) 26.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            onRename?.let {
                WorkbenchSmallIconButton(
                    label = tr(Res.string.rename_script),
                    glyph = WorkbenchGlyph.EDIT,
                    onClick = it,
                    tag = "script-rename",
                )
            }
        }
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
    onManage: (StoredScript) -> Unit,
) {
    ProgramCardModifier(compact).let { modifier ->
        // The card carries no action buttons: tapping the row opens the program, and management
        // (rename, delete) lives behind a long press or a secondary click, so the list reads as a list.
        Card(modifier.testTag("script-card-${script.id}")
            .combinedClickable(
                enabled = !busy,
                onClick = { onOpen(script) },
                onLongClick = { onManage(script) },
            )
            .then(Modifier.secondaryClick { onManage(script) })
            .semantics { contentDescription = "script-open-${script.id}" }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(script.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(formatUpdatedTime(script.createdAtEpochMillis), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun PresetScriptCard(preset: StoredScript, compact: Boolean, onOpen: (StoredScript) -> Unit) {
    Card(ProgramCardModifier(compact).clickable { onOpen(preset) }
        .semantics { contentDescription = "script-preset-${preset.id}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(preset.name, Modifier.fillMaxWidth(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            preset.summary?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun ProgramCardModifier(compact: Boolean): Modifier =
    (if (compact) Modifier.fillMaxWidth() else Modifier.width(300.dp))
        .heightIn(min = 142.dp)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ScriptEditor(
    document: MutableState<EditorDocument>,
    field: MutableState<TextFieldValue>,
    history: CodeHistory,
    robotState: ConsoleState,
    libraryBusy: Boolean,
    onSave: () -> Unit,
    onMore: () -> Unit,
    onMonitor: (() -> Unit)?,
    onAudio: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val disabled = document.value.busy || libraryBusy
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Actions sit on the left as icon buttons; running the script is the primary action and stays on
        // the right edge of this bar, next to the code it applies to.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WorkbenchIconButton(
                    label = tr(Res.string.save_to_library),
                    glyph = WorkbenchGlyph.SAVE,
                    onClick = onSave,
                    enabled = !disabled && (document.value.scriptId == null || document.value.dirty),
                    primary = true,
                    tag = "script-save",
                )
                WorkbenchIconButton(
                    label = tr(Res.string.script_audio_manage),
                    glyph = WorkbenchGlyph.SPEAKER,
                    onClick = onAudio,
                    enabled = !disabled,
                    tag = "script-audio-manage",
                )
                onMonitor?.let { monitor ->
                    WorkbenchIconButton(
                        label = tr(Res.string.script_monitor),
                        glyph = WorkbenchGlyph.VIDEO,
                        onClick = monitor,
                        enabled = !disabled,
                        tag = "script-monitor",
                    )
                }
                WorkbenchIconButton(
                    label = tr(Res.string.more_actions),
                    glyph = WorkbenchGlyph.MORE,
                    onClick = onMore,
                    enabled = !disabled,
                    tag = "script-more",
                )
            }
            Spacer(Modifier.width(8.dp))
            if (robotState.canStop) {
                WorkbenchIconButton(
                    label = tr(Res.string.stop_script),
                    glyph = WorkbenchGlyph.STOP,
                    onClick = onStop,
                    danger = true,
                    tag = "script-run-stop",
                )
            } else {
                WorkbenchIconButton(
                    label = tr(Res.string.run_script),
                    glyph = WorkbenchGlyph.PLAY,
                    onClick = onRun,
                    enabled = robotState.canRun(document.value.source),
                    primary = true,
                    tag = "script-run",
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(HanppieDesignTokens.CardRadius)).padding(16.dp)) {
            ScriptCodeEditor(
                history = history,
                field = field,
                enabled = !disabled,
                onChange = { updated ->
                    field.value = updated
                    document.value = document.value.copy(source = updated.text)
                },
                onSave = onSave,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun formatUpdatedTime(epochMillis: Long): String {
    return formatLocalDateTime(epochMillis, DateTimeStyle.SCRIPT_UPDATED)
}
