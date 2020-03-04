import event_client
import rm_ctrl
import tools
import rm_define
import time

event = event_client.EventClient()
vision_ctrl = rm_ctrl.VisionCtrl(event)
gun_ctrl = rm_ctrl.GunCtrl(event)
led_ctrl = rm_ctrl.LedCtrl(event)
tools.wait(50)

def start():
    while True:
        if vision_ctrl.check_condition(rm_define.cond_recognition_people):
            led_ctrl.set_led(rm_define.armor_all, 255, 0, 0, rm_define.effect_always_on)
            print('CHECK COND IF:')
            time.sleep(3)
        else:
            led_ctrl.set_led(rm_define.armor_all, 0, 255, 0, rm_define.effect_always_on)
            print('CHECK COND ELSE:')
            time.sleep(1)
    time.sleep(10)

try:
    ready()
except Exception as e:
    print(e.message)
    pass

try:
    start()
except Exception as e:
    print(e.message)
    pass

gun_ctrl.stop()
vision_ctrl.stop()
led_ctrl.stop()
event.stop()
