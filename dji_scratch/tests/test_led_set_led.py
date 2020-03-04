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
    led_ctrl.set_led(rm_define.armor_all, 255, 0, 0, rm_define.effect_always_on)
    time.sleep(1)
    led_ctrl.set_led(rm_define.armor_bottom_all, 0, 255, 0, rm_define.effect_always_on)
    time.sleep(1)
    led_ctrl.set_led(rm_define.armor_bottom_all, 0, 0, 255, rm_define.effect_always_on)
    time.sleep(1)
    led_ctrl.set_led(rm_define.armor_all, 0, 0, 255, rm_define.effect_breath)
    time.sleep(5)
    led_ctrl.set_led(rm_define.armor_bottom_all, 255, 255, 0, rm_define.effect_flash)
    time.sleep(5)
    led_ctrl.set_led(rm_define.armor_top_all, 0, 255, 255, rm_define.effect_marquee)
    time.sleep(5)
    led_ctrl.set_led(rm_define.armor_all, 255, 255, 255, rm_define.effect_always_on)

try:
    start()
except Exception as e:
    print(e.message)
    pass

event.stop()
