package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ChatPage(model: ConsoleModel, modifier: Modifier = Modifier, onVoiceInput: (() -> Unit)? = null, onSettings: () -> Unit = {}) {
    val state by model.chat.state.collectAsState()
    val config by model.modelSettings.collectAsState()
    val microphone by model.voiceInput.state.collectAsState()
    val speech by model.speech.state.collectAsState()
    val autoRead by model.autoReadReplies.collectAsState()
    val enteredAtRevision = remember(model) { model.chat.state.value.replyRevision }
    DisposableEffect(model) {
        model.voicePageActive = true
        onDispose { model.voicePageActive = false; model.voiceInput.cancel(); model.replySpeaker.stop() }
    }
    LaunchedEffect(state.replyRevision) {
        if (state.replyRevision > enteredAtRevision && autoRead && model.isForeground && !microphone.active)
            model.replySpeaker.speak(state.lastReply)
    }
    var input by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(microphone.resultId) {
        if (microphone.result.isNotBlank()) {
            input = (input + (if (input.isBlank()) "" else "\n") + microphone.result).take(12000)
            model.voiceInput.consumeResult()
        }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    LaunchedEffect(state.lines.size, state.streaming.length, state.approval) {
        val count = list.layoutInfo.totalItemsCount
        val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (count > 0 && !list.isScrollInProgress && lastVisible >= count - 2) list.animateScrollToItem(count - 1)
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Spacer(Modifier.weight(1f))
            ComposerIcon(tr(Res.string.new_chat), "new", enabled = !state.running && state.lines.isNotEmpty()) { model.replySpeaker.stop(); model.chat.clear() }
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
                        if (line.role == ChatRole.ASSISTANT) ComposerIcon(tr(Res.string.read_aloud), "speaker",
                            enabled = speech.available && !microphone.active) { model.replySpeaker.speak(line.text) }
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
        if (speech.speaking) Button(model.replySpeaker::stop) { Text(tr(Res.string.stop_reading)) }
        state.error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        microphone.error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        if (autoRead) speech.error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        if (microphone.active) Text(microphone.partial.ifBlank { if (microphone.processing) tr(Res.string.recognizing) else tr(Res.string.listening) }, fontSize = 13.sp)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            TextField(input, { if (it.length <= 12000) input = it }, Modifier.weight(1f).testTag("chat-input"), label = tr(Res.string.message), maxLines = 4, enabled = !microphone.active)

            if (onVoiceInput != null) ComposerIcon(
                if (!microphone.active) tr(Res.string.voice_input) else if (microphone.processing) tr(Res.string.cancel_recognition) else tr(Res.string.finish_recording),
                if (microphone.active) "stop" else "mic", enabled = !state.running,
                modifier = Modifier.testTag("voice-input"),
            ) {
                if (microphone.active) {
                    if (microphone.processing) model.voiceInput.cancel() else model.voiceInput.finish()
                } else { model.replySpeaker.stop(); onVoiceInput() }
            }
            ComposerIcon(if (state.running) tr(Res.string.cancel) else tr(Res.string.send), if (state.running) "stop" else "send",
                enabled = state.running || (input.isNotBlank() && !microphone.active), primary = true) {
                model.replySpeaker.stop()
                if (state.running) model.chat.cancel()
                else {
                    if (config.apiKey.isBlank()) { focus.clearFocus(); keyboard?.hide(); onSettings(); return@ComposerIcon }
                    model.chat.send(input, config)
                    if (model.chat.state.value.running) input = ""
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}


@Composable
private fun ComposerIcon(label: String, icon: String, enabled: Boolean, modifier: Modifier = Modifier,
                         primary: Boolean = false, onClick: () -> Unit) {
    val background = if (primary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh
    val ink = if (primary) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    IconButton(onClick = onClick, enabled = enabled, cornerRadius = 14.dp,
        modifier = modifier.size(48.dp).semantics { contentDescription = label; role = Role.Button; if (!enabled) disabled() },
        backgroundColor = background.copy(alpha = if (enabled) 1f else .35f)) {
        Canvas(Modifier.size(24.dp)) {
            val w = size.width; val h = size.height; val stroke = 2.dp.toPx()
            when (icon) {
                "new" -> {
                    drawLine(ink, Offset(w*.5f,h*.2f), Offset(w*.5f,h*.8f),stroke)
                    drawLine(ink, Offset(w*.2f,h*.5f), Offset(w*.8f,h*.5f),stroke)
                }
                "speaker" -> {
                    drawRect(ink, Offset(w*.15f,h*.35f),Size(w*.2f,h*.3f))
                    drawLine(ink,Offset(w*.35f,h*.35f),Offset(w*.55f,h*.2f),stroke)
                    drawLine(ink,Offset(w*.55f,h*.2f),Offset(w*.55f,h*.8f),stroke)
                    drawLine(ink,Offset(w*.55f,h*.8f),Offset(w*.35f,h*.65f),stroke)
                    drawArc(ink,-60f,120f,false,Offset(w*.4f,h*.18f),Size(w*.5f,h*.64f),style=Stroke(stroke))
                }
                "mic" -> {
                    drawRoundRect(ink, Offset(w*.35f,h*.08f), Size(w*.3f,h*.5f),
                        androidx.compose.ui.geometry.CornerRadius(w*.15f), style = Stroke(stroke))
                    drawArc(ink, 0f, 180f, false, Offset(w*.18f,h*.2f), Size(w*.64f,h*.58f), style = Stroke(stroke))
                    drawLine(ink,Offset(w*.5f,h*.78f),Offset(w*.5f,h*.95f),stroke)
                }
                "stop" -> drawRoundRect(ink,Offset(w*.23f,h*.23f),Size(w*.54f,h*.54f),androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                else -> {
                    drawLine(ink,Offset(w*.5f,h*.82f),Offset(w*.5f,h*.18f),stroke)
                    drawLine(ink,Offset(w*.5f,h*.18f),Offset(w*.23f,h*.45f),stroke)
                    drawLine(ink,Offset(w*.5f,h*.18f),Offset(w*.77f,h*.45f),stroke)
                }
            }
        }
    }
}
