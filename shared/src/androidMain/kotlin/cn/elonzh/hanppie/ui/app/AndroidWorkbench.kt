package cn.elonzh.hanppie.ui.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.session.RobotNetwork
import cn.elonzh.hanppie.robot.session.JvmRobotRuntime
import cn.elonzh.hanppie.ui.chat.createAgentHttpClient
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.remote.AndroidSpeakerInput
import cn.elonzh.hanppie.ui.speech.AndroidSpeech
import cn.elonzh.hanppie.ui.speech.AndroidSpeechInput
import java.net.DatagramSocket
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text

private class AndroidNetworkResources(app: Application) : AutoCloseable {
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val multicast = wifi.createMulticastLock("hanppie-discovery").apply { setReferenceCounted(false) }
    private fun wifiNetwork() = connectivity.allNetworks.firstOrNull {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: error(tr(Res.string.connect_to_the_robot_s_wi_fi_first))
    fun network(): RobotNetwork {
        val network = wifiNetwork()
        return object : RobotNetwork {
            override fun datagram() = DatagramSocket(null).apply { network.bindSocket(this) }
            override val socketFactory get() = network.socketFactory
        }
    }

    fun prepare() {
        wifiNetwork()
        if (!multicast.isHeld) multicast.acquire()
    }

    fun releaseMulticast() {
        if (multicast.isHeld) multicast.release()
    }

    override fun close() = releaseMulticast()
}

private fun createAndroidWorkbenchViewModel(app: Application, systemLanguage: String): WorkbenchViewModel {
    val storage = WorkbenchStorage.create()
    val network = AndroidNetworkResources(app)
    val speechInput = AndroidSpeechInput(app)
    return try {
        val model = ConsoleModel(
            speech = AndroidSpeech(app),
            voiceInput = speechInput,
            speakerInput = AndroidSpeakerInput(app),
            robotRuntime = JvmRobotRuntime(network::network),
            settingsStore = storage.settings,
            scriptRepository = storage.scripts,
            createAgentHttpClient = ::createAgentHttpClient,
            prepareNetwork = network::prepare,
        )
        WorkbenchViewModel(
            model = model,
            storage = storage,
            systemLanguage = systemLanguage,
            applyPlatformPreferences = { speechInput.loadService(it.speechService) },
            pausePlatformResources = network::releaseMulticast,
            releasePlatformResources = network::close,
        )
    } catch (error: Exception) {
        speechInput.close()
        network.close()
        storage.close()
        throw error
    }
}

@Composable
fun AndroidWorkbench() {
    val context = LocalContext.current
    val systemLanguage = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].toLanguageTag()
    val holder: WorkbenchViewModel = viewModel(factory = viewModelFactory {
        initializer { createAndroidWorkbenchViewModel(context.applicationContext as Application, systemLanguage) }
    })
    val speechInput = holder.model.voiceInput as AndroidSpeechInput
    LaunchedEffect(systemLanguage) { holder.updateSystemLanguage(systemLanguage) }
    val owner = LocalLifecycleOwner.current
    var voiceDisclosure by remember { mutableStateOf(false) }
    var audioSettings by remember { mutableStateOf(false) }
    val selectedSpeechService by speechInput.selectedService.collectAsState()
    var voiceDisclosureAccepted by remember { mutableStateOf(false) }
    var talkRequested by remember { mutableStateOf(false) }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (holder.model.isForeground && holder.model.voicePageActive) {
            if (granted) speechInput.start() else speechInput.permissionDenied()
        }
    }
    val talkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && talkRequested && holder.model.isForeground) holder.model.beginPushToTalk()
        else if (!granted) talkRequested = false
    }
    fun startVoice() {
        if (!holder.model.isForeground || !holder.model.voicePageActive) return
        holder.model.replySpeaker.stop()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) speechInput.start()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun startTalk() {
        talkRequested = true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            holder.model.beginPushToTalk()
        else talkPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun stopTalk() {
        talkRequested = false
        holder.model.endPushToTalk()
    }
    var confirmExit by remember { mutableStateOf(false) }
    val state by holder.model.state.collectAsState()
    BackHandler(enabled = holder.document.value.dirty || state.connected || state.busy) { confirmExit = true }
    DisposableEffect(owner, holder) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && (context as? Activity)?.isChangingConfigurations != true) {
                holder.pauseConnection()
            }
            if (event == Lifecycle.Event.ON_START) holder.resumeConnection()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    WorkbenchTheme(holder.appearance) {
        WorkbenchDialog(show = audioSettings, onDismissRequest = { audioSettings = false }, title = tr(Res.string.speech_services)) {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(Res.string.speech_recognition))
                speechInput.services().forEach { (id, label) ->
                    Button({ speechInput.selectService(id); holder.saveSpeechService(id) }) {
                        Text(if (selectedSpeechService == id) tr(Res.string.value_selected,label) else tr(Res.string.use_value,label))
                    }
                    Button({
                        audioSettings = false
                        val component = android.content.ComponentName.unflattenFromString(id)
                        try {
                            requireNotNull(component)
                            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", component.packageName, null)))
                        } catch (_: Exception) { holder.updateFileError(tr(Res.string.could_not_open_recognition_service_permissions)) }
                    }) { Text(tr(Res.string.value_permissions,label)) }
                }
                Button({
                    audioSettings = false
                    try { context.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                    catch (_: Exception) { holder.updateFileError(tr(Res.string.could_not_open_default_voice_input_settings)) }
                }) { Text(tr(Res.string.default_voice_input)) }
                Button({
                    audioSettings = false
                    try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    catch (_: Exception) { holder.updateFileError(tr(Res.string.could_not_open_text_to_speech_settings)) }
                }, modifier = Modifier.testTag("open-tts-settings")) { Text(tr(Res.string.text_to_speech_settings)) }
            }
        }
        WorkbenchDialog(show = voiceDisclosure, onDismissRequest = { voiceDisclosure = false }, title = tr(Res.string.use_phone_microphone),
            summary = tr(Res.string.your_system_speech_service_may_process_audio_online_recognized)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ voiceDisclosure = false }) { Text(tr(Res.string.cancel)) }
                Button({ voiceDisclosureAccepted = true; voiceDisclosure = false; startVoice() }) { Text(tr(Res.string.action_continue)) }
            }
        }
        WorkbenchDialog(show = confirmExit, onDismissRequest = { confirmExit = false }, title = tr(Res.string.quit_hanppie_2),
            summary = tr(Res.string.unsaved_changes_will_be_lost_disconnecting_does_not_guarantee)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ confirmExit = false }) { Text(tr(Res.string.back)) }
                Button({ (context as? Activity)?.finish() }) { Text(tr(Res.string.quit_anyway)) }
            }
        }
        Console(holder.model, holder.document, onImport = holder::importScript,
            onVoiceInput = { if (voiceDisclosureAccepted) startVoice() else voiceDisclosure = true },
            onPushToTalkStart = ::startTalk, onPushToTalkStop = ::stopTalk,
            onExport = holder::exportScript, fileError = holder.fileError, onFileError = holder::updateFileError,
            onSpeechSettings = { audioSettings = true },
            onRobotFileUpload = holder::uploadRobotFile,
            onRobotFileDownload = holder::downloadRobotFile,
            onRobotFileOpen = holder::openRobotFile)
    }
}
internal fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
