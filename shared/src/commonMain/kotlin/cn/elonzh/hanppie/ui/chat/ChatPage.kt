package cn.elonzh.hanppie.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.agent.runtime.AgentSession
import cn.elonzh.hanppie.agent.runtime.toolNameFromUnknownNotice
import cn.elonzh.hanppie.agent.tools.DeleteLabScriptTool
import cn.elonzh.hanppie.agent.tools.ExecuteLabPythonTool
import cn.elonzh.hanppie.agent.tools.LabApiReferenceTool
import cn.elonzh.hanppie.agent.tools.ListLabScriptsTool
import cn.elonzh.hanppie.agent.tools.ReadLabScriptTool
import cn.elonzh.hanppie.agent.tools.RobotStatusTool
import cn.elonzh.hanppie.agent.tools.SaveLabScriptTool
import cn.elonzh.hanppie.agent.tools.StopLabTool
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

private data class ChatListScrollSnapshot(
    val followLatest: Boolean,
    val forceLatest: Boolean,
    val scrolling: Boolean,
    val scrollingBack: Boolean,
    val canScrollForward: Boolean,
    val totalItemsCount: Int,
)

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
    var deleteTarget by remember { mutableStateOf<AgentSession?>(null) }
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
    val list = rememberLazyListState()
    var followLatest by remember(state.sessionId) { mutableStateOf(true) }
    var forceLatest by remember(state.sessionId) { mutableStateOf(true) }
    var previousUserMessageRevision by remember(state.sessionId) {
        mutableLongStateOf(state.userMessageRevision)
    }
    LaunchedEffect(list, state.sessionId) {
        var automaticScroll = false
        snapshotFlow {
            ChatListScrollSnapshot(
                followLatest = followLatest,
                forceLatest = forceLatest,
                scrolling = list.isScrollInProgress,
                scrollingBack = list.lastScrolledBackward,
                canScrollForward = list.canScrollForward,
                totalItemsCount = list.layoutInfo.totalItemsCount,
            )
        }.collect { viewport ->
            if (!automaticScroll && viewport.scrolling && viewport.scrollingBack && !viewport.forceLatest) {
                followLatest = false
            } else if (!viewport.canScrollForward) {
                followLatest = true
            }

            val shouldScroll = viewport.forceLatest || (viewport.followLatest && viewport.canScrollForward)
            if (shouldScroll && (viewport.forceLatest || !viewport.scrolling) && viewport.totalItemsCount > 0) {
                withFrameNanos { }
                automaticScroll = true
                try {
                    list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
                } finally {
                    automaticScroll = false
                    forceLatest = false
                }
            }
        }
    }
    LaunchedEffect(state.sessionId, state.userMessageRevision) {
        if (state.userMessageRevision > previousUserMessageRevision) {
            followLatest = true
            forceLatest = true
        }
        previousUserMessageRevision = state.userMessageRevision
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
        val compactHistoryListHeightLimit = (maxHeight - 220.dp).coerceIn(120.dp, 420.dp)
        if (historyOpen && !expanded) {
            WorkbenchDialog(show = true, onDismissRequest = { historyOpen = false }, title = tr(Res.string.conversations)) {
                ConversationList(state.sessions, state.sessionId,
                    onOpen = { model.chat.openSession(it); historyOpen = false },
                    onNew = { model.chat.newSession(); historyOpen = false },
                    onRename = { item -> renameTitle = item.title; renameTarget = item },
                    onDelete = { id -> deleteTarget = state.sessions.firstOrNull { it.id == id } },
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
            onDelete = { id -> deleteTarget = state.sessions.firstOrNull { it.id == id } },
            enabled = state.ready,
            fillAvailableHeight = true,
            modifier = Modifier.width(280.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!expanded) Row(Modifier.fillMaxWidth()) {
            ComposerIcon(tr(Res.string.conversations), WorkbenchGlyph.CHAT,
                enabled = state.ready) { historyOpen = true }
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
                    ChatMessage(line)
                }
                if (state.running) item {
                    if (state.streaming.isNotBlank()) key(state.lines) {
                        StreamingReply(state.streaming, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                    }
                    else Text(if (state.approval != null) tr(Res.string.awaiting_approval) else tr(Res.string.agent_is_thinking),
                        Modifier.padding(12.dp), color = MiuixTheme.colorScheme.onTertiaryContainer)
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ model.chat.approve(false) }) { Text(tr(Res.string.reject)) }
                            Button({ model.chat.approve(true) }, colors = ButtonDefaults.buttonColorsPrimary()) {
                                Text(tr(approvalAction))
                            }
                        }
                    }
                } }
                item(key = "chat-bottom-anchor") {
                    Spacer(Modifier.height(1.dp).testTag("chat-bottom-anchor"))
                }
            }
            DesktopListScrollbar(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
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
        .padding(start = 12.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (item.lastMessagePreview.isNotBlank()) Text(item.lastMessagePreview, maxLines = 2,
                overflow = TextOverflow.Ellipsis, fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
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
private fun ChatMessage(line: ChatLine) {
    when (line.role) {
        ChatRole.USER -> Row(Modifier.fillMaxWidth().padding(start = 48.dp),
            horizontalArrangement = Arrangement.End) {
            Box(Modifier.widthIn(max = 600.dp).wrapContentWidth(Alignment.End).testTag("user-message")
                .background(MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                ChatMarkdown(line.text)
            }
        }
        ChatRole.ASSISTANT -> ChatMarkdown(line.text, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
        ChatRole.SCRIPT -> ChatMarkdown(
            "```python\n${line.text.trimEnd()}\n```",
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
        ChatRole.TOOL -> ToolMessage(line)
        ChatRole.SYSTEM -> SelectionContainer {
            Text(line.text, Modifier.fillMaxWidth().padding(horizontal = 4.dp), fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun ToolMessage(line: ChatLine) {
    val tool = checkNotNull(line.toolCall?.tool ?: line.toolResult?.tool) {
        "Tool activity requires a Koog tool call or result"
    }
    val result = line.toolResult
    val outcomeUnknown = result?.output?.let(::toolNameFromUnknownNotice) != null
    val arguments = line.toolCall?.args?.let(Json::parseToJsonElement)?.takeUnless(JsonElement::isEmptyPayload)
    val output = when {
        result == null || outcomeUnknown -> null
        result.isError -> JsonPrimitive(result.output)
        else -> Json.parseToJsonElement(result.output)
    }?.takeUnless(JsonElement::isEmptyPayload)
    val hasDetails = arguments != null || output != null
    val resultStatus = (output as? JsonObject).string("status")
    val rejected = resultStatus == ExecuteLabPythonTool.Status.USER_REJECTED.name ||
        resultStatus == DeleteLabScriptTool.Status.USER_REJECTED.name
    val commandSent = resultStatus == ExecuteLabPythonTool.Status.START_COMMAND_SENT.name ||
        resultStatus == StopLabTool.Status.STOP_COMMAND_SENT.name
    val summary = toolSummary(tool, arguments, output, result?.isError == true)
    var detailsVisible by remember(result?.id, line.toolCall?.args) { mutableStateOf(false) }
    val status = when {
        result == null -> tr(Res.string.tool_in_progress)
        outcomeUnknown -> tr(Res.string.tool_result_unknown)
        result.isError -> tr(Res.string.tool_failed)
        rejected -> tr(Res.string.tool_canceled)
        commandSent -> tr(Res.string.tool_command_sent)
        else -> tr(Res.string.tool_completed)
    }
    val statusColor = if (result?.isError == true || outcomeUnknown) {
        MiuixTheme.colorScheme.error
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Column(
        Modifier.fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = .65f), RoundedCornerShape(12.dp))
            .testTag("tool-message-$tool"),
    ) {
        val headerModifier = if (hasDetails) {
            Modifier.toggleable(
                value = detailsVisible,
                role = Role.Button,
                onValueChange = { detailsVisible = it },
            ).semantics {
                stateDescription = tr(
                    if (detailsVisible) Res.string.tool_details_expanded else Res.string.tool_details_collapsed,
                )
            }
        } else {
            Modifier
        }
        Row(
            headerModifier.fillMaxWidth().testTag("tool-message-header-$tool")
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            WorkbenchIcon(toolGlyph(tool), MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(18.dp))
            Text(toolLabel(tool), Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(status, color = statusColor, fontSize = 11.sp)
            if (hasDetails) {
                WorkbenchIcon(
                    WorkbenchGlyph.CHEVRON_RIGHT,
                    MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    Modifier.size(16.dp).rotate(if (detailsVisible) 90f else 0f),
                )
            }
        }
        if (summary != null && !detailsVisible) {
            Text(
                summary,
                Modifier.fillMaxWidth().padding(start = 39.dp, end = 12.dp, bottom = 9.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (result?.isError == true) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                fontSize = 11.sp,
            )
        }
        if (detailsVisible) {
            Column(
                Modifier.fillMaxWidth().padding(start = 39.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                arguments?.let { ToolPayload(tr(Res.string.tool_parameters), it) }
                output?.let { ToolPayload(tr(Res.string.tool_result), it) }
            }
        }
    }
}

@Composable
private fun ToolPayload(title: String, payload: JsonElement) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        SelectionContainer { StructuredJson(payload) }
    }
}

@Composable
private fun StructuredJson(value: JsonElement, field: String? = null) {
    when (value) {
        JsonNull -> ToolField(field, "—")
        is JsonPrimitive -> {
            if (field == "source" && value.isString) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolFieldName(field)
                    ChatMarkdown("```python\n${value.content.trimEnd()}\n```", Modifier.fillMaxWidth())
                }
            } else {
                ToolField(field, value.content)
            }
        }
        is JsonObject -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            value.forEach { (name, child) ->
                if (child is JsonObject || child is JsonArray) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ToolFieldName(name)
                        Box(Modifier.padding(start = 10.dp)) { StructuredJson(child) }
                    }
                } else {
                    StructuredJson(child, name)
                }
            }
        }
        is JsonArray -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            value.forEach { child ->
                if (child is JsonObject) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        StructuredJson(child)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("•", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
                        Box(Modifier.weight(1f)) { StructuredJson(child, field = null) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolField(field: String?, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        field?.let { ToolFieldName(it, Modifier.widthIn(min = 104.dp, max = 160.dp)) }
        Text(
            value,
            Modifier.weight(1f, fill = false),
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ToolFieldName(field: String, modifier: Modifier = Modifier) {
    Text(
        field,
        modifier,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun toolSummary(
    tool: String,
    arguments: JsonElement?,
    output: JsonElement?,
    resultIsError: Boolean,
): String? {
    if (output is JsonPrimitive) {
        return if (resultIsError) tr(Res.string.tool_failure_summary) else output.content.lineSequence().firstOrNull()
    }
    val args = arguments as? JsonObject
    val result = output as? JsonObject
    return when (tool) {
        RobotStatusTool.NAME -> if (result == null) {
            null
        } else {
            val connection = if (result["connected"]?.jsonPrimitive?.booleanOrNull == true) {
                tr(Res.string.connected)
            } else {
                tr(Res.string.disconnected)
            }
            result["batteryPercent"]?.jsonPrimitive?.intOrNull?.let { battery ->
                "$connection · ${tr(Res.string.tool_battery_summary, battery)}"
            } ?: connection
        }
        LabApiReferenceTool.NAME -> args.string("query")?.let { tr(Res.string.tool_query_summary, it) }
        ListLabScriptsTool.NAME -> (result?.get("scripts") as? JsonArray)?.size?.let {
            tr(Res.string.tool_scripts_summary, it)
        }
        ReadLabScriptTool.NAME,
        SaveLabScriptTool.NAME -> args.string("name")?.let { tr(Res.string.tool_script_summary, it) }
        DeleteLabScriptTool.NAME -> if (result.string("status") == DeleteLabScriptTool.Status.USER_REJECTED.name) {
            tr(Res.string.delete_script_rejected_summary)
        } else {
            (result.string("name") ?: args.string("name"))?.let { tr(Res.string.tool_script_summary, it) }
        }
        ExecuteLabPythonTool.NAME -> if (result.string("status") == ExecuteLabPythonTool.Status.USER_REJECTED.name) {
            tr(Res.string.execute_script_rejected_summary)
        } else {
            args.string("source")?.length?.let { tr(Res.string.tool_source_summary, it) }
        }
        StopLabTool.NAME -> if (result.string("status") == StopLabTool.Status.STOP_COMMAND_SENT.name) {
            tr(Res.string.stop_command_sent_robot_stop_is_unconfirmed)
        } else null
        else -> if (result?.isNotEmpty() == true) result.entries.first().value.summaryValue() else null
    }
}

private fun JsonObject?.string(name: String): String? =
    this?.get(name)?.jsonPrimitive?.contentOrNull

private fun JsonElement.summaryValue(): String? = when (this) {
    JsonNull -> null
    is JsonPrimitive -> content
    is JsonArray -> size.toString()
    is JsonObject -> entries.firstOrNull()?.value?.summaryValue()
}

private fun JsonElement.isEmptyPayload(): Boolean =
    (this is JsonObject && isEmpty()) || (this is JsonArray && isEmpty())

@Composable
private fun toolLabel(tool: String): String = when (tool) {
    RobotStatusTool.NAME -> tr(Res.string.read_status)
    LabApiReferenceTool.NAME -> tr(Res.string.inspect_lab_api)
    ListLabScriptsTool.NAME -> tr(Res.string.list_lab_scripts)
    ReadLabScriptTool.NAME -> tr(Res.string.read_lab_script)
    SaveLabScriptTool.NAME -> tr(Res.string.save_lab_script)
    DeleteLabScriptTool.NAME -> tr(Res.string.delete_lab_script)
    ExecuteLabPythonTool.NAME -> tr(Res.string.run_lab_script)
    StopLabTool.NAME -> tr(Res.string.stop_script)
    else -> tool
}

private fun toolGlyph(tool: String): WorkbenchGlyph = when (tool) {
    RobotStatusTool.NAME -> WorkbenchGlyph.ACTIVITY
    LabApiReferenceTool.NAME -> WorkbenchGlyph.SEARCH
    ListLabScriptsTool.NAME -> WorkbenchGlyph.FOLDER
    ReadLabScriptTool.NAME -> WorkbenchGlyph.FILE_TEXT
    SaveLabScriptTool.NAME -> WorkbenchGlyph.SAVE
    DeleteLabScriptTool.NAME -> WorkbenchGlyph.DELETE
    ExecuteLabPythonTool.NAME -> WorkbenchGlyph.CODE
    StopLabTool.NAME -> WorkbenchGlyph.STOP
    else -> WorkbenchGlyph.ACTIVITY
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
