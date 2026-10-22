package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.camera.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun OnboardCaptureControls(controller: RobotCameraController, connected: Boolean, enabled: Boolean,
    contentMaxHeight: Dp, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val state by controller.state.collectAsState()
    val storage = state.storage.takeIf { connected }
    var openConnection by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.connection, connected, enabled) {
        if (openConnection != state.connection || !connected || !enabled) openConnection = null
    }
    Box(modifier.background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = HanppieDesignTokens.RemoteHudAlpha), RoundedCornerShape(12.dp))) {
        HudTextButton(if (storage?.recording == true) tr(Res.string.camera_capture_recording_badge, storage.recordingSeconds)
            else tr(Res.string.camera_capture_entry), connected && enabled, "onboard-capture-open",
            description = tr(Res.string.camera_capture_title)) { onOpen(); openConnection = state.connection }
    }
    WorkbenchDialog(show = openConnection == state.connection && connected && enabled,
        onDismissRequest = { openConnection = null }, title = tr(Res.string.camera_capture_title),
        summary = tr(Res.string.camera_capture_destination)) {
        Column(Modifier.fillMaxWidth().heightIn(max = contentMaxHeight).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(cameraStorageStatusText(connected, storage)), Modifier.testTag("onboard-capture-status"), fontSize = 14.sp)
            when (val request = state.capture) {
                CameraRequest.Loading -> Text(tr(Res.string.camera_capture_pending), Modifier.testTag("onboard-capture-result"), fontSize = 14.sp)
                is CameraRequest.Value -> Text(tr(when (request.value) {
                    CaptureAction.PHOTO -> Res.string.camera_capture_photo_ack
                    CaptureAction.START_RECORDING -> Res.string.camera_capture_recording_started
                    CaptureAction.STOP_RECORDING -> Res.string.camera_capture_recording_stopped
                }), Modifier.testTag("onboard-capture-result"), fontSize = 14.sp)
                is CameraRequest.Failed -> Text(request.message.resolve(), Modifier.testTag("onboard-capture-result"),
                    fontSize = 14.sp, color = MiuixTheme.colorScheme.error)
                CameraRequest.Idle -> Unit
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ controller.capture(CaptureAction.PHOTO) }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("onboard-photo"),
                    enabled = storage?.canCapture == true && !state.storageBusy) { Text(tr(Res.string.take_photo)) }
                Button({ controller.capture(CaptureAction.START_RECORDING) }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("onboard-record-start"),
                    enabled = storage?.canCapture == true && !state.storageBusy) { Text(tr(Res.string.start_recording)) }
            }
            Button({ controller.capture(CaptureAction.STOP_RECORDING) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboard-record-stop"),
                enabled = !state.storageBusy && (storage == null || storage.recording || state.capture is CameraRequest.Failed)) {
                Text(tr(Res.string.stop_recording))
            }
            Button({ openConnection = null }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboard-capture-close")) {
                Text(tr(Res.string.close))
            }
        }
    }
}
