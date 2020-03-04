import socket
import select
import threading
import duss_event_msg
import tools
import rm_define
import duml_cmdset
import user_script_ctrl
import sys
import traceback
import rm_log
import time

logger = rm_log.dji_scratch_logger_get()

default_target_address = '\0/duss/mb/0x900'

DUSS_EVENT_MSG_HEADER_LEN = 4

class EventAckIdentify(object):
    def __init__(self):
        self.valid = False
        self.identify = 0
        self.wait_ack_event = threading.Event()

class EventClient(object):
    DEFAULT_ROUTE_FILE = '/system/etc/dji.json'

    def __init__(self, host_id = rm_define.script_host_id):
        self.debug = False
        self.route_table, _, _ = tools.load_route_table(EventClient.DEFAULT_ROUTE_FILE, 'scratch_service', 'scratch_client')
        self.my_server_address = '\0/duss/mb/' + str(hex(host_id))
        self.my_host_id = host_id
        self.wait_ack_list = {}
        self.wait_ack_mutex = threading.Lock()
        self.wait_ack_event_list = []
        self.cur_task_attri = {}
        self.finish = False
        self.script_state = user_script_ctrl.UserScriptCtrl()
        self.async_req_cb_list = {}
        self.async_ack_cb_list = {}
        self.event_process_mutex = threading.Lock()
        self.event_process_list = {}
        self.event_callback_list = {}
        self.event_notify_mutex = threading.Lock()
        self.event_notify_list = {}
        self.event_notify_not_register_dict = {}
        self.event_process_flag = False
        self.async_cb_mutex = threading.Lock()
        self.check_event_msg_invalid_callback = None
        self.already_finish_task_identify_set = set()

        for i in range(1, 9):
            ackIdentify = EventAckIdentify()
            self.wait_ack_event_list.append(ackIdentify)
        logger.info('WAIT ACK EVENT LIST LEN = ' + str(len(self.wait_ack_event_list)))

        self.socketfd = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        self.my_server = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        try:
            self.my_server.bind(self.my_server_address)
            self.recv_thread = threading.Thread(target=self.__recv_task)
            self.recv_thread.start()
        except Exception as e:
            logger.fatal('EventClient: server error, message: ')
            logger.fatal('TRACEBACK:\n' + traceback.format_exc())
            sys.exit(-1)

    def stop(self):
        logger.info('EventClient: STOP')
        self.finish = True
        # send something to quit.
        self.send2myself('quit')
        self.recv_thread.join(3)
        logger.info('EventClient: recv thread alive = ' + str(self.recv_thread.isAlive()))
        self.socketfd.close()
        self.my_server.close()
        logger.info('EventClient host id = ' + str(self.my_host_id) + ' Exit!!!')

    def __recv_task(self):
        logger.info('START RECVING TASK...')
        while self.finish == False:

            recv_buff, host = self.my_server.recvfrom(1024)

            if self.finish:
                logger.info('EventClient: NEED QUIT!')
                break
            if recv_buff == None:
                logger.fatal('FATAL ERROR RECV BUFF = NONE!!!')
                continue
            # pack.unpack -> EventMsg.

            msg = duss_event_msg.unpack(recv_buff)

            if msg == None:
                continue

            # whether task push msg
            if msg['cmd_set'] == duml_cmdset.DUSS_MB_CMDSET_RM:
                if msg['cmd_id'] == duml_cmdset.DUSS_MB_CMD_RM_GIMBAL_POSITION_TASK_PUSH or msg['cmd_id'] == duml_cmdset.DUSS_MB_CMD_RM_CHASSIS_POSITION_TASK_PUSH or msg['cmd_id'] == duml_cmdset.DUSS_MB_CMD_RM_PLAY_SOUND_TASK_PUSH:
                    msg['task_id'] = msg['data'][0]
                    task_identify = str(msg['cmd_set']) + str(msg['cmd_id']) + str(msg['task_id'])
                    self.task_sync_process(task_identify, msg)
                    continue

            # handle_one_message()
            if msg['ack'] == True:
                identify = str(msg['sender']) + str(msg['cmd_set']) + str(msg['cmd_id']) + str(msg['seq_num'])
                #logger.info('GET ACK MSG: identify = ' + identify)
                self.wait_ack_mutex.acquire()
                if identify in self.wait_ack_list.keys():
                    for j in range(0, len(self.wait_ack_event_list)):
                        if self.wait_ack_event_list[j].valid and self.wait_ack_event_list[j].identify == identify:
                            self.wait_ack_list[identify] = msg
                            self.wait_ack_event_list[j].wait_ack_event.set()
                else:
                    logger.warn('SENDER = ' + str(msg['sender']) + ' CMDSET = ' + str(hex(msg['cmd_set'])) + ' CMDID = ' + str(hex(msg['cmd_id'])) + ' NOT IN WAIT ACK LIST!!!')
                self.wait_ack_mutex.release()

                # TODO self.async_ack_cb_list
                cmd_set_id = msg['cmd_set'] << 8 | msg['cmd_id']
                self.async_cb_mutex.acquire()
                if cmd_set_id in self.async_ack_cb_list.keys():
                    #logger.info('ASYNC ACK MSG = ' + str(cmd_set_id) + ' HANDLE.')
                    self.async_ack_cb_list[cmd_set_id](self, msg)
                self.async_cb_mutex.release()
            else:
                # TODO self.async_req_cb_list
                cmd_set_id = msg['cmd_set'] << 8 | msg['cmd_id']
                #if msg['cmd_set'] != 0x04:
                #    logger.info('CMD SETID = ' + str(cmd_set_id))
                self.async_cb_mutex.acquire()
                if cmd_set_id in self.async_req_cb_list.keys():
                    #logger.info('ASYNC MSG = ' + str(cmd_set_id) + ' HANDLE.')
                    self.async_req_cb_list[cmd_set_id](self, msg)
                else:
                    logger.warn('UNSUPPORT MSG CMD SET = ' + str(msg['cmd_set']) + ', CMD ID = ' + str(msg['cmd_id']))
                self.async_cb_mutex.release()

        logger.info('RECV TASK FINISH!!!')

    def send(self, data, target_address):
        try:
            self.socketfd.sendto(data, target_address)
        except:
            return duml_cmdset.DUSS_MB_RET_ACK

    def send2myself(self, data):
        try:
            self.socketfd.sendto(data.encode('utf-8'), '\0/duss/mb/' + str(hex(self.my_host_id)))
        except Exception as e:
            logger.error('EVENT_CLIENT: send2myself() error, message: ')
            logger.error(traceback.logger.info_exc())

    def is_wait(self):
        return False


##############################################################################################
    # TODO return value, if callback is already exist, support multip callback?
    def async_req_register(self, cmd_set_id, callback):
        logger.info('ASYNC REGISTER CMD_SETID = ' + str(hex(cmd_set_id)))
        self.async_cb_mutex.acquire()
        self.async_req_cb_list[cmd_set_id] = callback
        self.async_cb_mutex.release()

    def async_req_unregister(self, cmd_set_id):
        logger.info('ASYNC UNREGISTER CMD_SETID = ' + str(cmd_set_id))
        self.async_cb_mutex.acquire()
        if cmd_set_id in self.async_req_cb_list.keys():
            self.async_req_cb_list.pop(cmd_set_id)
        self.async_cb_mutex.release()

    def async_ack_register(self, cmd_set_id, callback):
        self.async_cb_mutex.acquire()
        if cmd_set_id in self.async_req_cb_list.keys():
            self.async_ack_cb_list[cmd_set_id] = callback
        self.async_cb_mutex.release()

    def async_ack_unregister(self, cmd_set_id, callback):
        self.async_cb_mutex.acquire()
        if cmd_set_id in self.async_ack_cb_list.keys():
            self.async_ack_cb_list.pop(cmd_set_id)
        self.async_cb_mutex.release()

    def event_notify_register(self, event_name, robot_event):
        self.event_notify_mutex.acquire()
        if event_name in self.event_notify_not_register_dict.keys() and time.time() - self.event_notify_not_register_dict[event_name] < 5:
            robot_event.notify_for_task_complete()
            self.event_notify_not_register_dict.pop(event_name)
        else:
            self.event_notify_list[event_name] = robot_event
        self.event_notify_mutex.release()

    def event_notify(self, event_name):
        self.event_notify_mutex.acquire()
        if event_name in self.event_notify_list.keys():
            robot_event = self.event_notify_list[event_name]
            robot_event.notify_for_task_complete()
        else:
            self.event_notify_not_register_dict[event_name] = time.time()
        self.event_notify_mutex.release()

    def event_watchdog_set(self, event_name):
        self.event_notify_mutex.acquire()
        if event_name in self.event_notify_list.keys():
            robot_event = self.event_notify_list[event_name]
            robot_event.watchdog_set()
        self.event_notify_mutex.release()

    def event_notify_unregister(self, event_name):
        self.event_notify_mutex.acquire()
        if event_name in self.event_notify_list.keys():
            self.event_notify_list.pop(event_name)
        self.event_notify_mutex.release()

    def event_callback_register(self, event_name, callback):
        self.event_process_mutex.acquire()
        self.event_callback_list[event_name] = callback
        self.event_process_list[event_name] = {'callback':None, 'callback_data':None}
        self.event_process_mutex.release()

    def event_come_to_process(self, event_name, callback_data=None):
        self.event_process_mutex.acquire()
        if event_name in self.event_callback_list.keys():
            self.event_process_list[event_name]['callback'] = self.event_callback_list[event_name]
            self.event_process_list[event_name]['callback_data'] = callback_data
        else:
            logger.error('EVENTCTRL: NO CB REGISTER, FUNC IS %s', event_name)
        self.event_process_mutex.release()

    def wait_for_event_process(self, func_before_event):
        callback_list = []
        # append the event to list
        self.event_process_mutex.acquire()
        for (event_name, callback_items) in self.event_process_list.items():
            if callback_items['callback'] != None and callback_items['callback'].__name__ != 'dummy_callback':
                callback_list.append(callback_items)
                self.event_process_list[event_name] = {'callback':None, 'callback_data':None}
        self.event_process_mutex.release()

        # no event need to prcess or has event being processing
        if len(callback_list) <= 0 or self.event_process_flag:
            return False

        # exec func before process event
        if func_before_event != None:
            func_before_event()

        # set event process flag to true
        self.event_process_flag = True
        for item in callback_list:
            func = item['callback']
            data = item['callback_data']
            func(data)
        self.event_process_flag = False
        return True

    def ack_register_identify(self, event_msg):
        self.wait_ack_mutex.acquire()
        identify = str(event_msg.receiver) + str(event_msg.cmd_set) + str(event_msg.cmd_id) + str(event_msg.seq_num)
        #logger.info('ACK REGISTER IDENTIFY = ' + identify)
        self.wait_ack_list[identify] = True
        self.wait_ack_mutex.release()
        return identify

    def ack_unregister_identify(self, identify):
        resp = {}
        self.wait_ack_mutex.acquire()
        if identify in self.wait_ack_list.keys():
            resp = self.wait_ack_list.pop(identify)
        self.wait_ack_mutex.release()
        return resp

    # return duss_result
    def send_msg(self, event_msg):
        try:
            if self.debug:
                logger.info(str(event_msg.data))

            target_address = default_target_address
            if event_msg.receiver in self.route_table.keys():
                target_address = self.route_table[event_msg.receiver]['target_address']
            data = event_msg.pack()
            self.send(data, target_address)
        except Exception as e:
            logger.fatal("Exception in send_msg, " + traceback.format_exc())
            return rm_define.DUSS_ERR_FAILURE
        return rm_define.DUSS_SUCCESS

    # return duss_result, resp
    def send_sync(self, event_msg, time_out = duml_cmdset.MSG_DEFAULT_TIMEOUT):
        #logger.info('RECEIVER = ' + str(event_msg.receiver) + ', CMDSET = ' + str(hex(event_msg.cmd_set)) + ', CMDID = ' + str(hex(event_msg.cmd_id)))
        duss_result = rm_define.DUSS_SUCCESS
        check_result, invalid_code = self.check_event_msg_invalid(event_msg)
        if check_result == True:
            logger.warn('MODULE %d OFFLINE OR ERROR %s'%(event_msg.receiver, invalid_code))
            return invalid_code, None
        if event_msg.cmd_type & duml_cmdset.NEED_ACK_TYPE:
            identify = self.ack_register_identify(event_msg)
            j = 0
            for j in range(0, len(self.wait_ack_event_list)):
                if not self.wait_ack_event_list[j].valid:
                    #logger.info('VALID INDEX = ' + str(j))
                    break
            self.wait_ack_event_list[j].valid = True
            self.wait_ack_event_list[j].identify = identify
            self.wait_ack_event_list[j].wait_ack_event.clear()
            self.send_msg(event_msg)
            # TODO EVENT_AUTOCLEAR need event.wait with flag
            self.wait_ack_event_list[j].wait_ack_event.wait(time_out)
            if not self.wait_ack_event_list[j].wait_ack_event.isSet():
                duss_result = rm_define.DUSS_ERR_TIMEOUT
                logger.warn('CMDSET = ' + str(event_msg.cmd_set) + ', CMDID = ' + str(hex(event_msg.cmd_id)) + ' TIMEOUT')
            self.wait_ack_event_list[j].valid = False
            resp = self.ack_unregister_identify(identify)
            return duss_result, resp
        else:
            self.send_msg(event_msg)
            return duss_result, None

    # return duss_result
    def send_task_async(self, event_msg, event_task, time_out = duml_cmdset.MSG_DEFAULT_TIMEOUT):
        identify = str(event_task['cmd_set']) + str(event_task['cmd_id']) + str(event_task['task_id'])
        duss_result, resp = self.send_sync(event_msg, time_out)
        # TODO process duss_result
        if duss_result != rm_define.DUSS_SUCCESS:
            logger.error('EVENT: send task %s, error code = %d' % (identify, duss_result))
            return duss_result, identify

        if resp['data'][0] == duml_cmdset.DUSS_MB_RET_OK:
            if resp['data'][1] == 0:
                logger.info('TASK ID = ' + str(event_task['task_id']) + ' ACCEPTED')
                duss_result = rm_define.DUSS_SUCCESS
            elif resp['data'][1] == 1:
                logger.info('TASK ID = ' + str(event_task['task_id']) + ' REJECTED')
                duss_result = rm_define.DUSS_TASK_REJECTED
            elif resp['data'][1] == 2:
                logger.info('TASK ID = ' + str(event_task['task_id']) + ' ALREADY FINISH.')
                self.already_finish_task_identify_set.add(identify)
                duss_result = rm_define.DUSS_TASK_FINISHED
            else:
                logger.warn('UNSUPPORT TASK RESULT')
                duss_result = rm_define.DUSS_ERR_FAILURE
        else:
            logger.error('RETURN CODE ERROR')
            duss_result = rm_define.DUSS_ERR_FAILURE
        return duss_result, identify

    def task_sync_process(self, identify, task_push_msg):
        task_push_msg['task_id'] = task_push_msg['data'][0]
        task_push_msg['result'] = task_push_msg['data'][2] & 0x3
        task_push_msg['percent'] = task_push_msg['data'][1]
        self.script_state.set_block_running_percent(task_push_msg['percent'])
        logger.info('TASK ID = ' + str(task_push_msg['task_id']) + ', ' + str(task_push_msg['percent']) + '% STATE = ' + str(task_push_msg['result']))

        self.event_watchdog_set(identify)

        if task_push_msg['result'] != 0:
            logger.info('TASK ID = ' + str(task_push_msg['task_id']) + ' FINISHED.')
            if identify in self.already_finish_task_identify_set:
                self.already_finish_task_identify_set.remove(identify)
            else:
                self.event_notify(identify)

    def send_task_stop(self, event_msg, time_out = duml_cmdset.MSG_DEFAULT_TIMEOUT):
        duss_result, resp = self.send_sync(event_msg, time_out)
        return duss_result

    def send_async(self):
        # TODO
        pass

    def resp_ok(self, msg):
        self.resp_retcode(msg, duml_cmdset.DUSS_MB_RET_OK)

    def resp_retcode(self, msg, retcode):
        event_msg = duss_event_msg.unpack2EventMsg(msg)
        event_msg.clear()
        event_msg.append('ret_code', 'uint8', retcode)
        event_msg.sender, event_msg.receiver = event_msg.receiver, event_msg.sender
        event_msg.cmd_type = duml_cmdset.ACK_PKG_TYPE
        self.send_msg(event_msg)

    def resp_event_msg(self, event_msg):
        event_msg.sender, event_msg.receiver = event_msg.receiver, event_msg.sender
        event_msg.cmd_type = duml_cmdset.ACK_PKG_TYPE
        self.send_msg(event_msg)

    def event_msg_invalid_check_callback_register(self, callback):
        self.check_event_msg_invalid_callback = callback

    def event_msg_invalid_check_callback_unregister(self):
        self.check_event_msg_invalid_callback = None

    def check_event_msg_invalid(self, event_msg):
        if callable(self.check_event_msg_invalid_callback):
            return self.check_event_msg_invalid_callback(event_msg)
        else:
            return False, None
