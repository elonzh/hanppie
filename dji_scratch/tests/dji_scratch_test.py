import event_client
import rm_ctrl
import tools
import rm_define
import time
import math
import script_manage

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

# replace your python code here
def start():
    led_ctrl.set_top_led(rm_define.armor_top_all, 0, 255, 0, rm_define.effect_always_on)
    vision_ctrl.cond_wait(rm_define.cond_recognition_head_shoulder)
    led_ctrl.set_top_led(rm_define.armor_top_all, 255, 255, 0, rm_define.effect_flash)
    armor_ctrl.cond_wait('armor_all')
    led_ctrl.set_top_led(rm_define.armor_top_all, 0, 255, 255, rm_define.effect_always_on)
    time.sleep(5)

def vision_recognition_head_shoulder(v):
    led_ctrl.set_top_led(rm_define.armor_top_all, 255, 0, 0, rm_define.effect_always_on)
    time.sleep(5)

def armor_hitted_detection_all(v):
    led_ctrl.set_bottom_led(rm_define.armor_bottom_all, 255, 0, 0, rm_define.effect_always_on)

def ready():
    result = tank_ctrl.set_work_mode(rm_define.tank_mode_free)
    if (result == rm_define.FAILURE):
        print('Change Tank Ctrl Mode Failed')
    armor_ctrl.register_event(globals())
    vision_ctrl.register_event(globals())
    return result

def wait():
    while(event.is_wait()):
        print('waiting')
        tools.wait(100)

try:
    ready()
except:
    pass

try:
    start()
except:
    pass

gun_ctrl.stop()
gimbal_ctrl.stop()
chassis_ctrl.stop()
vision_ctrl.stop()
armor_ctrl.stop()
led_ctrl.stop()
event.stop()

try:
    wait()
except Exception as e:
    print(e.message)
    print('wait Exception')
    pass

del vision_ctrl
del chassis_ctrl
del gun_ctrl
del gimbal_ctrl
del led_ctrl
del armor_ctrl
del media_ctrl
del tank_ctrl

tools.wait(500)
