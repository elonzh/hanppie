package cn.elonzh.hanppie.desktop

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
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(tr("语言"), fontSize=18.sp)
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            for ((id,label) in listOf("system" to tr("跟随系统"), "zh" to "简体中文", "en" to "English")) {
                Button({ model.voiceInput.cancel(); model.replySpeaker.stop(); Localization.select(id); model.speech.languageChanged() },
                    modifier=Modifier.fillMaxWidth().semantics { contentDescription="language-$id" },
                    colors=if(Localization.choice==id) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                    Text((if(Localization.choice==id) "✓ " else "") + label)
                }
            }
        }
        Text(tr("模型服务"), fontSize = 18.sp)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(config.endpoint, { model.modelSettings.value = config.copy(endpoint = it) }, label = tr("API 地址"), enabled = !chat.running, singleLine = true)
            TextField(config.model, { model.modelSettings.value = config.copy(model = it) }, label = tr("模型"), enabled = !chat.running, singleLine = true)
            TextField(config.apiKey, { model.modelSettings.value = config.copy(apiKey = it) }, label = tr("API Key · 仅本次运行"), enabled = !chat.running, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(modifier = Modifier.semantics { contentDescription = tr("自动朗读智能体回复") },
                    state = if (autoRead) ToggleableState.On else ToggleableState.Off, onClick = {
                        model.autoReadReplies.value = !autoRead
                        if (autoRead) model.replySpeaker.stop()
                    })
                Text(tr("自动朗读"), fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                if (onSpeechSettings != null) Button(onSpeechSettings) { Text(tr("语音服务")) }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
