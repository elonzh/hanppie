import event_client
import rm_ctrl
import tools
import rm_define
import time

event = event_client.EventClient()
debug_ctrl = rm_ctrl.DebugCtrl(event)
gun_ctrl = rm_ctrl.GunCtrl(event)
tools.wait(50)

def start():
    gun_ctrl.set_leaser(1)
    time.sleep(1)
    gun_ctrl.set_leaser(0)
    time.sleep(1)

try:
    start()
except Exception as e:
    print(e.message)
    pass

while(event.is_wait()):
    tools.wait(100)


tools.wait(500)

