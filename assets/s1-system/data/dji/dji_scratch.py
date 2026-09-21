import sys
sys.path.append('/data/dji_scratch/src/robomaster/custom_ui')
sys.path.append('/data/dji_scratch/src/robomaster/multi_comm')
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
import rm_socket
import rm_ctrl

LOG_STREAM_OUT_FLAG = True

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

#creat a ModulesStatusCtrl and init it to get the status of other moudles
modulesStatus_ctrl = rm_ctrl.ModulesStatusCtrl(event_dji_system)
modulesStatus_ctrl.init()
#share the object(modulesStatus_ctrl) to  script_ctrl thredef
script_ctrl.register_modulesStatusCtrl_obj(modulesStatus_ctrl)

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
custom_skill_config_query_id = duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_CUSTOM_SKILL_CONFIG_QUERY
auto_test_id = duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_SCRATCH_AUTO_TEST
update_sys_date_id = duml_cmdset.DUSS_MB_CMDSET_COMMON << 8 | duml_cmdset.DUSS_MB_CMD_SET_DATE

event_dji_system.async_req_register(link_state_id, script_process.get_link_state)
event_dji_system.async_req_register(get_version_id, script_process.request_get_version)
event_dji_system.async_req_register(download_data_id, script_process.request_recv_script_file)
event_dji_system.async_req_register(download_finish_id, script_process.request_create_script_file)
event_dji_system.async_req_register(script_ctrl_id, script_process.request_ctrl_script_file)
event_dji_system.async_req_register(auto_test_id, script_process.request_auto_test)
event_dji_system.async_req_register(update_sys_date_id, script_process.update_sys_date)
event_dji_system.async_req_register(custom_skill_config_query_id, script_process.query_custom_skill_config)


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

UNKNOW = 0
PRO_ROBOMASTER_S1 = 1
PRO_ROBOMASTER_S1_EDU = 2

def is_sdk_enable():
    product_attri_req_msg = duss_event_msg.EventMsg(tools.hostid2senderid(event_dji_system.my_host_id))
    product_attri_req_msg.set_default_receiver(rm_define.system_id)
    product_attri_req_msg.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)
    product_attri_req_msg.set_default_cmdtype(duml_cmdset.NEED_ACK_TYPE)
    product_attri_req_msg.init()
    product_attri_req_msg.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_PRODUCT_ATTRIBUTE_GET
    result, resp = event_dji_system.send_sync(product_attri_req_msg)

    if result == rm_define.DUSS_SUCCESS:
        data = resp['data']
        ret_code = data[0]
        if ret_code != 0:
            logger.error('get product attribute failue, errcode=%d'%data[0])
            return False
        pro = data[1]
        return  pro == PRO_ROBOMASTER_S1_EDU
    else:
        logger.info('Robot is S1')
        return False

socket_ctrl = rm_socket.RmSocket()
uart_ctrl = rm_ctrl.SerialCtrl(event_dji_system)
script_ctrl.register_socket_obj(socket_ctrl)
script_ctrl.register_uart_obj(uart_ctrl)

# TRY ENABLE SDK and determine whether the extension-part can be used in scratch function
try:
    import sdk_manager
    sdk_manager_ctrl = sdk_manager.SDKManager(event_dji_system, socket_ctrl, uart_ctrl)

    retry_count = 3
    while retry_count > 0:
        logger.info('DJI SCRATCH 1 ...')
        retry_count -= 1
        if is_sdk_enable():
            script_ctrl.set_edu_status(True)
            modulesStatus_ctrl.set_edu_status(True)
            sdk_manager_ctrl.enable_plaintext_sdk()
            break
        else:
            time.sleep(1)
    if retry_count <= 0:
        del sdk_manager
        script_ctrl.set_edu_status(False)
        modulesStatus_ctrl.set_edu_status(False)
except Exception as e:
    logger.fatal(e)

socket_ctrl.init()

while not G_SCRIPT_FINISH:
    try:
        # logger.info('DJI SCRATCH 2 ...')
        # event_dji_system.foreach_item()
        logger.info(event_dji_system.recv_thread.name + ' is alive ' + str(event_dji_system.recv_thread.is_alive()))
        time.sleep(5)
    except Exception as e:
        logger.info('DJI SCRATCH fatal  abort ...')
        logger.fatal(traceback.format_exc())
        G_SCRIPT_FINISH = True
        break

script_ctrl.stop()
event_dji_system.stop()

logger.info('DJI SCRATCH EXIT!!!')
