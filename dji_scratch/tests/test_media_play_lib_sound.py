import event_client
import rm_ctrl
import tools
import rm_define
import time

event = event_client.EventClient()
media_ctrl = rm_ctrl.MediaCtrl(event)
tools.wait(50)

def start():
    media_ctrl.play_lib_sound(2)
    time.sleep(5)
    media_ctrl.play_lib_sound(2)
    time.sleep(5)
    media_ctrl.play_lib_sound(3)
    time.sleep(5)

try:
    start()
except Exception as e:
    print(e.message)
    pass

while(event.is_wait()):
    tools.wait(100)

del media_ctrl

tools.wait(500)
