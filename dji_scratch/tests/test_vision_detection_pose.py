import event_client
import rm_ctrl
import tools
import rm_define
import time

event = event_client.EventClient()
vision_ctrl = rm_ctrl.VisionCtrl(event)
gun_ctrl = rm_ctrl.GunCtrl(event)
tools.wait(50)

def vision_recognition_pose_victory(msg):
    gun_ctrl.fire()

def vision_recognition_pose_give_in(msg):
    gun_ctrl.fire()

def ready():
    vision_ctrl.register_event(globals())

def start():
    time.sleep(10)

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

