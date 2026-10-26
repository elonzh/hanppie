package cn.elonzh.hanppie.ui.robot.device

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.i18n.tr
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun robotModuleName(id: Int): String {
    val component = RobotComponent.fromDeviceId(id)
    return when (component) {
        RobotComponent.IMAGE_TRANSMISSION -> tr(Res.string.module_controller)
        RobotComponent.CAMERA -> tr(Res.string.module_camera)
        RobotComponent.CHASSIS -> tr(Res.string.module_chassis)
        RobotComponent.BATTERY -> tr(Res.string.module_battery)
        RobotComponent.GIMBAL -> tr(Res.string.module_gimbal)
        RobotComponent.ESC_0 -> tr(Res.string.module_motor_front_right)
        RobotComponent.ESC_1 -> tr(Res.string.module_motor_front_left)
        RobotComponent.ESC_2 -> tr(Res.string.module_motor_rear_left)
        RobotComponent.ESC_3 -> tr(Res.string.module_motor_rear_right)
        RobotComponent.SERVO_1, RobotComponent.SERVO_2, RobotComponent.SERVO_3, RobotComponent.SERVO_4 -> tr(Res.string.module_servo, id - 0x0317)
        RobotComponent.ARM -> tr(Res.string.module_arm)
        RobotComponent.CLAW -> tr(Res.string.module_claw)
        RobotComponent.TOF_1, RobotComponent.TOF_2, RobotComponent.TOF_3, RobotComponent.TOF_4 -> tr(Res.string.module_tof, id - 0x1200)
        RobotComponent.SENSOR_ADAPTER_1, RobotComponent.SENSOR_ADAPTER_2, RobotComponent.SENSOR_ADAPTER_3,
        RobotComponent.SENSOR_ADAPTER_4, RobotComponent.SENSOR_ADAPTER_5, RobotComponent.SENSOR_ADAPTER_6 -> tr(Res.string.module_adapter, id - 0x1600)
        RobotComponent.WATER_GUN -> tr(Res.string.module_blaster)
        RobotComponent.INFRARED_GUN -> tr(Res.string.module_ir_blaster)
        RobotComponent.BACK_ARMOR -> tr(Res.string.module_armor_back)
        RobotComponent.FRONT_ARMOR -> tr(Res.string.module_armor_front)
        RobotComponent.LEFT_ARMOR -> tr(Res.string.module_armor_left)
        RobotComponent.RIGHT_ARMOR -> tr(Res.string.module_armor_right)
        RobotComponent.LEFT_HEAD_ARMOR -> tr(Res.string.module_armor_head_left)
        RobotComponent.RIGHT_HEAD_ARMOR -> tr(Res.string.module_armor_head_right)
        null -> tr(Res.string.module_unknown, id.toString(16).uppercase().padStart(4, '0'))
    }
}

@Composable
internal fun RobotHealthContent(model: ConsoleController, showTitle: Boolean = true) {
    val connected by remember(model) { model.state.map { it.connected }.distinctUntilChanged() }
        .collectAsState(initial = model.state.value.connected)
    val health by model.robotHealth.state.collectAsState()
    var allMessages by remember(connected) { mutableStateOf(false) }
    if (showTitle) Text(tr(Res.string.health_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Column(Modifier.fillMaxWidth().testTag("robot-health"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!connected) Text(tr(Res.string.health_disconnected), Modifier.testTag("health-status"), fontSize = 15.sp)
        else {
            val modules = health.modules
            val alerts = health.issues.count { it.severity >= 2 }
            Text(tr(when { modules == null -> Res.string.health_waiting
                modules.isEmpty() -> Res.string.health_empty_modules
                alerts > 0 -> Res.string.health_alerts; else -> Res.string.health_no_alerts }, alerts),
                Modifier.testTag("health-status"), fontSize = 15.sp,
                color = if (alerts > 0) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface)
            health.battery?.let { battery ->
                Text(tr(Res.string.health_battery_percent, battery.percent?.toString() ?: "—"), Modifier.testTag("health-battery"), fontSize = 15.sp)
            }
            if (modules != null) {
                health.issues.filter { allMessages || it.severity >= 2 }.forEach { issue ->
                    Column(Modifier.fillMaxWidth().testTag("health-issue-${issue.hexCode}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${robotModuleName(issue.affectedDeviceId)} · ${tr(when (issue.severity) {
                            3 -> Res.string.health_error; 2 -> Res.string.health_warning; 1 -> Res.string.health_prompt; else -> Res.string.health_information
                        })}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(tr(robotIssueMessage(issue)), fontSize = 14.sp)
                    }
                }
                if (health.issues.any { it.severity < 2 }) Button({ allMessages = !allMessages },
                    Modifier.heightIn(min = 48.dp).testTag("health-message-toggle")) {
                    Text(tr(if (allMessages) Res.string.health_hide_information else Res.string.health_show_information))
                }
            }
            RobotInformationContent(model)
        }
    }
}
