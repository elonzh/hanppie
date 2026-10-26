package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.media.AntiFlicker
import cn.elonzh.hanppie.robot.media.CameraDate
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.camera.CameraRequest
import cn.elonzh.hanppie.ui.robot.camera.RobotCameraController
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CameraSettingsContent(controller: RobotCameraController, connected: Boolean, enabled: Boolean) {
    val state by controller.state.collectAsState()
    LaunchedEffect(controller, state.connection, connected) { if (connected) controller.refresh() }
    Text(tr(Res.string.robot_camera_settings), fontSize = 15.sp)
    Card(Modifier.fillMaxWidth().testTag("camera-settings"),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!connected) Text(tr(Res.string.camera_settings_connect_first), fontSize = 14.sp)
            else {
                when (val setting = state.antiFlicker) {
                    is CameraRequest.Value -> SettingsDropdown(tr(Res.string.camera_anti_flicker), "camera-anti-flicker",
                        when (val mode = AntiFlicker.fromWire(setting.value)) {
                            AntiFlicker.AUTO -> tr(Res.string.camera_anti_flicker_auto)
                            null -> tr(Res.string.unknown)
                            else -> mode.name
                        }, listOf(AntiFlicker.HZ_50.name to tr(Res.string.camera_anti_flicker_50),
                            AntiFlicker.HZ_60.name to tr(Res.string.camera_anti_flicker_60)),
                        enabled = enabled) { controller.setAntiFlicker(AntiFlicker.valueOf(it)) }
                    is CameraRequest.Failed -> SettingsNotice(setting.message.resolve(), Modifier.testTag("camera-anti-error"), error = true)
                    else -> Text(tr(Res.string.camera_anti_flicker_reading), Modifier.testTag("camera-anti-busy"), fontSize = 14.sp)
                }
                when (val setting = state.date) {
                    is CameraRequest.Value -> Text(tr(Res.string.camera_date_value,
                        setting.value?.display() ?: tr(Res.string.camera_date_unset)),
                        Modifier.testTag("camera-date-value"), fontSize = 14.sp)
                    is CameraRequest.Failed -> SettingsNotice(setting.message.resolve(), Modifier.testTag("camera-date-error"), error = true)
                    else -> Text(tr(Res.string.camera_date_reading), Modifier.testTag("camera-date-busy"), fontSize = 14.sp)
                }
                Text(tr(when (state.timeSynced) {
                    true -> Res.string.camera_time_synced
                    false -> Res.string.camera_time_unsynced
                    null -> Res.string.camera_time_unknown
                }), Modifier.testTag("camera-time-status"), fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Button(controller::syncClock, enabled = enabled && state.date != CameraRequest.Loading,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("camera-sync-clock")) { Text(tr(Res.string.camera_sync_clock)) }
                Button(controller::refresh,
                    enabled = enabled && state.date != CameraRequest.Loading && state.antiFlicker != CameraRequest.Loading,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("camera-settings-refresh")) { Text(tr(Res.string.refresh)) }
            }
        }
    }
}

private fun CameraDate.display(): String =
    "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')} " +
        "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}:${second.toString().padStart(2, '0')}"
