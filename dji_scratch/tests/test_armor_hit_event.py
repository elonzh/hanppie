import event_client
import rm_ctrl
import tools
import rm_define
import time

event = event_client.EventClient()
robot_tool = rm_ctrl.RobotCtrlTool(event)
chassis_ctrl = rm_ctrl.ChassisCtrl(event)
gimbal_ctrl = rm_ctrl.GimbalCtrl(event)
armor_ctrl = rm_ctrl.ArmorCtrl(event)
gun_ctrl = rm_ctrl.GunCtrl(event)
led_ctrl = rm_ctrl.LedCtrl(event)
tank_ctrl = rm_ctrl.TankCtrl(chassis_ctrl, gimbal_ctrl)
tools.wait(50)

def ready():
    armor_ctrl.register_event(globals())

def start():
    tank_ctrl.set_work_mode(rm_define.tank_mode_free)
    chassis_ctrl.move(rm_define.chassis_front)
    robot_tool.robot_sleep(10)

def armor_hitted_detection_bottom_back():
    print('in event')
    chassis_ctrl.rotate_with_time(rm_define.anticlockwise, 2)

try:
    ready()
except Exception as e:
    pass


start()


armor_ctrl.stop()
del armor_ctrl

event.stop()

