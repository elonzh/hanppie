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
import cn.elonzh.hanppie.ui.robot.audio.AudioSetting
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
                    state.error?.let { SettingsNotice(it.resolve(), Modifier.testTag("robot-audio-error"), error = true) }
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
    if (connected) {
        Text(tr(Res.string.native_audio_title), fontSize = 15.sp)
        Card(Modifier.fillMaxWidth().testTag("robot-audio-signal"),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val sound = state.soundEnabled) {
                    is AudioSetting.Value -> Text(tr(if (sound.value) Res.string.native_audio_snapshot_on else Res.string.native_audio_snapshot_off),
                        Modifier.testTag("robot-sound-snapshot"), fontSize = 14.sp)
                    is AudioSetting.Failed -> SettingsNotice(sound.message.resolve(), Modifier.testTag("robot-sound-error"), error = true)
                    else -> Text(tr(if (sound == AudioSetting.Loading) Res.string.native_audio_reading else Res.string.native_audio_unknown), fontSize = 14.sp)
                }
                Text(tr(Res.string.native_audio_scope), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                val playback = state.playback
                Text(when {
                    playback == null -> tr(Res.string.robot_playback_wait)
                    !playback.playing -> tr(Res.string.robot_playback_stopped)
                    playback.percent != null -> tr(Res.string.robot_playback_progress, playback.percent!!)
                    else -> tr(Res.string.robot_playback_running)
                }, Modifier.testTag("robot-playback-status"), fontSize = 14.sp)
                val feedback = state.antiFeedback
                SettingsDropdown(tr(Res.string.anti_feedback), "robot-anti-feedback",
                    (feedback as? AudioSetting.Value)?.value?.toString() ?: tr(Res.string.anti_feedback_choose),
                    listOf("true" to tr(Res.string.native_audio_on), "false" to tr(Res.string.native_audio_off)),
                    enabled && feedback != AudioSetting.Loading) { controller.setAntiFeedback(it == "true") }
                when (feedback) {
                    AudioSetting.Loading -> Text(tr(Res.string.anti_feedback_applying), Modifier.testTag("robot-feedback-result"), fontSize = 14.sp)
                    is AudioSetting.Value -> Unit
                    is AudioSetting.Failed -> SettingsNotice(feedback.message.resolve(), Modifier.testTag("robot-feedback-result"), error = true)
                    AudioSetting.Unread -> Unit
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
