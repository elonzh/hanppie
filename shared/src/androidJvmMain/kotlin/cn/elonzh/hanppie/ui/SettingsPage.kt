package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*

@Composable
internal fun SettingsPage(model: ConsoleModel, modifier: Modifier = Modifier, onSpeechSettings: (() -> Unit)? = null) {
    val config by model.modelSettings.collectAsState()
    val autoRead by model.autoReadReplies.collectAsState()
    val chat by model.chat.state.collectAsState()
    val settingsBusy by model.settingsBusy.collectAsState()
    val settingsMessage by model.settingsMessage.collectAsState()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(tr(Res.string.language), fontSize=18.sp)
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            for ((id,label) in listOf("system" to tr(Res.string.system_default), "zh" to "简体中文", "en" to "English")) {
                Button({ model.voiceInput.cancel(); model.replySpeaker.stop(); Localization.select(id); model.speech.languageChanged() },
                    modifier=Modifier.fillMaxWidth().semantics { contentDescription="language-$id" },
                    colors=if(Localization.choice==id) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                    Text((if(Localization.choice==id) "✓ " else "") + label)
                }
            }
        }
        Text(tr(Res.string.model_service), fontSize = 18.sp)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(config.endpoint, { model.settingsMessage.value = null; model.modelSettings.value = config.copy(endpoint = it) }, label = tr(Res.string.api_endpoint), enabled = !chat.running && !settingsBusy, singleLine = true)
            TextField(config.model, { model.settingsMessage.value = null; model.modelSettings.value = config.copy(model = it) }, label = tr(Res.string.model), enabled = !chat.running && !settingsBusy, singleLine = true)
            TextField(config.apiKey, { model.settingsMessage.value = null; model.modelSettings.value = config.copy(apiKey = it) }, label = tr(Res.string.api_key), enabled = !chat.running && !settingsBusy, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(modifier = Modifier.semantics { contentDescription = tr(Res.string.read_assistant_replies_automatically) },
                    state = if (autoRead) ToggleableState.On else ToggleableState.Off, onClick = {
                        if (!settingsBusy) model.autoReadReplies.value = !autoRead
                        model.settingsMessage.value = null
                        if (autoRead) model.replySpeaker.stop()
                    })
                Text(tr(Res.string.read_replies_aloud), fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                if (onSpeechSettings != null) Button(onSpeechSettings) { Text(tr(Res.string.speech_services)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(model::saveSettings, enabled = !settingsBusy && !chat.running) { Text(tr(Res.string.save_settings)) }
                settingsMessage?.let { Text(it.resolve(), fontSize = 13.sp) }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
