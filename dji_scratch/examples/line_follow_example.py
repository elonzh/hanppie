import event_client
import rm_ctrl
import tools
import rm_define
import time
import math

event = event_client.EventClient()
chassis_ctrl = rm_ctrl.ChassisCtrl(event)
gun_ctrl = rm_ctrl.GunCtrl(event)
led_ctrl = rm_ctrl.LedCtrl(event)
armor_ctrl = rm_ctrl.ArmorCtrl(event)
vision_ctrl = rm_ctrl.VisionCtrl(event)
gimbal_ctrl = rm_ctrl.GimbalCtrl(event)
media_ctrl = rm_ctrl.MediaCtrl(event)
tank_ctrl = rm_ctrl.TankCtrl(chassis_ctrl, gimbal_ctrl)

gimbal_ctrl.init()
tools.wait(50)

def start():
    gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 20)
    vision_ctrl.set_line_follow_speed(0.3)
    while True:
        vision_ctrl.start_line_follow_until_exception(rm_define.line_follow_color_blue)
        if vision_ctrl.is_cur_line_follow_exception(rm_define.line_intersection_Y)  or vision_ctrl.is_cur_line_follow_exception(rm_define.line_intersection_X):
            if vision_ctrl.is_cur_line_follow_exception(rm_define.marker_trans_left):
                vision_ctrl.line_follow_chassis_ctrl(rm_define.line_follow_chassis_left)
            elif vision_ctrl.is_cur_line_follow_exception(rm_define.marker_trans_right):
                vision_ctrl.line_follow_chassis_ctrl(rm_define.line_follow_chassis_right)
            elif vision_ctrl.is_cur_line_follow_exception(rm_define.marker_trans_forward):
                vision_ctrl.line_follow_chassis_ctrl(rm_define.line_follow_chassis_front)
        elif vision_ctrl.is_cur_line_follow_exception(rm_define.marker_number_zero):
            vision_ctrl.stop_line_follow()
            break
        elif vision_ctrl.is_cur_line_follow_exception(rm_define.line_lost):
            #find line
            gimbal_yaw_ctrl = {'amount':-1200, 'kp':1, 'ki':0, 'kd':0, 'max_speed':1600}
            gimbal_pitch_ctrl = {'amount':0, 'kp':0, 'ki':0, 'kd':0, 'max_speed':0}
            chassis_x_ctrl = {'amount':0, 'kp':1, 'ki':0, 'kd':0, 'max_speed':1}
            chassis_y_ctrl = {'amount':0, 'kp':1, 'ki':0, 'kd':0, 'max_speed':1}
            chassis_yaw_ctrl = {'amount':0, 'kp':1, 'ki':0, 'kd':0, 'max_speed':1}

            gimbal_spd_ctrl = vision_ctrl.set_line_follow_param_gimbal(rm_define.tank_mode_chassis_follow, gimbal_yaw_ctrl, gimbal_pitch_ctrl)
            chassis_spd_ctrl= vision_ctrl.set_line_follow_param_chassis(rm_define.tank_mode_chassis_follow, chassis_x_ctrl, chassis_y_ctrl, chassis_yaw_ctrl)

def ready():
    result = tank_ctrl.set_work_mode(rm_define.tank_mode_chassis_follow)
    if (result == rm_define.FAILURE):
        print('Change Tank Ctrl Mode Failed')
    vision_ctrl.register_event(globals())
    return result

try:
    ready()
except:
    pass

start()

media_ctrl.stop()
gun_ctrl.stop()
gimbal_ctrl.stop()
chassis_ctrl.stop()
vision_ctrl.stop()
armor_ctrl.stop()
led_ctrl.stop()
event.stop()

del vision_ctrl
del chassis_ctrl
del gun_ctrl
del gimbal_ctrl
del led_ctrl
del armor_ctrl
del media_ctrl
del tank_ctrl

tools.wait(500)
