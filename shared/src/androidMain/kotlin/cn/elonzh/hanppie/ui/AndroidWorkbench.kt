package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import android.app.Application
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cn.elonzh.hanppie.robot.RobotNetwork
import java.net.DatagramSocket
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text

internal class AndroidWorkbenchModel(app: Application) : ViewModel() {
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val multicast = wifi.createMulticastLock("hanppie-discovery").apply { setReferenceCounted(false) }
    private fun wifiNetwork() = connectivity.allNetworks.firstOrNull {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: error(tr(Res.string.connect_to_the_robot_s_wi_fi_first))
    val speechInput = AndroidSpeechInput(app)
    val model = ConsoleModel(AndroidSpeech(app), voiceInput = speechInput, speakerInput = AndroidSpeakerInput(app),
        settingsStore = AndroidSettingsStore(app),
        scriptStore = JsonScriptStore(app.filesDir.toPath().resolve("script-library-v1.json")),
        robotNetwork = {
        val network = wifiNetwork()
        object : RobotNetwork {
            override fun datagram() = DatagramSocket(null).apply { network.bindSocket(this) }
            override val socketFactory get() = network.socketFactory
        }
    }) {
        wifiNetwork()
        if (!multicast.isHeld) multicast.acquire()
    }
    val document = mutableStateOf(EditorDocument())
    var fileError by mutableStateOf<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transition: Job? = null
    private var foreground = true

    fun pause() {
        foreground = false
        model.setForeground(false)
        model.speech.stop()
        val previous = transition
        transition = scope.launch {
            previous?.join()
            withContext(Dispatchers.IO) { model.pauseConnection() }
            if (multicast.isHeld) multicast.release()
        }
    }

    fun resume() {
        foreground = true
        val pending = transition
        scope.launch { pending?.join(); if (foreground) model.setForeground(true) }
    }

    override fun onCleared() {
        scope.cancel(); model.close()
        if (multicast.isHeld) multicast.release()
    }
}

@Composable
fun AndroidWorkbench() {
    val context = LocalContext.current
    val systemLanguage = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    remember(context.applicationContext, systemLanguage) {
        val preferences=context.getSharedPreferences("hanppie-ui", android.content.Context.MODE_PRIVATE)
        Localization.initialize(systemLanguage,preferences.getString("language","system")) {
            preferences.edit().putString("language",it).apply()
        }
    }
    val appearance = remember(context.applicationContext) {
        val preferences = context.getSharedPreferences("hanppie-ui", android.content.Context.MODE_PRIVATE)
        AppearanceController(AppearanceSettings.decode(preferences.getString("appearance", null))) {
            preferences.edit().putString("appearance", it).apply()
        }
    }
    val holder: AndroidWorkbenchModel = viewModel(factory = viewModelFactory {
        initializer { AndroidWorkbenchModel(context.applicationContext as Application) }
    })
    val owner = LocalLifecycleOwner.current
    var voiceDisclosure by remember { mutableStateOf(false) }
    var audioSettings by remember { mutableStateOf(false) }
    var selectedSpeechService by remember { mutableStateOf(holder.speechInput.selectedService()) }
    var voiceDisclosureAccepted by remember { mutableStateOf(false) }
    var talkRequested by remember { mutableStateOf(false) }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (holder.model.isForeground && holder.model.voicePageActive) {
            if (granted) holder.speechInput.start() else holder.speechInput.permissionDenied()
        }
    }
    val talkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && talkRequested && holder.model.isForeground) holder.model.beginPushToTalk()
        else if (!granted) talkRequested = false
    }
    fun startVoice() {
        if (!holder.model.isForeground || !holder.model.voicePageActive) return
        holder.model.replySpeaker.stop()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) holder.speechInput.start()
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
            if (event == Lifecycle.Event.ON_STOP && (context as? Activity)?.isChangingConfigurations != true) holder.pause()
            if (event == Lifecycle.Event.ON_START) holder.resume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var saveSnapshot by remember { mutableStateOf<String?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            holder.document.value = holder.document.value.copy(busy = true)
            holder.scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val source = requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { it.readText() }
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else "script.py"
                        } ?: "script.py"
                        source to name
                    }
                    holder.document.value = EditorDocument(source = result.first, path = result.second)
                    holder.fileError = null
                } catch (error: Exception) { holder.fileError = error.message }
                finally { holder.document.value = holder.document.value.copy(busy = false) }
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-python")) { uri ->
        val snapshot = saveSnapshot
        saveSnapshot = null
        if (uri != null && snapshot != null) {
            holder.document.value = holder.document.value.copy(busy = true)
            holder.scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(snapshot) }
                    }
                    holder.fileError = null
                } catch (error: Exception) { holder.fileError = error.message }
                finally { holder.document.value = holder.document.value.copy(busy = false) }
            }
        }
    }
    var robotUploadDirectory by remember { mutableStateOf<String?>(null) }
    var robotDownloadEntry by remember { mutableStateOf<cn.elonzh.hanppie.robot.RobotFileEntry?>(null) }
    val robotUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val directory = robotUploadDirectory
        robotUploadDirectory = null
        if (uri != null && directory != null) {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
            holder.model.uploadRobotFile(directory, name) {
                requireNotNull(context.contentResolver.openInputStream(uri))
            }
        }
    }
    val robotDownload = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = robotDownloadEntry
        robotDownloadEntry = null
        if (uri != null && entry != null) holder.model.downloadRobotFile(entry) {
            requireNotNull(context.contentResolver.openOutputStream(uri, "w"))
        }
    }
    WorkbenchTheme(appearance) {
        WorkbenchDialog(show = audioSettings, onDismissRequest = { audioSettings = false }, title = tr(Res.string.speech_services)) {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(Res.string.speech_recognition))
                holder.speechInput.services().forEach { (id, label) ->
                    Button({ holder.speechInput.selectService(id); selectedSpeechService = id }) {
                        Text(if (selectedSpeechService == id) tr(Res.string.value_selected,label) else tr(Res.string.use_value,label))
                    }
                    Button({
                        audioSettings = false
                        val component = android.content.ComponentName.unflattenFromString(id)
                        try {
                            requireNotNull(component)
                            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", component.packageName, null)))
                        } catch (_: Exception) { holder.fileError = tr(Res.string.could_not_open_recognition_service_permissions) }
                    }) { Text(tr(Res.string.value_permissions,label)) }
                }
                Button({
                    audioSettings = false
                    try { context.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                    catch (_: Exception) { holder.fileError = tr(Res.string.could_not_open_default_voice_input_settings) }
                }) { Text(tr(Res.string.default_voice_input)) }
                Button({
                    audioSettings = false
                    try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    catch (_: Exception) { holder.fileError = tr(Res.string.could_not_open_text_to_speech_settings) }
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
        Console(holder.model, holder.document, onImport = { open.launch(arrayOf("text/*", "application/octet-stream", "application/x-python-code")) },
            onVoiceInput = { if (voiceDisclosureAccepted) startVoice() else voiceDisclosure = true },
            onPushToTalkStart = ::startTalk, onPushToTalkStop = ::stopTalk,
            onExport = {
                saveSnapshot = holder.document.value.source
                save.launch(suggestedScriptFileName(holder.document.value.displayName))
            }, fileError = holder.fileError, onFileError = { holder.fileError = it },
            onSpeechSettings = { audioSettings = true },
            onRobotFileUpload = { directory ->
                robotUploadDirectory = directory
                robotUpload.launch(arrayOf("*/*"))
            },
            onRobotFileDownload = { entry ->
                robotDownloadEntry = entry
                robotDownload.launch(entry.name)
            },
            onRobotFileOpen = { entry ->
                val directory = File(context.cacheDir, "robot-files").apply { mkdirs() }
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }
                    ?.forEach { it.delete() }
                val extension = entry.name.substringAfterLast('.', "").takeIf {
                    it.isNotBlank() && it.length <= 12 && it.all(Char::isLetterOrDigit)
                }?.let { ".$it" }
                val temporary = File.createTempFile("open-", extension, directory)
                holder.model.openRobotFile(entry, { temporary.outputStream() }) {
                    holder.scope.launch {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", temporary)
                        val mimeType = extension?.drop(1)?.lowercase()?.let(MimeTypeMap.getSingleton()::getMimeTypeFromExtension)
                            ?: "application/octet-stream"
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            holder.fileError = null
                        } catch (_: ActivityNotFoundException) {
                            holder.fileError = tr(Res.string.no_application_can_open_this_file)
                        }
                    }
                }
            })
    }
}
