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
chassis_ctrl.init()
led_ctrl.init()
tools.wait(50)

def start():
    vision_ctrl.start_line_detection()
    i = 100 #run 20s
    while i < 0:
        x = vision_ctrl.get_line_detection_deviation()
        if x != 0:
          gun_ctrl.fire()
        print('line data', x)
        i = i - 1
        tools.wait(200)

def ready():
    result = tank_ctrl.set_work_mode(rm_define.tank_mode_gimbal_follow)
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

media_ctrl.stop()
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
