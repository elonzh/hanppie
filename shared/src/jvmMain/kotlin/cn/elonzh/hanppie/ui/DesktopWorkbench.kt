package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text

internal fun ConsoleModel(persistSettings: Boolean = false): ConsoleModel = ConsoleModel(SystemSpeech(),
    speakerInput = DesktopSpeakerInput(),
    settingsStore = if (persistSettings) DesktopSettingsStore() else null,
    scriptStore = if (persistSettings) JsonScriptStore(desktopScriptLibraryPath()) else MemoryScriptStore())

private fun desktopScriptLibraryPath(): Path {
    val home = Path.of(System.getProperty("user.home"))
    val os = System.getProperty("os.name").lowercase()
    val directory = when {
        os.contains("mac") -> home.resolve("Library/Application Support/Hanppie")
        os.contains("win") -> System.getenv("APPDATA")?.let(Path::of)?.resolve("Hanppie")
            ?: home.resolve("AppData/Roaming/Hanppie")
        else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)?.resolve("hanppie")
            ?: home.resolve(".local/share/hanppie")
    }
    return directory.resolve("script-library-v1.json")
}

@Composable
fun DesktopWorkbench(onExit: () -> Unit) {
    remember {
        val preferences = java.util.prefs.Preferences.userRoot().node("cn/elonzh/hanppie/ui")
        Localization.initialize(java.util.Locale.getDefault().language, preferences.get("language", "system")) {
            preferences.put("language",it); preferences.flush()
        }
    }
    val appearance = remember {
        val preferences = java.util.prefs.Preferences.userRoot().node("cn/elonzh/hanppie/ui")
        AppearanceController(AppearanceSettings.decode(preferences.get("appearance", null))) {
            preferences.put("appearance", it); preferences.flush()
        }
    }
    val model = remember { ConsoleModel(persistSettings = true) }
    val document = remember { mutableStateOf(EditorDocument()) }
    var confirmExit by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Window(onCloseRequest = {
        if (document.value.dirty || document.value.busy || model.state.value.connected || model.state.value.busy) confirmExit = true
        else { model.close(); onExit() }
    }, title = "Hanppie", state = rememberWindowState(width = 1040.dp, height = 760.dp)) {
        DisposableEffect(window) {
            val listener = object : java.awt.event.WindowAdapter() {
                override fun windowLostFocus(event: java.awt.event.WindowEvent) { model.haltRemote() }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        WorkbenchTheme(appearance) {
            Console(model, document, onImport = {
                val file = chooseFile(false)
                if (file != null) {
                    document.value = document.value.copy(busy = true)
                    scope.launch {
                        try {
                            val source = withContext(Dispatchers.IO) { Files.readString(file.toPath()) }
                            document.value = EditorDocument(source = source, path = file.absolutePath)
                            fileError = null
                        } catch (error: Exception) { fileError = error.message }
                        finally { document.value = document.value.copy(busy = false) }
                    }
                }
            }, onExport = {
                val file = chooseFile(true, suggestedScriptFileName(document.value.displayName))
                if (file != null) {
                    val snapshot = document.value.source
                    document.value = document.value.copy(busy = true)
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Files.writeString(file.toPath(), snapshot) }
                            fileError = null
                        } catch (error: Exception) { fileError = error.message }
                        finally { document.value = document.value.copy(busy = false) }
                    }
                }
            }, fileError = fileError, onFileError = { fileError = it })
            WorkbenchDialog(show = confirmExit, onDismissRequest = { confirmExit = false }, title = tr(Res.string.quit_hanppie),
                summary = tr(Res.string.unsaved_changes_will_be_lost_disconnecting_does_not_guarantee)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ confirmExit = false }) { Text(tr(Res.string.back)) }
                    Button({ model.close(); onExit() }) { Text(tr(Res.string.quit_anyway)) }
                }
            }
        }
    }
}

private fun chooseFile(save: Boolean, suggestedName: String? = null): java.io.File? {
    val dialog = FileDialog(null as Frame?, if (save) tr(Res.string.save_python_script) else tr(Res.string.open_python_script),
        if (save) FileDialog.SAVE else FileDialog.LOAD)
    try {
        dialog.file = if (save) suggestedName ?: "script.py" else "*.py"
        dialog.isVisible = true
        return dialog.file?.let { java.io.File(dialog.directory, it) }
    } finally { dialog.dispose() }
}
