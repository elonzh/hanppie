package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.session.PlatformRobotRuntime
import cn.elonzh.hanppie.ui.chat.createAgentHttpClient
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.platform.*
import cn.elonzh.hanppie.ui.robot.audio.*
import cn.elonzh.hanppie.ui.robot.remote.IosSpeakerInput
import cn.elonzh.hanppie.ui.robot.scene.LocalRobotSceneTransparency
import cn.elonzh.hanppie.ui.speech.IosSpeechInput
import platform.Foundation.*
import platform.UIKit.UIViewController
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

fun MainViewController(platform: IosPlatformServices): UIViewController = ComposeUIViewController(
    configure = {
        // Shared imePadding owns keyboard layout; UIKit panning would apply a second offset.
        onFocusBehavior = OnFocusBehavior.DoNothing
    },
) {
    // Filament's transparent UIKit surface is placed above Compose, hiding home controls.
    CompositionLocalProvider(LocalIosPlatformServices provides platform, LocalRobotSceneTransparency provides false) { IosWorkbench(platform) }
}

private fun createIosWorkbench(platform: IosPlatformServices): WorkbenchViewModel {
    val storage = WorkbenchStorage.create()
    return try {
        lateinit var model: ConsoleModel
        model = ConsoleModel(
            voiceInput = IosSpeechInput(platform),
            speakerInput = IosSpeakerInput(platform) { message -> model.endPushToTalk(); model.log(message) },
            audioImporter = IosLabAudioImporter(platform),
            audioPlayer = IosLabAudioPlayer(platform),
            robotRuntime = PlatformRobotRuntime(),
            settingsStore = storage.settings,
            scriptRepository = storage.scripts,
            sessionHistory = storage.sessions,
            skills = storage.skills::await,
            createAgentHttpClient = ::createAgentHttpClient,
        )
        WorkbenchViewModel(model, storage, NSLocale.preferredLanguages.firstOrNull() as? String ?: "en-US",
            pausePlatformResources = { platform.cancelTalk(); platform.cancelSpeech(); platform.stopVideo(); platform.stopAudio() })
    } catch (error: Exception) { storage.close(); throw error }
}

@Composable
private fun IosWorkbench(platform: IosPlatformServices) {
    val holder = remember { createIosWorkbench(platform) }
    val owner = LocalLifecycleOwner.current
    var voiceDisclosure by remember { mutableStateOf(false) }
    var voiceDisclosureAccepted by remember { mutableStateOf(false) }
    var wifiGuidance by remember { mutableStateOf(false) }
    DisposableEffect(holder, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) holder.pauseConnection()
            if (event == Lifecycle.Event.ON_START) holder.resumeConnection()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); holder.shutdown() }
    }
    fun startVoice() { if (holder.model.isForeground && holder.model.voicePageActive) holder.model.voiceInput.start() }
    WorkbenchTheme(holder.appearance) {
        WorkbenchDialog(show = voiceDisclosure, onDismissRequest = { voiceDisclosure = false },
            title = tr(Res.string.use_phone_microphone), summary = tr(Res.string.your_system_speech_service_may_process_audio_online_recognized)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ voiceDisclosure = false }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(Res.string.cancel)) }
                Button({ voiceDisclosureAccepted = true; voiceDisclosure = false; startVoice() },
                    Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.action_continue)) }
            }
        }
        WorkbenchDialog(show = wifiGuidance, onDismissRequest = { wifiGuidance = false },
            title = tr(Res.string.connection_settings), summary = tr(Res.string.ios_wifi_guidance)) {
            Button({ wifiGuidance = false }) { Text(tr(Res.string.back)) }
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Console(holder.model, holder.document,
                onImport = holder::importScript, onImportAudio = holder::importScriptAudio, onExport = holder::exportScript,
                onVoiceInput = { if (voiceDisclosureAccepted) startVoice() else voiceDisclosure = true },
                onPushToTalkStart = holder.model::beginPushToTalk, onPushToTalkStop = holder.model::endPushToTalk,
                fileError = holder.fileError, onFileError = holder::updateFileError,
                onRobotFileUpload = holder::uploadRobotFile, onRobotFileDownload = holder::downloadRobotFile,
                onRobotFileOpen = holder::openRobotFile, onOpenWifiSettings = { wifiGuidance = true })
        }
    }
}
