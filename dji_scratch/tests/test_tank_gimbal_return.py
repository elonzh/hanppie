import event_client
import rm_ctrl
import tools
import rm_define
import time

event = event_client.EventClient()
chassis_ctrl = rm_ctrl.ChassisCtrl(event)
gimbal_ctrl = rm_ctrl.GimbalCtrl(event)
tank_ctrl = rm_ctrl.TankCtrl(chassis_ctrl, gimbal_ctrl)

tools.wait(50)

def start():
    tank_ctrl.set_work_mode(rm_define.tank_mode_free)
    chassis_ctrl.rotate_with_time(rm_define.clockwise, 4)
    chassis_ctrl.rotate_with_time(rm_define.anticlockwise, 4)
    tank_ctrl.set_work_mode(rm_define.tank_mode_free)
    chassis_ctrl.rotate_with_time(rm_define.clockwise, 4)
    chassis_ctrl.rotate_with_time(rm_define.anticlockwise, 4)
    tank_ctrl.set_work_mode(rm_define.tank_mode_chassis_follow)
    gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 90)
    gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 90)

try:
    start()
except Exception as e:
    print(e.message)
    pass

while(event.is_wait()):
    tools.wait(100)

chassis_ctrl.stop()
gimbal_ctrl.stop()
del gimbal_ctrl
del chassis_ctrl
del tank_ctrl

tools.wait(500)

