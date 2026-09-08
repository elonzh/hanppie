package cn.elonzh.hanppie.desktop

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

internal fun ConsoleModel(): ConsoleModel = ConsoleModel(SystemSpeech())

fun main() {
    val preferences = java.util.prefs.Preferences.userRoot().node("cn/elonzh/hanppie/ui")
    Localization.initialize(java.util.Locale.getDefault().language, preferences.get("language", "system")) {
        preferences.put("language",it); preferences.flush()
    }
    application {
    val model = remember { ConsoleModel() }
    val document = remember { mutableStateOf(EditorDocument()) }
    var confirmExit by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Window(onCloseRequest = {
        if (document.value.dirty || document.value.busy || model.state.value.connected || model.state.value.busy) confirmExit = true
        else { model.close(); exitApplication() }
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
            WindowDialog(show = confirmExit, onDismissRequest = { confirmExit = false }, title = tr("退出 Hanppie？"),
                summary = tr("未保存修改将丢失。断开连接不保证机内脚本停止。")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ confirmExit = false }) { Text(tr("返回")) }
                    Button({ model.close(); exitApplication() }) { Text(tr("仍然退出")) }
                }
            }
        }
    }
}
}

private fun chooseFile(save: Boolean): java.io.File? {
    val dialog = FileDialog(null as Frame?, if (save) tr("保存 Python 脚本") else tr("打开 Python 脚本"),
        if (save) FileDialog.SAVE else FileDialog.LOAD)
    try {
        dialog.file = if (save) "script.py" else "*.py"
        dialog.isVisible = true
        return dialog.file?.let { java.io.File(dialog.directory, it) }
    } finally { dialog.dispose() }
}
