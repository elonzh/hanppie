package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.back
import cn.elonzh.hanppie.resources.could_not_open_wifi_settings
import cn.elonzh.hanppie.resources.hanppie_app_icon
import cn.elonzh.hanppie.resources.quit_anyway
import cn.elonzh.hanppie.resources.quit_hanppie
import cn.elonzh.hanppie.resources.unsaved_changes_will_be_lost_disconnecting_does_not_guarantee
import cn.elonzh.hanppie.robot.session.JvmRobotRuntime
import cn.elonzh.hanppie.ui.chat.createAgentHttpClient
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.audio.DesktopLabAudioImporter
import cn.elonzh.hanppie.ui.robot.audio.DesktopLabAudioPlayer
import cn.elonzh.hanppie.ui.robot.remote.DesktopSpeakerInput
import cn.elonzh.hanppie.ui.settings.DataDirectoryAccess
import cn.elonzh.hanppie.ui.settings.LocalDataDirectoryAccess
import cn.elonzh.hanppie.ui.settings.ModelCatalog
import cn.elonzh.hanppie.ui.settings.ModelProviderPreset
import cn.elonzh.hanppie.ui.settings.ModelSettings
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import java.awt.Desktop
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.Locale

private fun createDesktopWorkbenchViewModel(): WorkbenchViewModel {
    val storage = WorkbenchStorage.create()
    return try {
        val model = ConsoleModel(
            speakerInput = DesktopSpeakerInput(),
            audioImporter = DesktopLabAudioImporter(),
            audioPlayer = DesktopLabAudioPlayer(),
            robotRuntime = JvmRobotRuntime(),
            settingsStore = storage.settings,
            scriptRepository = storage.scripts,
            sessionHistory = storage.sessions,
            skills = storage.skills::await,
            createAgentHttpClient = ::createAgentHttpClient,
            runtimeDefaults = { desktopModelOverrides(ModelSettings()) },
            applyModelOverrides = ::desktopModelOverrides,
        )
        WorkbenchViewModel(model, storage, Locale.getDefault().toLanguageTag())
    } catch (error: Exception) {
        storage.close()
        throw error
    }
}

private fun desktopModelOverrides(settings: ModelSettings): ModelSettings {
    val provider = System.getenv("HANPPIE_LLM_PROVIDER")?.uppercase()
        ?.let { runCatching { ModelProviderPreset.valueOf(it) }.getOrNull() }
        ?: settings.provider
    val providerDefaults = if (provider == settings.provider) settings else ModelCatalog.defaults(provider)
    return providerDefaults.copy(
        endpoint = System.getenv("HANPPIE_LLM_ENDPOINT") ?: providerDefaults.endpoint,
        model = System.getenv("HANPPIE_LLM_MODEL") ?: providerDefaults.model,
        apiKey = System.getenv("HANPPIE_LLM_API_KEY") ?: settings.apiKey,
    )
}

private fun desktopWifiSettingsCommand(): List<String>? {
    val os = System.getProperty("os.name", "").lowercase()
    return when {
        os.contains("mac") -> listOf("/usr/bin/open", "/System/Library/PreferencePanes/Network.prefPane")
        os.contains("win") -> listOf("cmd.exe", "/c", "start", "", "ms-settings:network-wifi")
        else -> listOf("nm-connection-editor", "gnome-control-center").firstNotNullOfOrNull { executable ->
            System.getenv("PATH")?.split(File.pathSeparator)?.firstNotNullOfOrNull { directory ->
                File(directory, executable).takeIf { it.isFile && it.canExecute() }
            }?.let { path -> if (executable == "gnome-control-center") listOf(path.path, "wifi") else listOf(path.path) }
        }
    }
}

@Composable
fun DesktopWorkbench(onExit: () -> Unit) {
    val lifecycleOwner = rememberLifecycleOwner(parent = null)
    val dataDirectory = remember {
        val directory = File(FileKit.filesDir.path).absoluteFile
        DataDirectoryAccess(directory.path) {
            withContext(Dispatchers.IO) { Desktop.getDesktop().open(directory) }
        }
    }
    CompositionLocalProvider(
        LocalLifecycleOwner provides lifecycleOwner,
        LocalDataDirectoryAccess provides dataDirectory,
    ) {
        DesktopWorkbenchWindow(onExit)
    }
}

@Composable
private fun DesktopWorkbenchWindow(onExit: () -> Unit) {
    val owner = rememberViewModelStoreOwner(parent = null, savedStateRegistryOwner = null)
    val holder: WorkbenchViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = viewModelFactory { initializer { createDesktopWorkbenchViewModel() } },
    )
    val model = holder.model
    val document = holder.document
    val mediaSettings by model.mediaSettings.collectAsState()
    var confirmExit by remember { mutableStateOf(false) }
    val desktopWindowState = rememberWindowState(width = 1120.dp, height = 658.dp)
    var windowReady by remember { mutableStateOf(false) }
    LaunchedEffect(holder.uiPreferencesLoaded) {
        if (holder.uiPreferencesLoaded) {
            desktopWindowState.position = restoreDesktopWindowPosition(holder.restoredWindowPosition)
            windowReady = true
        }
    }
    val windowScope = rememberCoroutineScope()
    var lastWindowPosition by remember { mutableStateOf(holder.restoredWindowPosition) }
    val wifiSettingsCommand = remember { desktopWifiSettingsCommand() }
    val shutdown: () -> Unit = {
        windowScope.launch {
            lastWindowPosition?.let { holder.saveWindowPosition(it) }
            holder.shutdown { EventQueue.invokeLater(onExit) }
        }
    }

    Window(
        onCloseRequest = {
            if (document.value.dirty || document.value.busy || model.state.value.connected || model.state.value.busy) {
                confirmExit = true
            } else {
                shutdown()
            }
        },
        visible = windowReady,
        title = "Hanppie",
        icon = painterResource(Res.drawable.hanppie_app_icon),
        state = desktopWindowState,
    ) {
        DisposableEffect(window, windowReady) {
            var pendingSave: kotlinx.coroutines.Job? = null
            fun rememberPosition() {
                if (!windowReady || !window.isShowing || window.placement != WindowPlacement.Floating || window.isMinimized) return
                val position = cn.elonzh.hanppie.ui.settings.SavedWindowPosition(window.x.toFloat(), window.y.toFloat())
                lastWindowPosition = position
                pendingSave?.cancel()
                pendingSave = windowScope.launch { delay(300); holder.saveWindowPosition(position) }
            }
            val positionListener = object : ComponentAdapter() {
                override fun componentMoved(event: ComponentEvent) = rememberPosition()
                override fun componentShown(event: ComponentEvent) = rememberPosition()
            }
            window.addComponentListener(positionListener)
            rememberPosition()
            window.minimumSize = Dimension(740, 480)
            val listener = object : WindowAdapter() {
                override fun windowLostFocus(event: WindowEvent) = model.setForeground(false)
                override fun windowGainedFocus(event: WindowEvent) = model.setForeground(true)
            }
            window.addWindowFocusListener(listener)
            onDispose {
                pendingSave?.cancel()
                window.removeComponentListener(positionListener)
                window.removeWindowFocusListener(listener)
            }
        }
        DesktopVideoAspect(window, mediaSettings.videoResolution.width.toDouble() / mediaSettings.videoResolution.height)
        WorkbenchTheme(holder.appearance) {
            Console(
                model = model,
                document = document,
                onImport = holder::importScript,
                onExport = holder::exportScript,
                onImportAudio = holder::importScriptAudio,
                fileError = holder.fileError,
                onFileError = holder::updateFileError,
                onRobotFileUpload = holder::uploadRobotFile,
                onRobotFileDownload = holder::downloadRobotFile,
                onRobotFileOpen = holder::openRobotFile,
                onOpenWifiSettings = wifiSettingsCommand?.let { command ->
                    {
                        try { ProcessBuilder(command).start() }
                        catch (_: Exception) { holder.updateFileError(tr(Res.string.could_not_open_wifi_settings)) }
                    }
                },
            )
            WorkbenchDialog(
                show = confirmExit,
                onDismissRequest = { confirmExit = false },
                title = tr(Res.string.quit_hanppie),
                summary = tr(Res.string.unsaved_changes_will_be_lost_disconnecting_does_not_guarantee),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ confirmExit = false }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(tr(Res.string.back))
                    }
                    Button(shutdown, Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.buttonColorsPrimary()) {
                        Text(tr(Res.string.quit_anyway))
                    }
                }
            }
        }
    }
}
