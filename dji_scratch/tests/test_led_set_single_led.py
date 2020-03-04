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
    led_ctrl.set_led(rm_define.armor_top_all, 255, 0, 0, rm_define.effect_always_on)
    time.sleep(1)
    for i in range(1, 9):
        led_ctrl.set_single_led(rm_define.armor_top_all, i, rm_define.effect_always_off)
        print('SET ' + str(i) + ' LED 0 255 255 ALWAYS ON.')
        time.sleep(1)
    led_ctrl.set_led(rm_define.armor_top_all, 255, 0, 0, rm_define.effect_breath)
    for i in range(1, 9):
        led_ctrl.set_single_led(rm_define.armor_top_all, i, rm_define.effect_always_off)
        print('SET ' + str(i) + ' LED 0 255 255 BREATH.')
        time.sleep(1)
    led_ctrl.set_led(rm_define.armor_top_all, 255, 0, 0, rm_define.effect_flash)
    for i in range(1, 9):
        led_ctrl.set_single_led(rm_define.armor_top_all, i, rm_define.effect_always_off)
        print('SET ' + str(i) + ' LED 0 255 255 FLASH.')
        time.sleep(3)
    led_ctrl.set_led(rm_define.armor_top_all, 255, 0, 0, rm_define.effect_marquee)
    for i in range(1, 9):
        led_ctrl.set_single_led(rm_define.armor_top_all, i, rm_define.effect_always_off)
        print('SET ' + str(i) + ' LED 0 255 255 MARQUEE.')
        time.sleep(3)
    led_ctrl.set_led(rm_define.armor_top_all, 255, 0, 0, rm_define.effect_always_on)

try:
    start()
except Exception as e:
    print(e.message)
    pass

while(event.is_wait()):
    tools.wait(100)

del led_ctrl

event.stop()

