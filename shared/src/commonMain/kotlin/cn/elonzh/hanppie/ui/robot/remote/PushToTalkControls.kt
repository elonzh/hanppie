package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.*
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun PushToTalkControls(controller: PushToTalkController, state: PushToTalkState, enabled: Boolean,
    onPress: (Boolean) -> Unit, onToggle: () -> Unit) {
    var details by remember(state.error) { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 160.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.active) Text(tr(when (state.phase) {
            TalkPhase.PREPARING -> Res.string.microphone_preparing
            TalkPhase.RECORDING -> Res.string.talking
            else -> Res.string.sending_talk
        }), Modifier.background(HanppieDesignTokens.RemoteHudSurface).padding(4.dp).testTag("push-to-talk-status"),
            color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp)
        Row {
            if (controller.available) HudIconButton(tr(when (state.phase) {
                TalkPhase.PREPARING -> Res.string.cancel
                TalkPhase.RECORDING -> Res.string.talk_send
                TalkPhase.SENDING -> Res.string.sending_talk
                else -> Res.string.talk_hold
            }), WorkbenchGlyph.MICROPHONE, enabled && !state.busy, selected = state.recording,
                modifier = Modifier.testTag("push-to-talk-button"), onPressChange = onPress, onAccessibilityClick = onToggle)
            if (state.error != null) HudIconButton(tr(Res.string.talk_problem_details), WorkbenchGlyph.INFO,
                modifier = Modifier.testTag("push-to-talk-problem"), selected = true) { details = true }
        }
    }
    WorkbenchDialog(details && state.error != null, { details = false }, tr(Res.string.talk_problem_title)) {
        state.error?.let { Text(it.resolve(), Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())
            .testTag("push-to-talk-error"), fontSize = 14.sp) }
        Text(tr(Res.string.talk_retry_hint), fontSize = 14.sp)
        Button({ controller.dismissError(); details = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("push-to-talk-error-close")) { Text(tr(Res.string.close)) }
    }
}
