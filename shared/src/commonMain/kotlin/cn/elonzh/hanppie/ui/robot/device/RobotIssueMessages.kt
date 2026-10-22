package cn.elonzh.hanppie.ui.robot.device

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.telemetry.RobotIssue
import org.jetbrains.compose.resources.StringResource

/** Message identities/ranges from fixed build 239; wording is maintained in our own resources. */
internal fun robotIssueMessage(issue: RobotIssue): StringResource {
    val value = issue.detail
    return when (issue.deviceId) {
        0x0100 -> when (value) {
            0x8000 -> Res.string.health_controller_hot
            0xc001 -> Res.string.health_controller_restart
            0x8004 -> Res.string.health_firmware_mismatch
            0xc005 -> Res.string.health_fan_fault
            0xc007 -> Res.string.health_multiple_chassis
            0xc008 -> Res.string.health_multiple_gimbals
            0xc009 -> Res.string.health_multiple_blasters
            in 0x800a..0x800d -> Res.string.health_armor_address
            0xc00e, 0xc00f -> Res.string.health_head_armor
            0x4200 -> Res.string.health_target_far
            0x4201 -> Res.string.health_target_near
            in 0x14..0x18, in 0x1a..0x1f -> Res.string.health_not_activated
            0x100 -> Res.string.health_process_restart
            0x300 -> Res.string.health_channel_timeout
            0x301 -> Res.string.health_wifi_disconnected
            0x302 -> Res.string.health_ip_unavailable
            0x303 -> Res.string.health_usb_unavailable
            6 -> Res.string.health_robot_started
            else -> Res.string.health_unknown_issue
        }
        0x0104 -> when (value) {
            0xc400, 0xc401 -> Res.string.health_camera_fault
            0x8402 -> Res.string.health_bright_light
            else -> Res.string.health_unknown_issue
        }
        0x0300 -> when (value) {
            0x8065 -> Res.string.health_pwm_current
            in 0x8101..0x8196 -> Res.string.health_chassis_sensor
            0x8197 -> Res.string.health_tilt_protection
            0x8198 -> Res.string.health_chassis_calibrate
            0x8201 -> Res.string.health_battery_critical
            0x8202 -> Res.string.health_battery_low
            0x8204 -> Res.string.health_battery_hot
            0xc205 -> Res.string.health_battery_read
            0x8207 -> Res.string.health_shutting_down
            0x8208 -> Res.string.health_restarting
            0xc209 -> Res.string.health_battery_auth
            0xc20a -> Res.string.health_battery_fault
            0x820b -> Res.string.health_battery_current
            0xc401 -> Res.string.health_controller_link
            0x8501 -> Res.string.health_gimbal_link
            0xc502 -> Res.string.health_gimbal_state
            0xcb01, 0xcc02, 0xcd03, 0xce04 -> Res.string.health_motor_fault
            0x8b0b, 0x8c0c, 0x8d0d, 0x8e0e -> Res.string.health_motor_hot
            else -> Res.string.health_unknown_issue
        }
        in 0x0314..0x0317 -> {
            val motorCode = 0xd400 + (issue.deviceId - 0x0314) * 0x100
            when (value - motorCode) {
                0x88, 0x8a -> Res.string.health_motor_protection
                in 1..0x87, 0x89, in 0x8b..0xfe -> Res.string.health_motor_fault
                else -> Res.string.health_unknown_issue
            }
        }
        0x030a -> if (value in 0x8a01..0x8afe) Res.string.health_battery_fault else Res.string.health_unknown_issue
        0x0400 -> when (value) {
            0xc000, 0xc008, 0xc100 -> Res.string.health_gimbal_motor
            0xc001 -> Res.string.health_gimbal_link
            0xc002 -> Res.string.health_gimbal_start
            0xc003 -> Res.string.health_gimbal_vibration
            0xc004 -> Res.string.health_gimbal_oscillation
            0x4005 -> Res.string.health_gimbal_balance
            0x8006 -> Res.string.health_gimbal_hot
            0x8007 -> Res.string.health_gimbal_blocked
            0xc009 -> Res.string.health_gimbal_sensor
            0xc00b -> Res.string.health_gimbal_attitude
            0xc00c -> Res.string.health_gimbal_calibrate
            else -> Res.string.health_unknown_issue
        }
        0x1700 -> when (value) {
            0x8001 -> Res.string.health_fire_rate
            0x8002 -> Res.string.health_blaster_hot
            0x8004 -> Res.string.health_blaster_protection
            0xc003 -> Res.string.health_blaster_fault
            else -> Res.string.health_unknown_issue
        }
        in 0x1801..0x1806 -> when {
            value == 0x8000 -> Res.string.health_armor_fault
            issue.deviceId in 0x1805..0x1806 && value == 0x8001 -> Res.string.health_armor_infrared
            else -> Res.string.health_unknown_issue
        }
        else -> Res.string.health_unknown_issue
    }
}
