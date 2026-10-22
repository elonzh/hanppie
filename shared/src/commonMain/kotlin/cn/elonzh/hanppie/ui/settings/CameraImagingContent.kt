package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.media.CameraPhotoSize
import cn.elonzh.hanppie.robot.media.VideoTransmissionRate
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.camera.CameraRequest
import cn.elonzh.hanppie.ui.robot.camera.RobotCameraController
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CameraImagingContent(controller: RobotCameraController, connected: Boolean, enabled: Boolean,
    storageReading: Boolean, canSwitch: Boolean) {
    val state by controller.state.collectAsState()
    Text(tr(Res.string.camera_imaging), fontSize = 15.sp)
    Card(Modifier.fillMaxWidth().testTag("camera-imaging"),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!connected) Text(tr(Res.string.camera_settings_connect_first), fontSize = 14.sp)
            else {
                val imaging = state.imaging
                Text(tr(Res.string.camera_video_format, imaging?.resolution?.let { "${it.label} · 30 fps" } ?: tr(Res.string.unknown)),
                    Modifier.testTag("camera-video-format"), fontSize = 14.sp)
                val idle = state.storage?.let { !it.recording && !it.shooting && !it.formatting } == true && !state.storageBusy
                if (imaging == null) Text(tr(Res.string.camera_imaging_wait), fontSize = 14.sp)
                else {
                    SettingsDropdown(tr(Res.string.camera_photo_size), "camera-photo-size",
                        imaging.photo?.name ?: tr(Res.string.unknown), CameraPhotoSize.entries.map { it.name to it.label }, enabled && idle) {
                        controller.setPhotoSize(CameraPhotoSize.valueOf(it))
                    }
                    ImagingRequestStatus(state.photoSize, tr(Res.string.camera_imaging_confirmed), "camera-photo-result")
                    val names = listOf(tr(Res.string.camera_main), tr(Res.string.camera_secondary))
                    if (imaging.availableCameras.size > 1) {
                        SettingsDropdown(tr(Res.string.camera_source), "camera-source",
                            imaging.cameraIndex.toString().takeIf { imaging.cameraIndex in 0..1 } ?: tr(Res.string.unknown),
                            imaging.availableCameras.map { it.toString() to names[it] }, enabled && idle && !storageReading && canSwitch) {
                            controller.switchCamera(it.toInt())
                        }
                        if (!canSwitch) Text(tr(Res.string.camera_switch_task_active), fontSize = 13.sp)
                        else if (!idle || storageReading) Text(tr(Res.string.camera_switch_busy), fontSize = 13.sp)
                    } else Text(tr(Res.string.camera_source_value, names.getOrNull(imaging.cameraIndex) ?: tr(Res.string.unknown)),
                        Modifier.testTag("camera-source-value"), fontSize = 14.sp)
                    ImagingRequestStatus(state.cameraSwitch, tr(Res.string.camera_imaging_confirmed), "camera-switch-result")
                }
                val videoBusy = state.transmissionRate == CameraRequest.Loading || state.keyFrame == CameraRequest.Loading
                SettingsDropdown(tr(Res.string.camera_transmission_rate), "camera-transmission-rate",
                    (state.transmissionRate as? CameraRequest.Value)?.value?.name ?: tr(Res.string.camera_rate_choose),
                    VideoTransmissionRate.entries.map { it.name to it.label }, enabled && !videoBusy) {
                    controller.setTransmissionRate(VideoTransmissionRate.valueOf(it))
                }
                ImagingRequestStatus(state.transmissionRate, tr(Res.string.camera_rate_confirmed,
                    (state.transmissionRate as? CameraRequest.Value)?.value?.label.orEmpty()), "camera-rate-result")
                Button(controller::requestKeyFrame, Modifier.heightIn(min = 48.dp).testTag("camera-request-keyframe"), enabled && !videoBusy) {
                    Text(tr(Res.string.camera_keyframe))
                }
                ImagingRequestStatus(state.keyFrame, tr(Res.string.camera_keyframe_requested), "camera-keyframe-result")
            }
        }
    }
}

@Composable
private fun ImagingRequestStatus(request: CameraRequest<*>, success: String, tag: String) {
    when (request) {
        CameraRequest.Loading -> Text(tr(Res.string.camera_imaging_applying), Modifier.testTag(tag), fontSize = 14.sp)
        is CameraRequest.Failed -> Text(request.message.resolve(), Modifier.testTag(tag), fontSize = 14.sp, color = MiuixTheme.colorScheme.error)
        is CameraRequest.Value -> Text(success, Modifier.testTag(tag), fontSize = 14.sp)
        CameraRequest.Idle -> Unit
    }
}
