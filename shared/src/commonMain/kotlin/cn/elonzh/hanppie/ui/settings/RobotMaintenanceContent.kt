package cn.elonzh.hanppie.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.i18n.tr

/** The index selects a task; telemetry, forms and destructive actions live in its detail. */
@Composable
internal fun RobotMaintenanceContent(model: ConsoleController, select: (SettingsDetail) -> Unit,
    onWifi: (() -> Unit)? = null,
    onAddressing: (() -> Unit)? = null, onCalibration: (() -> Unit)? = null, onFirmware: (() -> Unit)? = null,
    onArm: (() -> Unit)? = null, onExtensions: (() -> Unit)? = null) {
    val console by model.state.collectAsState()
    val health by model.robotHealth.state.collectAsState()
    val sdr by model.robotSdr.state.collectAsState()
    if (!console.connected) SettingsNotice(tr(Res.string.maintenance_connect), Modifier.testTag("maintenance-connect"))
    SettingsGroup(tr(Res.string.maintenance_status_group)) {
        val alerts = health.issues.count { it.severity >= 2 }
        SettingsNavigationRow(tr(Res.string.health_title),
            tr(if (console.connected && alerts > 0) Res.string.health_alerts else Res.string.maintenance_health_summary, alerts),
            "settings-maintenance-health") { select(SettingsDetail.HEALTH) }
    }
    SettingsGroup(tr(Res.string.maintenance_files_network)) {
        SettingsNavigationRow(tr(Res.string.camera_storage), tr(Res.string.maintenance_storage_summary),
            "settings-maintenance-storage") { select(SettingsDetail.STORAGE) }
        onWifi?.let { SettingsNavigationRow(tr(Res.string.robot_wifi), tr(Res.string.maintenance_wifi_summary), "settings-wifi-open", onClick = it) }
        if (sdr.visible) SettingsNavigationRow(tr(Res.string.sdr_title), tr(Res.string.maintenance_sdr_summary),
            "settings-maintenance-sdr") { select(SettingsDetail.SDR) }
    }
    if (onAddressing != null || onCalibration != null || onFirmware != null) SettingsGroup(tr(Res.string.maintenance_service_group)) {
        val addressing by model.robotAddressing.state.collectAsState()
        val calibration by model.robotCalibration.state.collectAsState()
        val firmware by model.robotFirmware.state.collectAsState()
        onAddressing?.let { SettingsNavigationRow(tr(if (addressing.blocksControl) Res.string.addressing_continue else Res.string.addressing_title),
            tr(Res.string.maintenance_addressing_summary), "settings-addressing-open", onClick = it) }
        onCalibration?.let { SettingsNavigationRow(tr(if (calibration.blocksControl) Res.string.calibration_continue else Res.string.calibration_title),
            tr(Res.string.maintenance_calibration_summary), "settings-calibration-open", onClick = it) }
        onFirmware?.let { SettingsNavigationRow(tr(if (firmware.blocksControl) Res.string.firmware_continue else Res.string.firmware_title),
            tr(Res.string.maintenance_firmware_summary), "settings-firmware-open", onClick = it) }
    }
    SettingsGroup(tr(Res.string.maintenance_accessories_group)) {
        onArm?.let { SettingsNavigationRow(tr(Res.string.arm_title), tr(Res.string.maintenance_arm_summary), "settings-arm-open", onClick = it) }
        onExtensions?.let { SettingsNavigationRow(tr(Res.string.extensions_title), tr(Res.string.maintenance_extensions_summary), "settings-extensions-open", onClick = it) }
        SettingsNavigationRow(tr(Res.string.display_external_lights), tr(Res.string.display_external_lights_summary),
            "settings-maintenance-lighting") { select(SettingsDetail.LIGHTING) }
    }
    SettingsGroup(tr(Res.string.robot_power_title)) {
        SettingsNavigationRow(tr(Res.string.maintenance_power_actions), tr(Res.string.maintenance_power_summary),
            "settings-maintenance-power") { select(SettingsDetail.POWER) }
    }
}
