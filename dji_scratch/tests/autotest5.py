import event_client
import rm_ctrl
import rm_define
import math
import traceback
import rm_log
import scratch_unittest
import duss_event_msg
import duml_cmdset
import tools

logger = rm_log.dji_scratch_logger_get()
event = event_client.EventClient()
event_test = event_client.EventClient(0x907)
# need to use other methods
time = rm_ctrl.RobotCtrlTool(event)
chassis_ctrl = rm_ctrl.ChassisCtrl(event)
gun_ctrl = rm_ctrl.GunCtrl(event)
led_ctrl = rm_ctrl.LedCtrl(event)
armor_ctrl = rm_ctrl.ArmorCtrl(event)
vision_ctrl = rm_ctrl.VisionCtrl(event)
gimbal_ctrl = rm_ctrl.GimbalCtrl(event)
media_ctrl = rm_ctrl.MediaCtrl(event)
tank_ctrl = rm_ctrl.TankCtrl(chassis_ctrl, gimbal_ctrl)
test_client = scratch_unittest.TestResult()

gimbal_ctrl.init()
led_ctrl.init()
tools.wait(50)

def robot_init():
    gimbal_ctrl.return_middle(90)

def ready():
    result = tank_ctrl.set_work_mode(rm_define.tank_mode_free)
    if (result == rm_define.FAILURE):
        logger.error('Change Tank Ctrl Mode Failed')

    robot_init()

    result = tank_ctrl.set_work_mode(rm_define.tank_mode_gimbal_follow)
    if (result == rm_define.FAILURE):
        logger.error('Change Tank Ctrl Mode Failed')
    armor_ctrl.register_event(globals())
    vision_ctrl.register_event(globals())
    return result

hit_results = [False, False, False, False, False, False, False]

def armor_hitted_detection_bottom_back(msg):
    print('1')
    hit_results[0] = True

def armor_hitted_detection_top_right(msg):
    print('2')
    hit_results[1] = True

def armor_hitted_detection_bottom_left(msg):
    print('3')
    hit_results[2] = True

def armor_hitted_detection_all(msg):
    print('4')
    hit_results[3] = True

def armor_hitted_detection_bottom_right(msg):
    print('5')
    hit_results[4] = True

def armor_hitted_detection_top_left(msg):
    print('6')
    hit_results[5] = True

def armor_hitted_detection_bottom_front(msg):
    print('7')
    hit_results[6] = True


def start():
    for i in range(6):
        msg_buff = duss_event_msg.EventMsg(tools.hostid2senderid(event_test.my_host_id))
        msg_buff.set_default_receiver(rm_define.scratch_script_id)
        msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)
        msg_buff.init()
        msg_buff.append('arm', 'uint8', (i + 1) << 4)
        msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_HIT_EVENT
        msg_buff.cmd_type = duml_cmdset.REQ_PKG_TYPE
        duss_result, resp = event_test.send_sync(msg_buff)
        print(duss_result)
        time.sleep(1)

ready()
start()


test_result = True
for res in hit_results:
    if res == False:
        test_result = False

media_ctrl.stop()
gun_ctrl.stop()
gimbal_ctrl.stop()
chassis_ctrl.stop()
vision_ctrl.stop()
armor_ctrl.stop()
led_ctrl.stop()
event.stop()
del event

# set test results
test_client.set_test_result(test_result)
test_client.set_test_finished()

while not test_client.get_test_exit():
    tools.wait(100)