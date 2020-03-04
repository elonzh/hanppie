import event_client
import rm_ctrl
import rm_define
import math
import traceback
import rm_log

logger_file = rm_log.logger_file_out_path_generate('dji_scratch')
logger = rm_log.dji_scratch_logger_get()
logger = rm_log.logger_init(logger, logger_file, rm_log.DEBUG)
logger.info('DJI_SCRATCH: create log file is %s' %logger_file)



logger = rm_log.dji_scratch_logger_get()
event = event_client.EventClient()
gun_ctrl = rm_ctrl.GunCtrl(event)
armor_ctrl = rm_ctrl.ArmorCtrl(event)
vision_ctrl = rm_ctrl.VisionCtrl(event)
chassis_ctrl = rm_ctrl.ChassisCtrl(event)
gimbal_ctrl = rm_ctrl.GimbalCtrl(event)
tank_ctrl = rm_ctrl.TankCtrl(chassis_ctrl, gimbal_ctrl)
# scratch mode only
led_ctrl = rm_ctrl.LedCtrl(event)
media_ctrl = rm_ctrl.MediaCtrl(event)
# need replaced when app changed the method name
time = rm_ctrl.RobotCtrlTool(event)
tools = rm_ctrl.RobotCtrlTool(event)

def robot_reset():
    gimbal_ctrl.set_work_mode(rm_define.gimbal_free_mode)
    gimbal_ctrl.return_middle(90)

def robot_init():
    gimbal_ctrl.init()
    led_ctrl.init()
    gun_ctrl.init()
    robot_reset()

def ready():
    robot_init()

    tank_ctrl.set_work_mode(rm_define.tank_mode_free)
    armor_ctrl.register_event(globals())
    vision_ctrl.register_event(globals())

def stop():
    event.script_state.set_script_has_stopped()

def robot_exit():
    robot_reset()
    gimbal_ctrl.set_work_mode(rm_define.gimbal_yaw_follow_mode)
    chassis_ctrl.set_mode(rm_define.chassis_fpv_mode)


offset_x = 0.5
offset_y = 0.5
flag = False

flag = 1

def vision_recognition_marker_all(msg):
    global flag
    if vision_ctrl.check_condition(rm_define.cond_recognition_marker_number_one) and flag == 1:
        vision_ctrl.marker_detection_and_aim(rm_define.marker_number_one)
        flag = flag + 1;
        gun_ctrl.fire()
    elif vision_ctrl.check_condition(rm_define.cond_recognition_marker_number_two) and flag == 2:
        vision_ctrl.marker_detection_and_aim(rm_define.marker_number_two)
        flag = flag + 1;
        gun_ctrl.fire()
    elif vision_ctrl.check_condition(rm_define.cond_recognition_marker_number_three) and flag == 3:
        vision_ctrl.marker_detection_and_aim(rm_define.marker_number_three)
        flag = flag + 1;
        gun_ctrl.fire()
    elif vision_ctrl.check_condition(rm_define.cond_recognition_marker_number_four) and flag == 4:
        vision_ctrl.marker_detection_and_aim(rm_define.marker_number_four)
        flag = flag + 1;
        gun_ctrl.fire()
    elif vision_ctrl.check_condition(rm_define.cond_recognition_marker_number_five) and flag == 5:
        vision_ctrl.marker_detection_and_aim(rm_define.marker_number_five)
        flag = flag + 1;
        gun_ctrl.fire()
    elif vision_ctrl.check_condition(rm_define.cond_recognition_marker_number_six) and flag == 6:
        vision_ctrl.marker_detection_and_aim(rm_define.marker_number_six)
        flag = flag + 1;
        gun_ctrl.fire()
    elif vision_ctrl.check_condition(rm_define.cond_recognition_marker_number_seven) and flag == 7:
        vision_ctrl.marker_detection_and_aim(rm_define.marker_number_seven)
        flag = flag + 1;
    elif vision_ctrl.check_condition(rm_define.cond_recognition_marker_number_eight) and flag == 8:
        vision_ctrl.marker_detection_and_aim(rm_define.marker_number_eight)
        flag = flag + 1;
        gun_ctrl.fire()
    elif vision_ctrl.check_condition(rm_define.cond_recognition_marker_number_nine) and flag  == 9:
        vision_ctrl.marker_detection_and_aim(rm_define.marker_number_nine)
        flag = 1;
        gun_ctrl.fire()

def start():
    vision_ctrl.set_marker_detection_distance(200)
    time.sleep(80)

try:
# replace your python code here
    ready()
    start()
    stop()
except:
    logger.error('MAIN: script exit, message: ')
    logger.error('TRACEBACK:\n' + traceback.format_exc())
finally:
    media_ctrl.stop()
    led_ctrl.stop()
    gun_ctrl.stop()
    armor_ctrl.stop()
    vision_ctrl.stop()
    gimbal_ctrl.stop()
    chassis_ctrl.stop()
    robot_exit()
    event.stop()
    del event
