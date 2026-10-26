package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.robot.camera.CameraRequest
import cn.elonzh.hanppie.ui.robot.camera.cameraStorageStatusText
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CameraStorageContent(model: ConsoleController, onAlbum: (() -> Unit)? = null) {
    val console by model.state.collectAsState()
    val state by model.robotCamera.state.collectAsState()
    val album by model.robotAlbum.state.collectAsState()
    val storage = state.storage.takeIf { console.connected }
    val canFormat = storage?.canFormat == true && !state.storageBusy && !album.nativeBusy
    var confirmConnection by remember { mutableStateOf<Long?>(null) }
    var awaitingFormat by remember(state.connection) { mutableStateOf(false) }
    LaunchedEffect(state.formatRequest, awaitingFormat) {
        if (awaitingFormat) when (state.formatRequest) {
            is CameraRequest.Value -> {
                model.settingsMessage.value = uiText(Res.string.camera_storage_format_accepted)
                awaitingFormat = false
            }
            is CameraRequest.Failed -> awaitingFormat = false
            else -> Unit
        }
    }
    LaunchedEffect(state.connection, canFormat) {
        if (!canFormat || confirmConnection != state.connection) confirmConnection = null
    }
    WorkbenchDialog(show = confirmConnection == state.connection && canFormat,
        onDismissRequest = { confirmConnection = null },
        title = tr(Res.string.camera_storage_format_question),
        summary = tr(Res.string.camera_storage_format_summary)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ confirmConnection = null }, Modifier.weight(1f).heightIn(min = 48.dp)
                .testTag("camera-storage-format-cancel")) { Text(tr(Res.string.cancel)) }
            Button({
                confirmConnection = null
                awaitingFormat = true
                model.robotCamera.formatStorage()
            }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("camera-storage-format-confirm"),
                colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.errorContainer,
                    contentColor = MiuixTheme.colorScheme.onErrorContainer)) {
                Text(tr(Res.string.camera_storage_format))
            }
        }
    }
    Card(Modifier.fillMaxWidth().testTag("camera-storage"),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr(cameraStorageStatusText(console.connected, storage)), Modifier.testTag("camera-storage-status"), fontSize = 15.sp,
                color = if (storage?.inserted == true && storage.hasError) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurface)
            if (storage?.inserted == true) {
                Text(tr(Res.string.camera_storage_space, storage.remainingSpaceMb?.toString() ?: "—",
                    storage.totalSpaceMb?.toString() ?: "—"), Modifier.testTag("camera-storage-space"), fontSize = 14.sp)
                Text(tr(Res.string.camera_storage_photos, storage.availablePhotos?.toString() ?: "—"), fontSize = 14.sp)
                Text(tr(Res.string.camera_storage_recording_left, storage.remainingRecordingSeconds?.toString() ?: "—"), fontSize = 14.sp)
                if (storage.recording) Text(tr(Res.string.camera_storage_recording_elapsed, storage.recordingSeconds), fontSize = 14.sp)
            }
            if (console.connected) {
                if (album.nativeBusy) Text(tr(Res.string.album_transfer_active), fontSize = 14.sp)
                Button({ confirmConnection = state.connection }, enabled = canFormat,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("camera-storage-format"),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.errorContainer,
                        contentColor = MiuixTheme.colorScheme.onErrorContainer)) {
                    Text(tr(Res.string.camera_storage_format))
                }
                when (val request = state.formatRequest) {
                    CameraRequest.Loading -> Text(tr(Res.string.camera_storage_format_sending),
                        Modifier.testTag("camera-storage-format-result"), fontSize = 14.sp)
                    is CameraRequest.Value -> Unit
                    is CameraRequest.Failed -> SettingsNotice(request.message.resolve(), Modifier.testTag("camera-storage-format-result"), error = true)
                    CameraRequest.Idle -> Unit
                }
            }
            if (onAlbum != null) Button(onAlbum, Modifier.heightIn(min = 48.dp).testTag("settings-album-open")) {
                Text(tr(Res.string.album_browse))
            }
        }
    }
}
