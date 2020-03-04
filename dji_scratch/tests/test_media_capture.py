import event_client
import rm_ctrl
import tools
import rm_define

event = event_client.EventClient()
media_ctrl = rm_ctrl.MediaCtrl(event)
tools.wait(50)

def start():
    media_ctrl.capture()
    media_ctrl.capture()
    media_ctrl.capture()

try:
    start()
except Exception as e:
    print(e.message)
    pass

del media_ctrl

event.stop()
