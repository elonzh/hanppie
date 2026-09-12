package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.remote.DesktopSpeakerInput
import cn.elonzh.hanppie.ui.speech.SystemSpeech
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.Locale
import org.jetbrains.compose.resources.painterResource
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text

private fun createDesktopWorkbenchViewModel(): WorkbenchViewModel {
    val storage = WorkbenchStorage.create()
    return try {
        val model = ConsoleModel(
            speech = SystemSpeech(),
            speakerInput = DesktopSpeakerInput(),
            settingsStore = storage.settings,
            scriptRepository = storage.scripts,
        )
        WorkbenchViewModel(model, storage, Locale.getDefault().language)
    } catch (error: Exception) {
        storage.close()
        throw error
    }
}

@Composable
fun DesktopWorkbench(onExit: () -> Unit) {
    val lifecycleOwner = rememberLifecycleOwner(parent = null)
    CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
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
    var confirmExit by remember { mutableStateOf(false) }
    val desktopWindowState = rememberWindowState(width = 1040.dp, height = 760.dp)
    var cockpitActive by remember { mutableStateOf(false) }
    var workbenchSize by remember { mutableStateOf(desktopWindowState.size) }

    Window(
        onCloseRequest = {
            if (document.value.dirty || document.value.busy || model.state.value.connected || model.state.value.busy) {
                confirmExit = true
            } else {
                holder.shutdown()
                onExit()
            }
        },
        title = "Hanppie",
        icon = painterResource(Res.drawable.hanppie_app_icon),
        state = desktopWindowState,
    ) {
        DisposableEffect(window) {
            window.minimumSize = Dimension(320, 480)
            val listener = object : WindowAdapter() {
                override fun windowLostFocus(event: WindowEvent) = model.setForeground(false)
                override fun windowGainedFocus(event: WindowEvent) = model.setForeground(true)
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        LaunchedEffect(cockpitActive) {
            if (cockpitActive) {
                workbenchSize = desktopWindowState.size
                window.minimumSize = Dimension(740, 480)
                if (workbenchSize.width < 900.dp || workbenchSize.width < workbenchSize.height) {
                    desktopWindowState.size = DpSize(1040.dp, 700.dp)
                }
            } else {
                window.minimumSize = Dimension(320, 480)
                desktopWindowState.size = workbenchSize
            }
        }
        WorkbenchTheme(holder.appearance) {
            Console(
                model = model,
                document = document,
                onCockpitChanged = { cockpitActive = it },
                onImport = holder::importScript,
                onExport = holder::exportScript,
                fileError = holder.fileError,
                onFileError = holder::updateFileError,
                onRobotFileUpload = holder::uploadRobotFile,
                onRobotFileDownload = holder::downloadRobotFile,
                onRobotFileOpen = holder::openRobotFile,
            )
            WorkbenchDialog(
                show = confirmExit,
                onDismissRequest = { confirmExit = false },
                title = tr(Res.string.quit_hanppie),
                summary = tr(Res.string.unsaved_changes_will_be_lost_disconnecting_does_not_guarantee),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ confirmExit = false }) { Text(tr(Res.string.back)) }
                    Button({ holder.shutdown(); onExit() }) { Text(tr(Res.string.quit_anyway)) }
                }
            }
        }
    }
}
