import event_client
import rm_ctrl
import tools
import rm_define
import time

event = event_client.EventClient()
vision_ctrl = rm_ctrl.VisionCtrl(event)
gun_ctrl = rm_ctrl.GunCtrl(event)
tools.wait(50)

def vision_recognition_marker_trans_red(msg):
    gun_ctrl.fire()


def vision_recognition_marker_trans_yellow(msg):
    gun_ctrl.fire()


def vision_recognition_marker_trans_green(msg):
    gun_ctrl.fire()


def vision_recognition_marker_trans_left(msg):
    gun_ctrl.fire()


def vision_recognition_marker_trans_right(msg):
    gun_ctrl.fire()


def vision_recognition_marker_trans_forward(msg):
    gun_ctrl.fire()


def vision_recognition_marker_trans_backward(msg):
    gun_ctrl.fire()


def vision_recognition_marker_trans_red_heart(msg):
    gun_ctrl.fire()


def vision_recognition_marker_trans_sword(msg):
    gun_ctrl.fire()


def ready():
    vision_ctrl.register_event(globals())

def start():
    time.sleep(50)

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

