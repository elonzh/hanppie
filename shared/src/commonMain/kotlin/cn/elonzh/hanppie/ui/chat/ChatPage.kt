package cn.elonzh.hanppie.ui.chat

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.agent.runtime.AgentSession
import cn.elonzh.hanppie.agent.tools.DeleteLabScriptTool
import cn.elonzh.hanppie.agent.tools.ExecuteLabPythonTool
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.DesktopListScrollbar
import cn.elonzh.hanppie.ui.design.HanppieBrandAssets
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.i18n.tr
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ChatPage(model: ConsoleController, modifier: Modifier = Modifier, onVoiceInput: (() -> Unit)? = null, onOpenScript: (String) -> Unit = {}, onSettings: () -> Unit = {}) {
    val state by model.chat.state.collectAsState()
    val config by model.modelSettings.collectAsState()
    val microphone by model.voiceInput.state.collectAsState()
    DisposableEffect(model) {
        model.voicePageActive = true
        onDispose { model.voicePageActive = false; model.voiceInput.cancel() }
    }
    var composerValue by remember(state.sessionId) { mutableStateOf(TextFieldValue(state.draft)) }
    LaunchedEffect(state.draft) {
        if (composerValue.text != state.draft) {
            composerValue = TextFieldValue(state.draft, TextRange(state.draft.length))
        }
    }
    LaunchedEffect(microphone.resultId, state.sessionId) {
        if (microphone.result.isNotBlank()) {
            val draft = model.chat.state.value.draft
            model.chat.updateDraft(draft + (if (draft.isBlank()) "" else "\n") + microphone.result)
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
    fun submit(): Boolean {
        val current = model.chat.state.value
        val message = current.draft
        if (current.running) {
            model.chat.cancel()
            return true
        }
        if (current.canSend && message.isNotBlank() && !microphone.active) {
            if (config.apiKey.isBlank()) {
                focus.clearFocus()
                keyboard?.hide()
                onSettings()
                return true
            } else {
                return model.chat.send(message, config)
            }
        }
        return false
    }
    val list = key(state.sessionId) { rememberLazyListState() }
    val scrollbarInteraction = remember { MutableInteractionSource() }
    val draggingScrollbar by scrollbarInteraction.collectIsDraggedAsState()
    var followLatest by rememberSaveable(state.sessionId) { mutableStateOf(true) }
    var forceLatest by rememberSaveable(state.sessionId) { mutableStateOf(true) }
    var previousUserMessageRevision by rememberSaveable(state.sessionId) {
        mutableLongStateOf(state.userMessageRevision)
    }
    val userScroll = remember(list) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    followLatest = false
                    forceLatest = false
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y < 0f && !list.canScrollForward) {
                    followLatest = true
                }
                return Offset.Zero
            }
        }
    }
    var wasDraggingScrollbar by remember { mutableStateOf(false) }
    LaunchedEffect(draggingScrollbar) {
        if (draggingScrollbar) {
            followLatest = false
            forceLatest = false
        } else if (wasDraggingScrollbar) {
            followLatest = !list.canScrollForward
        }
        wasDraggingScrollbar = draggingScrollbar
    }
    LaunchedEffect(state.sessionId, state.userMessageRevision) {
        if (state.userMessageRevision > previousUserMessageRevision) {
            followLatest = true
            forceLatest = true
        }
        previousUserMessageRevision = state.userMessageRevision
    }
    var observedContent by remember(list) { mutableStateOf(false) }
    // Only new conversation content can request following. Layout changes (including tool
    // expansion and lazy item measurement) must never move the reader to the bottom.
    LaunchedEffect(list, state.lines, state.streaming, state.running, state.approval, forceLatest) {
        val returningToSavedPosition = !observedContent && !forceLatest
        observedContent = true
        if (!returningToSavedPosition && (followLatest || forceLatest) && !draggingScrollbar) {
            val bottomIndex = state.lines.size.coerceAtLeast(1) +
                (if (state.running) 1 else 0) + (if (state.approval != null) 1 else 0)
            var stableFrames = 0
            var previousLayout = list.layoutInfo
            // Markdown can gain height on the next layout after its lazy item is composed.
            // Finish this content update only after the bottom has settled, then stop observing
            // geometry so later expansion or recycling cannot restart following.
            while (stableFrames < 2) {
                withFrameNanos { }
                if ((!followLatest && !forceLatest) || draggingScrollbar || list.isScrollInProgress) break
                val layout = list.layoutInfo
                if (layout === previousLayout && !list.canScrollForward) stableFrames++ else stableFrames = 0
                previousLayout = layout
                if (list.canScrollForward) list.scrollToItem(bottomIndex)
            }
            forceLatest = false
        }
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
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val expanded = maxWidth >= 840.dp
        val compactHistoryListHeightLimit = (maxHeight - 220.dp).coerceIn(120.dp, 420.dp)
        if (historyOpen && !expanded) {
            WorkbenchDialog(show = true, onDismissRequest = { historyOpen = false }, title = tr(Res.string.conversations)) {
                ConversationList(state.sessions, state.sessionId,
                    onOpen = { model.chat.openSession(it); historyOpen = false },
                    onNew = { model.chat.newSession(); historyOpen = false },
                    onRename = { item -> renameTitle = item.title; renameTarget = item },
                    // Deleting is the explicit choice already: the menu row acts at once instead of asking
                    // the user to confirm wording about their own conversation.
                    onDelete = { id -> model.chat.deleteSession(id) },
                    enabled = state.ready,
                    listHeightLimit = compactHistoryListHeightLimit,
                    fillAvailableHeight = false,
                    modifier = Modifier.fillMaxWidth())
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
                Key.N -> { if (state.ready) model.chat.newSession(); true }
                Key.B -> { historyOpen = !historyOpen; true }
                Key.L -> { composerFocus.requestFocus(); true }
                Key.Comma -> { onSettings(); true }
                else -> false
            }
        }.focusable(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (expanded) ConversationList(state.sessions, state.sessionId,
            onOpen = model.chat::openSession,
            onNew = model.chat::newSession,
            onRename = { item -> renameTitle = item.title; renameTarget = item },
            onDelete = { id -> model.chat.deleteSession(id) },
            enabled = state.ready,
            fillAvailableHeight = true,
            modifier = Modifier.width(280.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!expanded) Row(Modifier.fillMaxWidth()) {
            ComposerIcon(tr(Res.string.conversations), WorkbenchGlyph.CHAT,
                enabled = state.ready) { historyOpen = true }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().nestedScroll(userScroll).padding(end = 12.dp).testTag("chat-messages"), state = list,
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
                    ChatMessage(line, onOpenScript)
                }
                if (state.running) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.streamingReasoning.isNotBlank()) ChatReasoning(state.streamingReasoning, streaming = true)
                        if (state.streaming.isNotBlank()) key(state.lines) {
                            StreamingReply(state.streaming, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                        } else if (state.streamingReasoning.isBlank() || state.approval != null) {
                            Text(if (state.approval != null) tr(Res.string.awaiting_approval) else tr(Res.string.agent_is_thinking),
                                Modifier.padding(12.dp), color = MiuixTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
                state.approval?.let { approval -> item {
                    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp)).padding(16.dp)) {
                        val approvalTitle = when (approval.toolCall.tool) {
                            ExecuteLabPythonTool.NAME -> Res.string.run_this_script
                            DeleteLabScriptTool.NAME -> Res.string.delete_this_script
                            else -> Res.string.approve_tool_operation
                        }
                        val approvalAction = when (approval.toolCall.tool) {
                            ExecuteLabPythonTool.NAME -> Res.string.approve_and_run
                            DeleteLabScriptTool.NAME -> Res.string.approve_and_delete
                            else -> Res.string.approve
                        }
                        Text(tr(approvalTitle), fontWeight = FontWeight.SemiBold)
                        when (approval.toolCall.tool) {
                            ExecuteLabPythonTool.NAME -> ChatMarkdown(
                                "```python\n${approval.preview.trimEnd()}\n```",
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            )
                            else -> SelectionContainer {
                                Text(
                                    approval.preview,
                                    Modifier.padding(vertical = 12.dp),
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        FlowRow(
                            Modifier.fillMaxWidth().testTag("chat-approval-actions"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button({ model.chat.approve(false) }, Modifier.testTag("chat-approval-reject")) { Text(tr(Res.string.reject)) }
                            Button({ model.chat.approve(true) }, Modifier.testTag("chat-approval-confirm"), colors = ButtonDefaults.buttonColorsPrimary()) {
                                Text(tr(approvalAction))
                            }
                        }
                    }
                } }
                item(key = "chat-bottom-anchor") {
                    Spacer(Modifier.height(1.dp).testTag("chat-bottom-anchor"))
                }
            }
            DesktopListScrollbar(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("chat-scrollbar"), scrollbarInteraction)
        }
        state.error?.let { error ->
            SelectionContainer { Text(error, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        }
        microphone.error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        if (microphone.active) Text(microphone.partial.ifBlank { if (microphone.processing) tr(Res.string.recognizing) else tr(Res.string.listening) }, fontSize = 13.sp)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            TextField(composerValue, { value ->
                if (value.text.length <= 12000) {
                    composerValue = value
                    model.chat.updateDraft(value.text)
                }
            }, Modifier.weight(1f).focusRequester(composerFocus).testTag("chat-input")
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter &&
                        !event.isShiftPressed && composerValue.composition == null) {
                        submit()
                    } else false
                },
                label = tr(Res.string.message), maxLines = 4, enabled = !microphone.active)

            if (onVoiceInput != null) ComposerIcon(
                if (!microphone.active) tr(Res.string.voice_input) else if (microphone.processing) tr(Res.string.cancel_recognition) else tr(Res.string.finish_recording),
                if (microphone.active) WorkbenchGlyph.STOP else WorkbenchGlyph.MICROPHONE, enabled = state.ready,
                modifier = Modifier.testTag("voice-input"),
            ) {
                if (microphone.active) {
                    if (microphone.processing) model.voiceInput.cancel() else model.voiceInput.finish()
                } else onVoiceInput()
            }
            ComposerIcon(if (state.running) tr(Res.string.cancel) else tr(Res.string.send),
                if (state.running) WorkbenchGlyph.STOP else WorkbenchGlyph.SEND,
                enabled = state.running || (state.canSend && composerValue.text.isNotBlank() && !microphone.active),
                primary = true, onClick = { submit() })
        }
        Spacer(Modifier.height(4.dp))
        }
        }
    }
}

@Composable
private fun ConversationList(
    sessions: List<AgentSession>,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (AgentSession) -> Unit,
    onDelete: (String) -> Unit,
    enabled: Boolean,
    fillAvailableHeight: Boolean,
    listHeightLimit: Dp? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("conversation-list")
        .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(Res.string.conversations), Modifier.weight(1f).padding(start = 8.dp),
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            WorkbenchIconButton(
                label = tr(Res.string.new_chat),
                glyph = WorkbenchGlyph.ADD,
                onClick = onNew,
                enabled = enabled,
                primary = true,
                tag = "new-conversation",
            )
        }
        val listModifier = if (fillAvailableHeight) {
            Modifier.fillMaxWidth().weight(1f)
        } else {
            Modifier.fillMaxWidth().heightIn(max = requireNotNull(listHeightLimit))
        }
        LazyColumn(listModifier,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sessions, key = { it.id }) { item ->
                ConversationRow(item, selected = item.id == selectedId,
                    onClick = { if (enabled) onOpen(item.id) },
                    onRename = { onRename(item) }, onDelete = { onDelete(item.id) }, enabled = enabled)
            }
        }
    }
}

@Composable
private fun ConversationRow(
    item: AgentSession,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    Row(Modifier.fillMaxWidth().background(
        if (selected) MiuixTheme.colorScheme.primary.copy(alpha = .14f) else Color.Transparent,
        RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick)
        .padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // One line per conversation: the title is the whole row and long titles are ellipsized.
        Text(item.title, Modifier.weight(1f).padding(end = 4.dp), maxLines = 1,
            overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        ConversationActions(onRename, onDelete, enabled)
    }
}

@Composable
private fun ConversationActions(onRename: () -> Unit, onDelete: () -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled, cornerRadius = 14.dp,
            modifier = Modifier.size(48.dp).semantics {
                contentDescription = tr(Res.string.conversation_actions)
                role = Role.Button
                if (!enabled) disabled()
            }, backgroundColor = Color.Transparent) {
            WorkbenchIcon(WorkbenchGlyph.MORE, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(20.dp))
        }
        WindowListPopup(expanded, onDismissRequest = { expanded = false }) {
            ListPopupColumn {
                DropdownImpl(DropdownItem(tr(Res.string.rename)), 2, false, 0,
                    onSelectedIndexChange = { expanded = false; onRename() })
                DropdownImpl(DropdownItem(tr(Res.string.delete)), 2, false, 1,
                    onSelectedIndexChange = { expanded = false; onDelete() })
            }
        }
    }
}

@Composable
private fun ChatMessage(line: ChatLine, onOpenScript: (String) -> Unit) {
    when (line.role) {
        ChatRole.USER -> Row(Modifier.fillMaxWidth().padding(start = 48.dp),
            horizontalArrangement = Arrangement.End) {
            Box(Modifier.widthIn(max = 600.dp).wrapContentWidth(Alignment.End).testTag("user-message")
                .background(MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                ChatMarkdown(line.text)
            }
        }
        ChatRole.REASONING -> ChatReasoning(line.text)
        ChatRole.ASSISTANT -> ChatMarkdown(line.text, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
        ChatRole.SCRIPT -> ChatMarkdown(
            "```python\n${line.text.trimEnd()}\n```",
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
        ChatRole.TOOL -> ToolMessage(line, onOpenScript)
        ChatRole.SYSTEM -> SelectionContainer {
            Text(line.text, Modifier.fillMaxWidth().padding(horizontal = 4.dp), fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
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
