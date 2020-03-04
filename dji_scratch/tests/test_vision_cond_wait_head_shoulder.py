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
    led_ctrl.set_led(rm_define.armor_all, 255, 0, 0, rm_define.effect_always_on)
    time.sleep(3)
    vision_ctrl.cond_wait(rm_define.cond_recognition_head_shoulder)
    led_ctrl.set_led(rm_define.armor_all, 0, 255, 0, rm_define.effect_always_on)
    time.sleep(3)

try:
    start()
except Exception as e:
    print(e.message)
    pass

while(event.is_wait()):
    tools.wait(100)

gun_ctrl.stop()
vision_ctrl.stop()
led_ctrl.stop()

event.stop()
