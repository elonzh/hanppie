import connection
import queue
import threading
import time
import json
import traceback

import event_client
import rm_ctrl
import rm_define
import rm_log
import tools

logger = rm_log.dji_scratch_logger_get()

PROTOCAL_MAPPING_TABLE_PATH = '/data/dji_scratch/src/robomaster/plaintext_sdk/protocal_mapping_table.json'

class ProtocalParser(object):
    def __init__(self, event_dji_system):

        self.event_client = event_dji_system
        self.sdk_ctrl = rm_ctrl.SDKCtrl(event_dji_system)

        self.connection_obj = connection.Connection()
        self.data_queue = queue.Queue(512)
        self.event_info_queue = queue.Queue(16)
        self.push_info_queue = queue.Queue(512)

        # make command exec order
        # if there is command has been execed
        # will return error when user send command
        # support 'command1; command2;' to order run many commands
        self.command_execing_event = threading.Event()

        self.command_parser_callback = {
            'command':self.command_protocal_format_parser,
            'quit':self.quit_protocal_format_parser,
        }

        self.data_process_thread = None
        self.push_info_thread = None
        self.event_info_thread = None

        self.protocal_mapping_table = None

        self.sdk_mode = False

        self.ctrl_obj = {}

        self.report_local_host_ip_timer = None

    def init(self):

        f = open(PROTOCAL_MAPPING_TABLE_PATH, 'r')
        self.protocal_mapping_table = json.load(f)
        f.close()

        self.connection_obj.ctrl_connect_register(self.__connect_callback)
        self.connection_obj.ctrl_recv_register(self.__data_recv_callback)
        self.connection_obj.ctrl_exit_register(self.__disconnect_callback)
        self.connection_obj.init()

        self.ctrl_obj = {}

        if self.report_local_host_ip_timer == None:
            self.report_local_host_ip_timer = tools.get_timer(2, self.connection_obj.report_local_host_ip)
            self.report_local_host_ip_timer.start()

    def __connect_callback(self, data):
        self.connection_status_report('connected', data)

    def __data_recv_callback(self, data, socket_obj):
        self.protocal_parser(data, socket_obj)

    def __disconnect_callback(self, data):
        self.quit_protocal_format_parser()
        self.connection_status_report('disconnected', data)

    def command_execing_start(self):
        self.command_execing_event.set()

    def command_execing_is_finish(self):
        self.command_execing_event.is_set()

    def command_execing_finish(self):
        self.command_execing_event.clear()

    def sdk_robot_ctrl(self, ctrl):
        def init():
            self.ctrl_obj['event'] = event_client.EventClient()
            self.ctrl_obj['modulesStatus_ctrl'] = rm_ctrl.ModulesStatusCtrl(self.ctrl_obj['event'])
            self.ctrl_obj['blaster_ctrl'] = rm_ctrl.GunCtrl(self.ctrl_obj['event'])
            self.ctrl_obj['armor_ctrl'] = rm_ctrl.ArmorCtrl(self.ctrl_obj['event'])
            self.ctrl_obj['AI_ctrl'] = rm_ctrl.VisionCtrl(self.ctrl_obj['event'])
            self.ctrl_obj['chassis_ctrl'] = rm_ctrl.ChassisCtrl(self.ctrl_obj['event'])
            self.ctrl_obj['gimbal_ctrl'] = rm_ctrl.GimbalCtrl(self.ctrl_obj['event'])
            self.ctrl_obj['robot_ctrl'] = rm_ctrl.RobotCtrl(self.ctrl_obj['event'], self.ctrl_obj['chassis_ctrl'], self.ctrl_obj['gimbal_ctrl'])
            self.ctrl_obj['led_ctrl'] = rm_ctrl.LedCtrl(self.ctrl_obj['event'])
            self.ctrl_obj['media_ctrl'] = rm_ctrl.MediaCtrl(self.ctrl_obj['event'])
            self.ctrl_obj['mobile_ctrl'] = rm_ctrl.MobileCtrl(self.ctrl_obj['event'])
            self.ctrl_obj['tools'] = rm_ctrl.RobotTools(self.ctrl_obj['event'])
            self.ctrl_obj['sdk_ctrl'] = rm_ctrl.SDKCtrl(self.ctrl_obj['event'])
            #log_ctrl = rm_ctrl.LogCtrl(event)

        def ready():
            self.ctrl_obj['robot_ctrl'].init()
            self.ctrl_obj['modulesStatus_ctrl'].init()
            self.ctrl_obj['gimbal_ctrl'].init()
            self.ctrl_obj['chassis_ctrl'].init()
            self.ctrl_obj['led_ctrl'].init()
            self.ctrl_obj['blaster_ctrl'].init()
            self.ctrl_obj['mobile_ctrl'].init()
            self.ctrl_obj['tools'].init()

            self.ctrl_obj['robot_ctrl'].enable_sdk_mode()
            self.ctrl_obj['robot_ctrl'].set_mode(rm_define.robot_mode_gimbal_follow)
            self.ctrl_obj['tools'].program_timer_start()

            self.ctrl_obj['armor_ctrl'].sdk_event_push_callback_register(self.armor_hit_detection_event_callback)
            self.ctrl_obj['media_ctrl'].sdk_applause_event_push_callback_register(self.applause_detection_event_callback)

        def stop():
            self.ctrl_obj['blaster_ctrl'].stop()
            self.ctrl_obj['chassis_ctrl'].stop()
            self.ctrl_obj['gimbal_ctrl'].stop()
            self.ctrl_obj['media_ctrl'].stop()
            self.ctrl_obj['AI_ctrl'].stop()
            self.ctrl_obj['armor_ctrl'].stop()

        def exit():
            stop()
            self.ctrl_obj['robot_ctrl'].disable_sdk_mode()
            self.ctrl_obj['robot_ctrl'].exit()
            self.ctrl_obj['gimbal_ctrl'].exit()
            self.ctrl_obj['chassis_ctrl'].exit()
            self.ctrl_obj['blaster_ctrl'].exit()
            self.ctrl_obj['mobile_ctrl'].exit()
            self.ctrl_obj['armor_ctrl'].exit()
            self.ctrl_obj['media_ctrl'].exit()
            self.ctrl_obj['sdk_ctrl'].exit()
            self.ctrl_obj['event'].stop()
            self.ctrl_obj.clear()

        if ctrl == 'init':
            init()
        elif ctrl == 'ready':
            ready()
        elif ctrl == 'stop':
            stop()
        elif ctrl == 'exit':
            exit()

    def __data_process(self):

        self.sdk_robot_ctrl('init')
        self.sdk_robot_ctrl('ready')

        while self.sdk_mode:
            result = False
            if not self.data_queue.empty():
                socket_obj, data = self.data_queue.get()
                self.command_execing_start()
                if data.req_type == 'set':
                    cmd = str(data.obj) + '.' + str(data.function) + str(data.param)

                    logger.info(cmd)

                    try:
                        result = eval(cmd, self.ctrl_obj)
                    except Exception as e:
                        logger.fatal(traceback.format_exc())
                        self.ctrl_ack(socket_obj, 'fail', data.seq)
                        continue
                    if (type(result) == tuple and result[-1] == 0) or (type(result) == bool and result == True) or result == None or result == 0:
                        self.ctrl_ack(socket_obj, 'ok', data.seq)
                    else:
                        self.ctrl_ack(socket_obj, 'fail', data.seq)
                    logger.fatal('process : ' + str(data.obj) + '.' + str(data.function) + str(data.param) + ' exec_result:' + str(result))
                elif data.req_type == 'get':
                    if data.param == None:
                        cmd = str(data.obj) + '.' + str(data.function) + '()'
                    else:
                        cmd = str(data.obj) + '.' + str(data.function) + str(data.param)

                    logger.info(cmd)

                    try:
                        result = eval(cmd, self.ctrl_obj)
                    except Exception as e:
                        logger.fatal(traceback.format_exc())
                        self.ctrl_ack(socket_obj, 'fail', data.seq)
                    seq = data.seq
                    data = ''
                    if type(result) == tuple or type(result) == list:
                        for i in result:
                            data = data + str(i) + ' '
                    else:
                        data = str(result) + ' '
                    self.ctrl_ack(socket_obj, data, seq)
                time.sleep(1)
            else:
                self.command_execing_finish()
                time.sleep(0.1)

        self.sdk_robot_ctrl('exit')

    def protocal_parser(self, data, socket_obj):
        #command
        logger.info('Recv string: %s'%(data))
        command_gather = data.split(';')
        for command_string in command_gather:
            command = command_string.split(' ')

            if len(command) == 0:
                continue

            # find 'seq'
            seq = None
            if 'seq' in command:
                seq_pos = command.index('seq')
                if len(command) > seq_pos+1:
                    seq = command[seq_pos+1]
                    if seq.isdigit():
                        seq = int(seq)
                    else:
                        self.ctrl_ack(socket_obj, 'command format error')
                else:
                    self.ctrl_ack(socket_obj, 'command format error')

            if self.command_execing_is_finish():
                self.ctrl_ack(socket_obj, 'error', seq)
                return False

            # check protocal format
            command_obj = command[0]

            # call process function
            if command_obj in self.command_parser_callback.keys():
                result = self.command_parser_callback[command_obj]()
                if result == False or result == None:
                    self.ctrl_ack(socket_obj, '%s error'%command_obj, seq)
                else:
                    self.ctrl_ack(socket_obj, 'ok', seq)
            else:
                if not self.sdk_mode:
                    self.ctrl_ack(socket_obj, 'not in sdk mode', seq)
                    return False
                result = self.ctrl_protocal_format_parser(command, seq)
                if result == False or result == None:
                    self.ctrl_ack(socket_obj, 'command format error', seq)
                else:
                    if not self.data_queue.full():
                        try:
                            self.data_queue.put_nowait((socket_obj, result))
                        except Exception as e:
                            # full ?
                            logger.fatal(e)

    def command_protocal_format_parser(self):
        if self.sdk_mode == False:
            self.sdk_mode = True
            if self.data_process_thread == None or self.data_process_thread.is_alive() == False:
                self.data_process_thread = threading.Thread(target=self.__data_process)
                self.data_process_thread.start()

            if self.event_info_thread == None or self.event_info_thread.is_alive() == False:
                self.event_info_thread = threading.Thread(target=self.__event_info_push)
                self.event_info_thread.start()

            if self.report_local_host_ip_timer and self.report_local_host_ip_timer.is_start():
                self.report_local_host_ip_timer.join()
                self.report_local_host_ip_timer.stop()

            return True
        else:
            return False

    def quit_protocal_format_parser(self):
        if self.data_process_thread and self.data_process_thread.is_alive():
            if self.report_local_host_ip_timer == None:
                self.report_local_host_ip_timer = tools.get_timer(2, self.connection_obj.report_local_host_ip)
                self.report_local_host_ip_timer.start()
            else:
                self.report_local_host_ip_timer.start()
            self.sdk_mode = False
            self.data_process_thread.join()
            return True
        else:
            return False

    def ctrl_protocal_format_parser(self, command, seq):
        cmdpkg = CommandPackage()
        cmdpkg.seq = seq

        try:
            obj = command[0]
            if obj in self.protocal_mapping_table.keys():
                cmdpkg.obj = self.protocal_mapping_table[obj]['obj']
            else:
                return False

            function = command[1]
            if function in self.protocal_mapping_table[obj]['functions'].keys():
                function_dict = self.protocal_mapping_table[obj]['functions'][function]

                if '?' in command:
                    cmdpkg.function = function_dict['get'][0]
                    params_list = command[2:]
                    if len(function_dict['get'][1:]) != 0 and len(params_list) != 0:
                        cmdpkg.param = tuple(params_list[0:len(function_dict['get'][1:])])

                    logger.fatal(cmdpkg.param)

                    cmdpkg.req_type = 'get'
                else:
                    params_list = command[2:]
                    cmdpkg.function = function_dict['set'][0]
                    cmdpkg.req_type = 'set'
                    params = []

                    for param in function_dict['set'][1:]:
                        if len(function_dict['set'][1:]) == 1:
                            value = params_list[0]
                            if value.isdigit():
                                value = int(value)
                            else:
                                try:
                                    value = float(value)
                                except Exception as e:
                                    pass
                            params.append(value)
                            break

                        if param in params_list and params_list.index(param) + 1 < len(params_list):
                            value = params_list[params_list.index(param)+1]
                            if value.isdigit():
                                value = int(value)
                            else:
                                try:
                                    value = float(value)
                                except Exception as e:
                                    pass
                            params.append(value)
                        else:
                            params.append(None)

                    cmdpkg.param = tuple(params)
            else:
                return False
        except Exception as e:
            logger.fatal(traceback.format_exc())
            return False

        return cmdpkg

    def connection_status_report(self, status, data):
        logger.info('connect status changed, cur ip info: %s, cur status: %s'%(data, status))
        mode = 'wifi'
        if data != None:
            ip = data[0]
            if ip ==  tools.get_ip_by_dev_name('wlan0'):
                mode = 'wifi'
            elif ip ==  tools.get_ip_by_dev_name('rndis0'):
                mode = 'rndis'
            logger.info('connect mode: %s'%(mode))

        if status == 'connected':
            self.sdk_ctrl.sdk_on(mode)
        elif status == 'disconnected':
            self.sdk_ctrl.sdk_off()

    def armor_hit_detection_event_callback(self, index, type):
        msg ='armor hit %d %d'%(index,type)
        if not self.event_info_queue.full():
            self.event_info_queue.put(msg)

    def applause_detection_event_callback(self, data):
        msg = 'sound applause %d'%(data)
        if not self.event_info_queue.full():
            self.event_info_queue.put(msg)

    def __event_info_push(self):
        while self.sdk_mode:
            if not self.event_info_queue.empty():
                msg = self.event_info_queue.get()
                self.connection_obj.event_send(msg)
            elif not self.push_info_queue.empty():
                msg = self.push_info_queue.get()
                self.connection_obj.push_send(msg)

    def ctrl_send(self, data):
        self.connection_obj.ctrl_send(data)

    def ctrl_ack(self, client, data, seq):
        msg = data
        if seq != None:
            msg = msg + ' seq %s'%str(seq)
        self.connection_obj.ctrl_ack(client, msg)

    def event_send(self, data):
        self.connection_obj.event_send(data)

    def event_ack(self, client, data, seq):
        msg = data
        if seq != None:
            msg = msg + ' seq %s'%str(seq)
        self.connection_obj.event_ack(client, data)

class CommandPackage(object):
    def __init__(self):
        self.obj = None
        self.function = None
        self.param = None
        self.seq = None
        self.req_type = None
