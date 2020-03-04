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


def ready():
    vision_ctrl.register_event(globals())

def start():
    time.sleep(10)

def vision_recognition_people(msg):
    gun_ctrl.fire()
    led_ctrl.set_led(rm_define.armor_bottom_all, 0, 255, 255, rm_define.effect_always_on)

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

while(event.is_wait()):
    tools.wait(100)

gun_ctrl.stop()
vision_ctrl.stop()
del gun_ctrl
del vision_ctrl

event.stop()
