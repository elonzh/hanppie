import os
import sys

paths = [
    "hanppie/lib",
    "hanppie/src/robomaster",
    "hanppie/src/robomaster/custom_ui",
    "hanppie/src/robomaster/multi_comm",
]
pwd = os.getcwd()
sys.path.extend([os.path.join(pwd, p) for p in paths])

import rm_log
import event_client
import duml_cmdset
import rm_define
import duss_event_msg
import tools
import time
import signal
import traceback
import rm_socket
import rm_ctrl

LOG_STREAM_OUT_FLAG = True

LOG_FILE_OUT_LEVEL = rm_log.INFO
LOG_STREAM_OUT_LEVEL = rm_log.INFO

logger = rm_log.dji_scratch_logger_get()

event_dji_system = event_client.EventClient(rm_define.system_host_id)

if not LOG_STREAM_OUT_FLAG:
    LOG_STREAM_OUT_LEVEL = None
logger = rm_log.logger_init(logger, event_dji_system, LOG_FILE_OUT_LEVEL, LOG_STREAM_OUT_LEVEL)

# create a ModulesStatusCtrl and init it to get the status of other moudles
modulesStatus_ctrl = rm_ctrl.ModulesStatusCtrl(event_dji_system)
modulesStatus_ctrl.init()

push_heartbeat_id = duml_cmdset.DUSS_MB_CMDSET_COMMON << 8 | duml_cmdset.DUSS_MB_CMD_COM_HEARTBEAT

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
    # ACTIVE_FLAG = get_action_state()
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
custom_skill_config_query_id = duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_CUSTOM_SKILL_CONFIG_QUERY
auto_test_id = duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_SCRATCH_AUTO_TEST
update_sys_date_id = duml_cmdset.DUSS_MB_CMDSET_COMMON << 8 | duml_cmdset.DUSS_MB_CMD_SET_DATE

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

socket_ctrl = rm_socket.RmSocket()
uart_ctrl = rm_ctrl.SerialCtrl(event_dji_system)

socket_ctrl.init()

while not G_SCRIPT_FINISH:
    try:
        time.sleep(5)
    except Exception as e:
        logger.fatal(traceback.format_exc())
        G_SCRIPT_FINISH = True
        break

event_dji_system.stop()

logger.info('DJI SCRATCH EXIT!!!')
