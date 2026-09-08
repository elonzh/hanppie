package cn.elonzh.hanppie.desktop

import android.app.Application
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cn.elonzh.hanppie.robot.RobotNetwork
import java.net.DatagramSocket
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text

internal class AndroidWorkbenchModel(app: Application) : ViewModel() {
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val multicast = wifi.createMulticastLock("hanppie-discovery").apply { setReferenceCounted(false) }
    private fun wifiNetwork() = connectivity.allNetworks.firstOrNull {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: error(tr("请先连接机器人所在的 Wi-Fi"))
    val speechInput = AndroidSpeechInput(app)
    val model = ConsoleModel(AndroidSpeech(app), voiceInput = speechInput, robotNetwork = {
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
    val holder: AndroidWorkbenchModel = viewModel(factory = viewModelFactory {
        initializer { AndroidWorkbenchModel(context.applicationContext as Application) }
    })
    val owner = LocalLifecycleOwner.current
    var voiceDisclosure by remember { mutableStateOf(false) }
    var audioSettings by remember { mutableStateOf(false) }
    var selectedSpeechService by remember { mutableStateOf(holder.speechInput.selectedService()) }
    var voiceDisclosureAccepted by remember { mutableStateOf(false) }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (holder.model.isForeground && holder.model.voicePageActive) {
            if (granted) holder.speechInput.start() else holder.speechInput.permissionDenied()
        }
    }
    fun startVoice() {
        if (!holder.model.isForeground || !holder.model.voicePageActive) return
        holder.model.replySpeaker.stop()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) holder.speechInput.start()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
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
                    holder.document.value = holder.document.value.saved(snapshot, "script.py")
                    holder.fileError = null
                } catch (error: Exception) { holder.fileError = error.message }
                finally { holder.document.value = holder.document.value.copy(busy = false) }
            }
        }
    }
    WorkbenchTheme {
        WindowDialog(show = audioSettings, onDismissRequest = { audioSettings = false }, title = tr("语音服务")) {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("语音识别"))
                holder.speechInput.services().forEach { (id, label) ->
                    Button({ holder.speechInput.selectService(id); selectedSpeechService = id }) {
                        Text(if (selectedSpeechService == id) tr("{0} · 已选择",label) else tr("使用 {0}",label))
                    }
                    Button({
                        audioSettings = false
                        val component = android.content.ComponentName.unflattenFromString(id)
                        try {
                            requireNotNull(component)
                            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", component.packageName, null)))
                        } catch (_: Exception) { holder.fileError = tr("无法打开识别服务权限设置") }
                    }) { Text(tr("{0} · 权限设置",label)) }
                }
                Button({
                    audioSettings = false
                    try { context.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                    catch (_: Exception) { holder.fileError = tr("无法打开系统默认语音输入设置") }
                }) { Text(tr("系统默认语音输入")) }
                Button({
                    audioSettings = false
                    try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    catch (_: Exception) { holder.fileError = tr("无法打开系统朗读设置") }
                }) { Text(tr("系统朗读设置")) }
            }
        }
        WindowDialog(show = voiceDisclosure, onDismissRequest = { voiceDisclosure = false }, title = tr("使用手机麦克风"),
            summary = tr("语音由系统服务识别，服务可能联网处理声音。文字会回填到输入框，由你确认发送；应用不保存录音。")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ voiceDisclosure = false }) { Text(tr("取消")) }
                Button({ voiceDisclosureAccepted = true; voiceDisclosure = false; startVoice() }) { Text(tr("继续")) }
            }
        }
        WindowDialog(show = confirmExit, onDismissRequest = { confirmExit = false }, title = tr("退出憨皮？"),
            summary = tr("未保存修改将丢失。断开连接不保证机内脚本停止。")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ confirmExit = false }) { Text(tr("返回")) }
                Button({ (context as? Activity)?.finish() }) { Text(tr("仍然退出")) }
            }
        }
        Console(holder.model, holder.document, onOpen = { open.launch(arrayOf("text/*", "application/octet-stream", "application/x-python-code")) },
            onVoiceInput = { if (voiceDisclosureAccepted) startVoice() else voiceDisclosure = true },
            onSave = { saveSnapshot = holder.document.value.source; save.launch("script.py") }, fileError = holder.fileError,
            onSpeechSettings = { audioSettings = true })
    }
}
