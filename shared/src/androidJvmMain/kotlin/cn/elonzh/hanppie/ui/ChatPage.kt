package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.state.ToggleableState
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
        if (list.layoutInfo.totalItemsCount > 0) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Spacer(Modifier.weight(1f))
            Button({ model.replySpeaker.stop(); model.chat.clear() }, enabled = !state.running && state.lines.isNotEmpty()) { Text(tr(Res.string.new_chat)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat-messages"), state = list,
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (state.lines.isEmpty()) item {
                Column(Modifier.padding(top = 28.dp, bottom = 24.dp)) {
                    Text(tr(Res.string.what_would_you_like_to_do), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(tr(Res.string.chat_or_create_a_robot_script_together), Modifier.padding(top = 8.dp), color = Color(0xff78818d), fontSize = 14.sp)
                }
            }
            items(state.lines) { line ->
                Column(Modifier.fillMaxWidth().background(if (line.role == ChatRole.USER) Color(0xffe9efff) else Color.White,
                    RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text(tr(line.role.label), fontSize = 11.sp, color = Color(0xff78818d))
                    SelectionContainer { Text(line.text, Modifier.padding(top = 6.dp), fontSize = if (line.role in listOf(ChatRole.TOOL, ChatRole.SCRIPT)) 12.sp else 15.sp,
                        fontFamily = if (line.role == ChatRole.SCRIPT) FontFamily.Monospace else FontFamily.Default) }
                    if (line.role == ChatRole.ASSISTANT) Text(tr(Res.string.read_aloud), Modifier.sizeIn(minHeight = 48.dp)
                        .clickable(enabled = speech.available && !microphone.active, role = Role.Button) { model.replySpeaker.speak(line.text) }
                        .padding(top = 14.dp), color = Color(0xff3868e8), fontSize = 12.sp)
                }
            }
            if (state.running) item {
                Text(state.streaming.ifBlank { if (state.approval != null) tr(Res.string.awaiting_approval) else tr(Res.string.hanppie_is_thinking) }, Modifier.padding(12.dp), fontSize = 15.sp)
            }
            state.approval?.let { source -> item {
                Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text(tr(Res.string.run_this_script), fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(source, Modifier.padding(vertical = 12.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ model.chat.approve(false) }) { Text(tr(Res.string.reject)) }
                        Button({ model.chat.approve(true) }, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.approve_and_run)) }
                    }
                }
            } }
        }
        if (speech.speaking) Button(model.replySpeaker::stop) { Text(tr(Res.string.stop_reading)) }
        state.error?.let { Text(it, color = Color(0xffc64848), fontSize = 12.sp) }
        microphone.error?.let { Text(it, color = Color(0xffc64848), fontSize = 12.sp) }
        if (autoRead) speech.error?.let { Text(it, color = Color(0xffc64848), fontSize = 12.sp) }
        if (microphone.active) Text(microphone.partial.ifBlank { if (microphone.processing) tr(Res.string.recognizing) else tr(Res.string.listening) }, fontSize = 13.sp)
        state.elapsedMs?.let { Text(tr(Res.string.first_token_value_turn_value_ms,state.firstTokenMs?.let { "${it}ms" } ?: "—",it), fontSize = 11.sp, color = Color(0xff78818d)) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    val background = if (primary) Color(0xff3868e8) else Color(0xffe9ecf2)
    val ink = if (primary) Color.White else Color(0xff596577)
    Box(modifier.size(48.dp).background(background.copy(alpha = if (enabled) 1f else .35f), RoundedCornerShape(16.dp))
        .semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            val w = size.width; val h = size.height; val stroke = 2.dp.toPx()
            when (icon) {
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
