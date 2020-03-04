import event_client
import rm_ctrl
import tools
import rm_define
import time

event = event_client.EventClient()
gimbal_ctrl = rm_ctrl.GimbalCtrl(event)
tools.wait(50)

global_angle1 = 10
global_angle2 = 0

def start():
    global_angle1 = 10
    gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, global_angle1)
    gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, global_angle2)
    global_angle2 = 30
    gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, global_angle2)
    gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, global_angle2)

try:
    start()
except Exception as e:
    print(e.message)
    pass

while(event.is_wait()):
    tools.wait(100)

gimbal_ctrl.stop()
del gimbal_ctrl

tools.wait(500)

