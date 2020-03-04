import event_client
import rm_ctrl
import tools
import rm_define
import time

event = event_client.EventClient()
media_ctrl = rm_ctrl.MediaCtrl(event)
tools.wait(50)

def start():
    media_ctrl.record(1)
    time.sleep(10)
    media_ctrl.record(0)

try:
    start()
except:
    pass

while(event.is_wait()):
    tools.wait(100)

del media_ctrl

tools.wait(500)

