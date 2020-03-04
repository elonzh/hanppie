import event_client
import rm_ctrl
import tools
import rm_define
import time

event = event_client.EventClient()
armor_ctrl = rm_ctrl.ArmorCtrl(event)
gun_ctrl = rm_ctrl.GunCtrl(event)
led_ctrl = rm_ctrl.LedCtrl(event)
tools.wait(50)

def ready():
    armor_ctrl.register_event(globals())

def start():
    while True:
        if armor_ctrl.check_condition(rm_define.cond_armor_hitted):
            led_ctrl.set_led(rm_define.armor_all, 255, 0, 0, rm_define.effect_always_on)
            print('CHECK COND IF:')
            time.sleep(3)
        else:
            led_ctrl.set_led(rm_define.armor_all, 0, 255, 0, rm_define.effect_always_on)
            print('CHECK COND ELSE:')
            time.sleep(1)

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

armor_ctrl.stop()
del armor_ctrl

event.stop()
