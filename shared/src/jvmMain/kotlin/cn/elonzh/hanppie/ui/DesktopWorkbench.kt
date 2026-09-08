package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.window.WindowDialog

internal fun ConsoleModel(persistSettings: Boolean = false): ConsoleModel = ConsoleModel(SystemSpeech(),
    settingsStore = if (persistSettings) DesktopSettingsStore() else null)

@Composable
fun DesktopWorkbench(onExit: () -> Unit) {
    remember {
        val preferences = java.util.prefs.Preferences.userRoot().node("cn/elonzh/hanppie/ui")
        Localization.initialize(java.util.Locale.getDefault().language, preferences.get("language", "system")) {
            preferences.put("language",it); preferences.flush()
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
        WorkbenchTheme {
            Console(model, document, onOpen = {
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
            }, onSave = {
                val file = chooseFile(true)
                if (file != null) {
                    val snapshot = document.value.source
                    document.value = document.value.copy(busy = true)
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Files.writeString(file.toPath(), snapshot) }
                            document.value = document.value.saved(snapshot, file.absolutePath)
                            fileError = null
                        } catch (error: Exception) { fileError = error.message }
                        finally { document.value = document.value.copy(busy = false) }
                    }
                }
            }, fileError = fileError)
            WindowDialog(show = confirmExit, onDismissRequest = { confirmExit = false }, title = tr(Res.string.quit_hanppie),
                summary = tr(Res.string.unsaved_changes_will_be_lost_disconnecting_does_not_guarantee)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ confirmExit = false }) { Text(tr(Res.string.back)) }
                    Button({ model.close(); onExit() }) { Text(tr(Res.string.quit_anyway)) }
                }
            }
        }
    }
}

private fun chooseFile(save: Boolean): java.io.File? {
    val dialog = FileDialog(null as Frame?, if (save) tr(Res.string.save_python_script) else tr(Res.string.open_python_script),
        if (save) FileDialog.SAVE else FileDialog.LOAD)
    try {
        dialog.file = if (save) "script.py" else "*.py"
        dialog.isVisible = true
        return dialog.file?.let { java.io.File(dialog.directory, it) }
    } finally { dialog.dispose() }
}
