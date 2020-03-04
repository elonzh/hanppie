import event_client
import rm_define
import time
import tools
import rm_ctrl
import threading
import duml_cmdset
import duss_event_msg

event = event_client.EventClient()

def check_dji_armor_version(event_client):
    msg_buff = duss_event_msg.EventMsg(tools.hostid2senderid(event_client.my_host_id))
    msg_buff.set_default_receiver(rm_define.armor1_id)
    msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_COMMON)
    while True:
        msg_buff.init()
        msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_GET_DEVICE_VERSION
        duss_result, resp = event_client.send_sync(msg_buff)
        print('CHECK DJI ARMOR 1 result = ' + str(duss_result))
        tools.wait(67)

def check_dji_gun_version(event_client):
    msg_buff = duss_event_msg.EventMsg(tools.hostid2senderid(event_client.my_host_id))
    msg_buff.set_default_receiver(rm_define.gun_id)
    msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_COMMON)
    while True:
        msg_buff.init()
        msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_GET_DEVICE_VERSION
        duss_result, resp = event_client.send_sync(msg_buff)
        print('CHECK DJI GUN result = ' + str(duss_result))
        tools.wait(67)

def check_dji_hdvt_uav_version(event_client):
    msg_buff = duss_event_msg.EventMsg(tools.hostid2senderid(event_client.my_host_id))
    msg_buff.set_default_receiver(rm_define.hdvt_uav_id)
    msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_COMMON)
    while True:
        msg_buff.init()
        msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_GET_DEVICE_VERSION
        duss_result, resp = event_client.send_sync(msg_buff)
        print('CHECK DJI HDVT UAV result = ' + str(duss_result))
        tools.wait(59)

def check_chassis_version(event_client):
    msg_buff = duss_event_msg.EventMsg(tools.hostid2senderid(event_client.my_host_id))
    msg_buff.set_default_receiver(rm_define.chassis_id)
    msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_COMMON)
    while True:
        msg_buff.init()
        msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_GET_DEVICE_VERSION
        duss_result, resp = event_client.send_sync(msg_buff)
        print('CHECK DJI CHASSIS result = ' + str(duss_result))
        tools.wait(59)

def check_gimbal_version(event_client):
    msg_buff = duss_event_msg.EventMsg(tools.hostid2senderid(event_client.my_host_id))
    msg_buff.set_default_receiver(rm_define.gimbal_id)
    msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_COMMON)
    while True:
        msg_buff.init()
        msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_GET_DEVICE_VERSION
        duss_result, resp = event_client.send_sync(msg_buff)
        print('CHECK DJI GIMBAL result = ' + str(duss_result))
        tools.wait(67)

thread1 = threading.Thread(target=check_dji_armor_version, args=(event,))
thread2 = threading.Thread(target=check_dji_gun_version, args=(event,))
thread3 = threading.Thread(target=check_dji_hdvt_uav_version, args=(event,))
thread4 = threading.Thread(target=check_gimbal_version, args=(event,))
thread5 = threading.Thread(target=check_chassis_version, args=(event,))

thread1.start()
thread2.start()
thread3.start()
thread4.start()
thread5.start()

while True:
    time.sleep(1)

event.stop()
