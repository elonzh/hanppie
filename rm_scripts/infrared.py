import event_client
import rm_ctrl
import rm_define
import rm_log
import duss_event_msg

logger = rm_log.dji_scratch_logger_get()
event = event_client.EventClient()
modulesStatus_ctrl = rm_ctrl.ModulesStatusCtrl(event)
gun_ctrl = rm_ctrl.GunCtrl(event)
armor_ctrl = rm_ctrl.ArmorCtrl(event)
vision_ctrl = rm_ctrl.VisionCtrl(event)
chassis_ctrl = rm_ctrl.ChassisCtrl(event)
gimbal_ctrl = rm_ctrl.GimbalCtrl(event)
robot_ctrl = rm_ctrl.RobotCtrl(event, chassis_ctrl, gimbal_ctrl)
log_ctrl = rm_ctrl.LogCtrl(event)
# scratch mode only
led_ctrl = rm_ctrl.LedCtrl(event)
media_ctrl = rm_ctrl.MediaCtrl(event)
# need replaced when app changed the method name
time = rm_ctrl.RobotTools(event)
tools = rm_ctrl.RobotTools(event)
debug_ctrl = rm_ctrl.DebugCtrl(event)
mobile_ctrl = rm_ctrl.MobileCtrl(event)
blaster_ctrl = gun_ctrl
AI_ctrl = vision_ctrl

show_msg = log_ctrl.show_msg
print_msg = log_ctrl.print_msg
info_msg = log_ctrl.info_msg
debug_msg = log_ctrl.debug_msg
error_msg = log_ctrl.error_msg
fatal_msg = log_ctrl.fatal_msg
print = print_msg

robot_mode = rm_define.robot_mode
chassis_status = rm_define.chassis_status
gimbal_status = rm_define.gimbal_status
detection_type = rm_define.detection_type
detection_func = rm_define.detection_func
led_effect = rm_define.led_effect
led_position = rm_define.led_position
pwm_port = rm_define.pwm_port
line_color = rm_define.line_color


def fn(msg: duss_event_msg.EventMsg):
    logger.info("attack:", msg.data)


armor_ctrl.set_hit_sensitivity(10)
armor_ctrl.register_event({
    armor_ctrl.ARMOR_ALL_STR: fn,
})
