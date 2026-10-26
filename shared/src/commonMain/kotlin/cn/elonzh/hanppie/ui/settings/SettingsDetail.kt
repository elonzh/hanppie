package cn.elonzh.hanppie.ui.settings

import androidx.compose.runtime.*
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import org.jetbrains.compose.resources.StringResource

internal enum class SettingsDetail(val title: StringResource) {
    HEALTH(Res.string.health_title),
    STORAGE(Res.string.camera_storage), POWER(Res.string.robot_power_title), SDR(Res.string.sdr_title),
    LIGHTING(Res.string.display_external_lights), CAMERA(Res.string.robot_camera_settings),
    AUDIO(Res.string.robot_audio_settings), MOTION(Res.string.motion_device_title),
}

@Composable
internal fun SettingsDetailContent(model: ConsoleController, section: SettingsDetail, onAlbum: (() -> Unit)?) {
    val chat by model.chat.state.collectAsState()
    val busy by model.settingsBusy.collectAsState()
    val enabled = !chat.running && !busy
    when (section) {
        SettingsDetail.HEALTH -> cn.elonzh.hanppie.ui.robot.device.RobotHealthContent(model, showTitle = false)
        SettingsDetail.STORAGE -> CameraStorageContent(model, onAlbum)
        SettingsDetail.POWER -> cn.elonzh.hanppie.ui.robot.device.RobotPowerContent(model, showTitle = false)
        SettingsDetail.SDR -> cn.elonzh.hanppie.ui.robot.network.SdrSettingsControls(model)
        SettingsDetail.LIGHTING -> ExternalLightingContent(model)
        SettingsDetail.CAMERA -> {
            val console by model.state.collectAsState()
            val album by model.robotAlbum.state.collectAsState()
            val tracking by model.tracking.collectAsState()
            if (!console.connected) SettingsNotice(cn.elonzh.hanppie.ui.i18n.tr(Res.string.camera_settings_connect_first))
            else {
                CameraImagingContent(model.robotCamera, true, enabled && !console.busy, album.nativeBusy,
                    !tracking.blocksManualControl && !console.scriptRunPhase.mayBeExecuting)
                CameraSettingsContent(model.robotCamera, true, enabled && !console.busy)
            }
        }
        SettingsDetail.AUDIO -> {
            val console by model.state.collectAsState()
            val media by model.mediaSettings.collectAsState()
            RobotAudioSettingsContent(model.robotAudio, console.connected, media.speakerVolume, enabled && !console.busy)
        }
        SettingsDetail.MOTION -> {
            val console by model.state.collectAsState()
            RobotMotionSettingsContent(model.robotMotion, console.connected)
        }
    }
}
