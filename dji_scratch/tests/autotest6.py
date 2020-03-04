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
    vision_ctrl.register_event_test(globals())
    return result


def unified_callback(msg):
    print('cb type:%d, info:%d' %(msg[0].type, msg[0].info))
    recognition_results[msg[0].type][msg[0].info] = True


recognition_results = {}

event_detection_type_dict = dict(vision_ctrl.event_detection_type_dict)

def vision_callback_create():
    global recognition_results
    global event_detection_type_dict
    vision_callback_dict = globals()
    for (detection_type, info_dict) in event_detection_type_dict.items():
        for (info, fun_str) in info_dict.items():
            if 'all' not in fun_str and 'line' not in fun_str:
                if detection_type not in recognition_results.keys():
                    recognition_results[detection_type] = {}
                recognition_results[detection_type][info] = False
                vision_callback_dict[fun_str] = unified_callback

def start():
    msg_buff = duss_event_msg.EventMsg(tools.hostid2senderid(event_test.my_host_id))
    msg_buff.set_default_receiver(rm_define.scratch_script_id)
    msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_VISION)
    for (k, v) in event_detection_type_dict.items():
        msg_buff.init()
        msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_VISION_DETECTION_MSG_PUSH
        msg_buff.cmd_type = duml_cmdset.REQ_PKG_TYPE
        if len(v) == 1:
            msg_buff.append('detection_type', 'uint8', k)
            msg_buff.append('status', 'uint8', 3)
            msg_buff.append('reserved', 'uint32', 0)
            msg_buff.append('error_code', 'uint16', 0)
            msg_buff.append('rect_count', 'uint8', 1)
            msg_buff.append('info_x', 'float', 0)
            msg_buff.append('info_y', 'float', 0)
            msg_buff.append('info_w', 'float', 0)
            msg_buff.append('info_h', 'float', 0)
            msg_buff.append('info', 'uint32', 0)
            duss_result, resp = event_test.send_sync(msg_buff)
            print('send msg result %d ' %duss_result)
            time.sleep(0.5)
        else:
            for(k1, v1) in v.items():
                if 'all' not in v1:
                    print(v1)
                    msg_buff.append('detection_type', 'uint8', k)
                    msg_buff.append('status', 'uint8', 3)
                    msg_buff.append('reserved', 'uint32', 0)
                    msg_buff.append('error_code', 'uint16', 0)
                    msg_buff.append('rect_count', 'uint8', 1)
                    msg_buff.append('info_x', 'float', 0)
                    msg_buff.append('info_y', 'float', 0)
                    msg_buff.append('info_w', 'float', 0)
                    msg_buff.append('info_h', 'float', 0)
                    msg_buff.append('info', 'uint32', k1)
                    duss_result, resp = event_test.send_sync(msg_buff)
                    print('send msg result %d' %duss_result)
                    time.sleep(0.5)


vision_callback_create()
ready()
start()


test_result = True
for (detection_type, info_dict) in recognition_results.items():
    for (info, res) in info_dict.items():
        if res == False:
            print ('%s is failue' %(event_detection_type_dict[detection_type][info]))
            test_result = False

if test_result == True:
    print ('all successfully')

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
