import event_client
import rm_ctrl
import tools
import rm_define
import time

event = event_client.EventClient()
led_ctrl = rm_ctrl.LedCtrl(event)
tools.wait(50)

def start():
    led_ctrl.set_led(rm_define.armor_all, 255, 0, 0, rm_define.effect_always_off)
    time.sleep(1)
    led_ctrl.set_flash(rm_define.armor_all, 2)
    time.sleep(3)
    led_ctrl.set_flash(rm_define.armor_all, 5)
    time.sleep(3)
    led_ctrl.set_flash(rm_define.armor_all, 10)
    time.sleep(3)
    led_ctrl.turn_off(rm_define.armor_all)

try:
    start()
except Exception as e:
    print(e.message)
    pass

while(event.is_wait()):
    tools.wait(100)

del led_ctrl

tools.wait(500)

