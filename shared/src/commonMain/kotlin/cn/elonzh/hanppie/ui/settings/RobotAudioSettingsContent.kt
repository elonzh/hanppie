package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.media.SpeakerLanguage
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.audio.RobotAudioController
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RobotAudioSettingsContent(controller: RobotAudioController, connected: Boolean, volume: Int, enabled: Boolean) {
    val state by controller.state.collectAsState()
    LaunchedEffect(controller, connected) { if (connected) controller.refresh() }
    Text(tr(Res.string.robot_audio_settings), fontSize = 15.sp)
    Card(Modifier.fillMaxWidth().testTag("robot-audio-settings"),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                !connected -> Text(tr(Res.string.robot_audio_connect_first), fontSize = 14.sp)
                state.busy -> Text(tr(Res.string.robot_audio_reading), Modifier.testTag("robot-audio-busy"), fontSize = 14.sp)
                else -> {
                    state.error?.let { Text(it.resolve(), Modifier.testTag("robot-audio-error"),
                        color = MiuixTheme.colorScheme.error, fontSize = 14.sp) }
                    val current = state.settings
                    if (current != null) {
                        Text(tr(Res.string.robot_audio_current_volume, current.volume), fontSize = 14.sp)
                        Button({ controller.applyVolume(volume) }, enabled = enabled,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("robot-audio-apply-volume")) {
                            Text(tr(Res.string.robot_audio_apply_volume, volume))
                        }
                        SettingsDropdown(tr(Res.string.robot_speaker_language), "robot-speaker-language",
                            current.language?.name ?: tr(Res.string.unknown),
                            SpeakerLanguage.entries.map { it.name to speakerLanguageName(it) }, enabled = enabled) { name ->
                            controller.setLanguage(SpeakerLanguage.valueOf(name))
                        }
                        if (current.language == null) Text(tr(Res.string.robot_speaker_language_unknown), fontSize = 13.sp)
                    }
                    Button(controller::refresh, enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("robot-audio-refresh")) { Text(tr(Res.string.refresh)) }
                }
            }
        }
    }
}

private fun speakerLanguageName(language: SpeakerLanguage): String = tr(when (language) {
    SpeakerLanguage.ENGLISH -> Res.string.speaker_language_en
    SpeakerLanguage.CHINESE -> Res.string.speaker_language_zh
    SpeakerLanguage.JAPANESE -> Res.string.speaker_language_ja
    SpeakerLanguage.GERMAN -> Res.string.speaker_language_de
    SpeakerLanguage.SPANISH -> Res.string.speaker_language_es
    SpeakerLanguage.KOREAN -> Res.string.speaker_language_ko
    SpeakerLanguage.FRENCH -> Res.string.speaker_language_fr
    SpeakerLanguage.RUSSIAN -> Res.string.speaker_language_ru
})
