package cn.elonzh.hanppie.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.agent.runtime.AgentSession
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.DesktopListScrollbar
import cn.elonzh.hanppie.ui.design.HanppieBrandAssets
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ChatPage(model: ConsoleController, modifier: Modifier = Modifier, onVoiceInput: (() -> Unit)? = null, onSettings: () -> Unit = {}) {
    val state by model.chat.state.collectAsState()
    val config by model.modelSettings.collectAsState()
    val microphone by model.voiceInput.state.collectAsState()
    DisposableEffect(model) {
        model.voicePageActive = true
        onDispose { model.voicePageActive = false; model.voiceInput.cancel() }
    }
    val input = state.draft
    LaunchedEffect(microphone.resultId, state.sessionId) {
        if (microphone.result.isNotBlank()) {
            model.chat.updateDraft(input + (if (input.isBlank()) "" else "\n") + microphone.result)
            model.voiceInput.consumeResult()
        }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val composerFocus = remember { FocusRequester() }
    val surfaceFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { surfaceFocus.requestFocus() }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<AgentSession?>(null) }
    var renameTitle by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<AgentSession?>(null) }
    fun submit() {
        if (state.running) {
            model.chat.cancel()
        } else if (state.ready && input.isNotBlank() && !microphone.active) {
            if (config.apiKey.isBlank()) {
                focus.clearFocus()
                keyboard?.hide()
                onSettings()
            } else {
                model.chat.send(input, config)
            }
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(state.lines.size, state.streaming.length, state.approval) {
        val count = list.layoutInfo.totalItemsCount
        val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (count > 0 && !list.isScrollInProgress && lastVisible >= count - 2) list.animateScrollToItem(count - 1)
    }
    renameTarget?.let { conversation ->
        WorkbenchDialog(show = true, onDismissRequest = { renameTarget = null }, title = tr(Res.string.rename_conversation)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(renameTitle, { renameTitle = it.take(80) }, label = tr(Res.string.conversation_title), singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ renameTarget = null }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(Res.string.cancel)) }
                    Button({ model.chat.renameSession(conversation.id, renameTitle); renameTarget = null },
                        Modifier.weight(1f).heightIn(min = 48.dp), enabled = renameTitle.isNotBlank(),
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.save)) }
                }
            }
        }
    }
    deleteTarget?.let { conversation ->
        WorkbenchDialog(show = true, onDismissRequest = { deleteTarget = null }, title = tr(Res.string.delete_conversation_question),
            summary = tr(Res.string.delete_conversation_summary)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ deleteTarget = null }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(Res.string.cancel)) }
                Button({ model.chat.deleteSession(conversation.id); deleteTarget = null },
                    Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(Res.string.delete)) }
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val expanded = maxWidth >= 840.dp
        if (historyOpen && !expanded) {
            WorkbenchDialog(show = true, onDismissRequest = { historyOpen = false }, title = tr(Res.string.conversations)) {
                ConversationList(state.sessions, state.archivedSessions, state.sessionId,
                    onOpen = { model.chat.openSession(it); historyOpen = false },
                    onRename = { item -> renameTitle = item.title; renameTarget = item },
                    onArchive = model.chat::archiveSession,
                    onRestore = model.chat::restoreSession,
                    onDelete = { id -> deleteTarget = (state.sessions + state.archivedSessions).firstOrNull { it.id == id } },
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp))
            }
        }
        Row(Modifier.fillMaxSize().testTag("chat-surface").focusRequester(surfaceFocus).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (event.key == Key.Escape && state.running) {
                model.chat.cancel()
                return@onPreviewKeyEvent true
            }
            if (!event.isCtrlPressed && !event.isMetaPressed) return@onPreviewKeyEvent false
            when (event.key) {
                Key.N -> { if (!state.running) model.chat.newSession(); true }
                Key.B -> { historyOpen = !historyOpen; true }
                Key.L -> { composerFocus.requestFocus(); true }
                Key.Comma -> { onSettings(); true }
                Key.Enter -> { submit(); true }
                else -> false
            }
        }.focusable(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (expanded) ConversationList(state.sessions, state.archivedSessions, state.sessionId,
            onOpen = model.chat::openSession,
            onRename = { item -> renameTitle = item.title; renameTarget = item },
            onArchive = model.chat::archiveSession,
            onRestore = model.chat::restoreSession,
            onDelete = { id -> deleteTarget = (state.sessions + state.archivedSessions).firstOrNull { it.id == id } },
            enabled = !state.running,
            modifier = Modifier.width(280.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (!expanded) ComposerIcon(tr(Res.string.conversations), WorkbenchGlyph.CHAT,
                enabled = !state.running) { historyOpen = true }
            else Spacer(Modifier.weight(1f))
            ComposerIcon(tr(Res.string.new_chat), WorkbenchGlyph.ADD,
                enabled = state.ready && !state.running) { model.chat.newSession() }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp).testTag("chat-messages"), state = list,
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                if (state.lines.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        androidx.compose.foundation.Image(org.jetbrains.compose.resources.painterResource(HanppieBrandAssets.avatar),
                            null, Modifier.size(112.dp))
                        Text(tr(Res.string.what_would_you_like_to_do), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                items(state.lines) { line ->
                    Column(Modifier.fillMaxWidth().background(if (line.role == ChatRole.USER) MiuixTheme.colorScheme.secondaryContainer else MiuixTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(18.dp)).padding(16.dp)) {
                        Text(tr(line.role.label), fontSize = 11.sp, color = if (line.role == ChatRole.ASSISTANT) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (line.role == ChatRole.SCRIPT || line.role == ChatRole.TOOL) {
                            SelectionContainer { Text(line.text, Modifier.padding(top = 6.dp), fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace) }
                        } else ChatMarkdown(line.text, Modifier.padding(top = 6.dp))
                    }
                }
                if (state.running) item {
                    if (state.streaming.isNotBlank()) key(state.lines) {
                        Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(18.dp)).padding(16.dp)) {
                            Text(tr(ChatRole.ASSISTANT.label), fontSize = 11.sp, color = MiuixTheme.colorScheme.onTertiaryContainer)
                            StreamingReply(state.streaming, Modifier.padding(top = 6.dp))
                        }
                    }
                    else Text(if (state.approval != null) tr(Res.string.awaiting_approval) else tr(Res.string.hanppie_is_thinking),
                        Modifier.padding(12.dp), color = MiuixTheme.colorScheme.onTertiaryContainer)
                }
                state.approval?.let { source -> item {
                    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp)).padding(16.dp)) {
                        Text(tr(Res.string.run_this_script), fontWeight = FontWeight.SemiBold)
                        SelectionContainer { Text(source, Modifier.padding(vertical = 12.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ model.chat.approve(false) }) { Text(tr(Res.string.reject)) }
                            Button({ model.chat.approve(true) }, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.approve_and_run)) }
                        }
                    }
                } }
            }
            DesktopListScrollbar(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        state.error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        microphone.error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        if (microphone.active) Text(microphone.partial.ifBlank { if (microphone.processing) tr(Res.string.recognizing) else tr(Res.string.listening) }, fontSize = 13.sp)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            TextField(input, { if (it.length <= 12000) model.chat.updateDraft(it) },
                Modifier.weight(1f).focusRequester(composerFocus).testTag("chat-input"),
                label = tr(Res.string.message), maxLines = 4, enabled = !microphone.active)

            if (onVoiceInput != null) ComposerIcon(
                if (!microphone.active) tr(Res.string.voice_input) else if (microphone.processing) tr(Res.string.cancel_recognition) else tr(Res.string.finish_recording),
                if (microphone.active) WorkbenchGlyph.STOP else WorkbenchGlyph.MICROPHONE, enabled = !state.running,
                modifier = Modifier.testTag("voice-input"),
            ) {
                if (microphone.active) {
                    if (microphone.processing) model.voiceInput.cancel() else model.voiceInput.finish()
                } else onVoiceInput()
            }
            ComposerIcon(if (state.running) tr(Res.string.cancel) else tr(Res.string.send),
                if (state.running) WorkbenchGlyph.STOP else WorkbenchGlyph.SEND,
                enabled = state.running || (state.ready && input.isNotBlank() && !microphone.active),
                primary = true, onClick = ::submit)
        }
        if (expanded) Text(tr(Res.string.chat_shortcuts), fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(4.dp))
        }
        }
    }
}

@Composable
private fun ConversationList(
    active: List<AgentSession>,
    archived: List<AgentSession>,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onRename: (AgentSession) -> Unit,
    onArchive: (String) -> Unit,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
    Column(modifier.testTag("conversation-list")
        .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(Res.string.conversations), Modifier.padding(8.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(active, key = { it.id }) { item ->
                ConversationRow(item, selected = item.id == selectedId, onClick = { if (enabled) onOpen(item.id) }) {
                    WorkbenchIconButton(tr(Res.string.rename), WorkbenchGlyph.EDIT, { onRename(item) }, enabled = enabled)
                    WorkbenchIconButton(tr(Res.string.archive), WorkbenchGlyph.ARCHIVE, { onArchive(item.id) }, enabled = enabled)
                }
            }
            if (showArchived) items(archived, key = { "archived-${it.id}" }) { item ->
                ConversationRow(item, selected = false, onClick = {}) {
                    WorkbenchIconButton(tr(Res.string.restore), WorkbenchGlyph.RESTORE, { onRestore(item.id) }, enabled = enabled)
                    WorkbenchIconButton(tr(Res.string.delete), WorkbenchGlyph.DELETE, { onDelete(item.id) }, enabled = enabled, danger = true)
                }
            }
        }
        Button({ showArchived = !showArchived }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled && archived.isNotEmpty()) {
            Text(if (showArchived) tr(Res.string.hide_archived_conversations) else tr(Res.string.show_archived_conversations_value, archived.size))
        }
    }
}

@Composable
private fun ConversationRow(item: AgentSession, selected: Boolean, onClick: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(
        if (selected) MiuixTheme.colorScheme.primary.copy(alpha = .14f) else Color.Transparent,
        RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(10.dp)) {
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (item.lastMessagePreview.isNotBlank()) Text(item.lastMessagePreview, maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
    }
}


@Composable
private fun ComposerIcon(label: String, icon: WorkbenchGlyph, enabled: Boolean, modifier: Modifier = Modifier,
                         primary: Boolean = false, onClick: () -> Unit) {
    val background = if (primary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh
    val ink = if (primary) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    IconButton(onClick = onClick, enabled = enabled, cornerRadius = 14.dp,
        modifier = modifier.size(48.dp).semantics { contentDescription = label; role = Role.Button; if (!enabled) disabled() },
        backgroundColor = background.copy(alpha = if (enabled) 1f else .35f)) {
        WorkbenchIcon(icon, ink)
    }
}
