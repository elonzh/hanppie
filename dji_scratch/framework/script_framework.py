import event_client
import rm_ctrl
import rm_define
import math
import traceback
import rm_log

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
print=print_msg

robot_mode = rm_define.robot_mode
chassis_status = rm_define.chassis_status
gimbal_status = rm_define.gimbal_status
detection_type = rm_define.detection_type
detection_func = rm_define.detection_func
led_effect = rm_define.led_effect
led_position = rm_define.led_position
pwm_port = rm_define.pwm_port
line_color = rm_define.line_color

def robot_reset():
    robot_ctrl.set_mode(rm_define.robot_mode_free)
    gimbal_ctrl.resume()
    gimbal_ctrl.recenter(90)

def robot_init():
    if 'speed_limit_mode' in globals():
        chassis_ctrl.enable_speed_limit_mode()

    robot_ctrl.init()
    modulesStatus_ctrl.init()
    gimbal_ctrl.init()
    chassis_ctrl.init()
    led_ctrl.init()
    gun_ctrl.init()
    chassis_ctrl.init()
    mobile_ctrl.init()
    tools.init()
    robot_reset()

def ready():
    robot_init()

    robot_ctrl.set_mode(rm_define.robot_mode_gimbal_follow)

    tools.program_timer_start()

def register_event():
    armor_ctrl.register_event(globals())
    vision_ctrl.register_event(globals())
    media_ctrl.register_event(globals())
    chassis_ctrl.register_event(globals())

def start():
    pass

def stop():
    event.script_state.set_script_has_stopped()
    block_description_push(id="ABCDEFGHIJ4567890123", name="STOP", type="INFO_PUSH", curvar="")

def robot_exit():
    robot_reset()
    robot_ctrl.exit()
    gimbal_ctrl.exit()
    chassis_ctrl.exit()
    gun_ctrl.exit()
    mobile_ctrl.exit()
    armor_ctrl.exit()
    media_ctrl.exit()

try:
    ready()

# replace your python code here
SCRATCH_PYTHON_CODE

    register_event()
    start()
    stop()
except:
    _error_msg = traceback.format_exc()
    logger.error('MAIN: script exit, message: ')
    logger.error('TRACEBACK:\n' + _error_msg)
finally:
    gun_ctrl.stop()
    chassis_ctrl.stop()
    gimbal_ctrl.stop()
    media_ctrl.stop()
    vision_ctrl.stop()
    armor_ctrl.stop()
    robot_exit()
    event.stop()
    del event
