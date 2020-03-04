import sys
sys.path.append('/data/dji_scratch/src/robomaster/plaintext_sdk')

import rm_log
import event_client
import script_manage
import duml_cmdset
import rm_define
import duss_event_msg
import tools
import time
import signal
import traceback
import os
import protocal_parser

LOG_STREAM_OUT_FLAG = True
SDK_DEBUG = False

LOG_FILE_OUT_LEVEL = rm_log.INFO
LOG_STREAM_OUT_LEVEL = rm_log.INFO

param = os.sched_param(5)
os.sched_setaffinity(0, (0,1,))
os.sched_setscheduler(0, os.SCHED_RR, param)

logger = rm_log.dji_scratch_logger_get()

event_dji_system = event_client.EventClient(rm_define.system_host_id)

if not LOG_STREAM_OUT_FLAG:
    LOG_STREAM_OUT_LEVEL = None
logger = rm_log.logger_init(logger, event_dji_system, LOG_FILE_OUT_LEVEL, LOG_STREAM_OUT_LEVEL)

local_sub_service = script_manage.LocalSubService(event_dji_system)
script_ctrl = script_manage.ScriptCtrl(event_dji_system)
script_process = script_manage.ScriptProcessCtrl(script_ctrl,local_sub_service)
local_sub_service.init_sys_power_on_time()

protocal_parer_ctrl = protocal_parser.ProtocalParser(event_dji_system)

push_heartbeat_id = duml_cmdset.DUSS_MB_CMDSET_COMMON << 8 | duml_cmdset.DUSS_MB_CMD_COM_HEARTBEAT
event_dji_system.async_req_register(push_heartbeat_id, script_process.request_push_heartbeat)

activeMsg = duss_event_msg.EventMsg(tools.hostid2senderid(event_dji_system.my_host_id))
activeMsg.set_default_receiver(rm_define.system_id)
activeMsg.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)
activeMsg.set_default_cmdtype(duml_cmdset.NEED_ACK_TYPE)

def get_action_state():
    activeMsg.init()
    activeMsg.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_1860_ACTIVE_STATE_GET
    duss_result, resp = event_dji_system.send_sync(activeMsg)
    if resp['data'][1] == 1:
        return True
    else:
        return False

ACTIVE_FLAG = False
while ACTIVE_FLAG:
    logger.fatal('DEVICE NOT BE ACTIVED!')
    #ACTIVE_FLAG = get_action_state()
    if ACTIVE_FLAG:
        break
    time.sleep(2)

# register callback
logger.info('DJI SCRATCH REGISTER CALLBACKS..')
link_state_id = duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_LINK_STATE_PUSH
get_version_id = duml_cmdset.DUSS_MB_CMDSET_COMMON << 8 | duml_cmdset.DUSS_MB_CMD_GET_DEVICE_VERSION
download_data_id = duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_SCRIPT_DOWNLOAD_DATA
download_finish_id = duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_SCRIPT_DOWNLOAD_FINSH
script_ctrl_id = duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_SCRIPT_CTRL
custom_skill_id = duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_CUSTOM_SKILL_CTRL
auto_test_id = duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_SCRATCH_AUTO_TEST
update_sys_date_id = duml_cmdset.DUSS_MB_CMDSET_COMMON << 8 | duml_cmdset.DUSS_MB_CMD_SET_DATE

event_dji_system.async_req_register(link_state_id, script_process.get_link_state)
event_dji_system.async_req_register(get_version_id, script_process.request_get_version)
event_dji_system.async_req_register(download_data_id, script_process.request_recv_script_file)
event_dji_system.async_req_register(download_finish_id, script_process.request_create_script_file)
event_dji_system.async_req_register(script_ctrl_id, script_process.request_ctrl_script_file)
event_dji_system.async_req_register(auto_test_id, script_process.request_auto_test)
event_dji_system.async_req_register(update_sys_date_id, script_process.update_sys_date)

G_SCRIPT_FINISH = False
def QUIT_SIGNAL(signum, frame):
    global G_SCRIPT_FINISH
    logger.info('Signal handler called with signal = ' + str(signum))
    G_SCRIPT_FINISH = True
    return

signal.signal(signal.SIGTSTP, QUIT_SIGNAL)
signal.signal(signal.SIGTERM, QUIT_SIGNAL)
signal.signal(signal.SIGINT, QUIT_SIGNAL)

logger.info('DJI SCRATCH ENTER MAINLOOP...')

pingMsg = duss_event_msg.EventMsg(tools.hostid2senderid(event_dji_system.my_host_id))
pingMsg.set_default_receiver(rm_define.mobile_id)
pingMsg.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)
pingMsg.set_default_cmdtype(duml_cmdset.REQ_PKG_TYPE)

def push_info_to_mobile(content):
    pingMsg.init()
    pingMsg.append('level', 'uint8', 0)
    pingMsg.append('length', 'uint16', len(str(content)))
    pingMsg.append('content', 'string', str(content))
    pingMsg.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SCRIPT_LOG_INFO
    event_dji_system.send_sync(pingMsg)

local_sub_service.enable()

if SDK_DEBUG:
    protocal_parer_ctrl.init()
else:
    # CHECK SDK ENABLE
    if os.path.exists('/data/SDK_ENABLE'):
        protocal_parer_ctrl.init()

while not G_SCRIPT_FINISH:
    try:
        time.sleep(5)
    except Exception as e:
        logger.fatal(traceback.format_exc())
        G_SCRIPT_FINISH = True
        break

script_ctrl.stop()
event_dji_system.stop()

logger.info('DJI SCRATCH EXIT!!!')
