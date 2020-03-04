import tools
import rm_define
import duml_cmdset

logger = None

def creata_logger(event_cilent):
    global logger
    logger = Logger(event_client)
    return logger

def get_logger():
    return logger

class Logger(object):
    DEBUG_LEVEL = 0
    INFO_LEVEL = 2
    WARNING_LEVEL = 3
    ERROR_LEVEL = 4
    FATAL_LEVEL = 5
    def __init__(self, event_client):
        import duss_event_msg
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(tools.hostid2senderid(event_client.my_host_id))
        self.msg_buff.set_default_receiver(rm_define.blackbox_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_COMMON)
        self.print_level = Logger.INFO_LEVEL
        self.save_level = Logger.ERROR_LEVEL

        self.msg_buff_list = []
        self.append_msg_buff_length = 100

        self.save_msg_enable = True
        self.print_msg_enable = True

        self.process_timer = tools.get_timer(0.5, self._msg_send_timer)
        self.process_timer.start()

    def _send_msg(self, msg):
        self.msg_buff.init()
        self.msg_buff.append('log_level', 'uint8', 0)
        self.msg_buff.append('msg', 'string', msg)
        self.msg_buff.cmd_id = 0xf0
        self.event_client.send_msg(self.msg_buff)

    def _msg_send_timer(self, *arg, **kw):
        if len(self.msg_buff_list) > 0:
            msg_list = self.msg_buff_list.copy()
            self.msg_buff_list.clear()
            for msg in msg_list:
                while len(msg) != 0:
                    send_msg = msg[:900]
                    msg = msg[900:]
                    self._send_msg(send_msg)
                tools.wait(5)

    def print_msg(self, msg):
        if self.print_msg_enable:
            print(msg)

    def save_msg(self, msg):
        if self.save_msg_enable:
            if len(self.msg_buff_list) >= self.append_msg_buff_length:
                self.msg_buff_list[-1] = msg
            else:
                self.msg_buff_list.append(msg)

    def append_msg_buff_list(self, msg_args, level=None):
        msg_string = ''
        for var in msg_args:
            msg_string += str(var) + ''
        if level:
            if level >= self.save_level:
                self.save_msg(msg_string[0:-1])
            if level >= self.print_level:
                self.print_msg(msg_string[0:-1])
        else:
            self.save_msg(msg_string)

    def append_msg_buff_length_set(self, length):
        self.append_msg_buff_length = length

    def print_level_set(self, level):
        if  level >= Logger.DEBUG_LEVEL and level <= Logger.FATAL_LEVEL:
            self.print_level = level

    def save_level_set(self, level):
        if  level >= Logger.DEBUG_LEVEL and level <= Logger.FATAL_LEVEL:
            self.save_level = level

    def enable_save_stream(self):
        self.send_msg_enable = True

    def disable_save_stream(self):
        self.send_msg_enable = False

    def enable_print_stream(self):
        self.print_msg_enable = True

    def disable_print_stream(self):
        self.print_msg_enable = False

    def add_msg(self, *msg_args):
        self.append_msg_buff_list(msg_args)

    def debug(self, *msg_args, level):
        self.append_msg_buff_list(msg_args, Logger.DEBUG_LEVEL)

    def info(self, *msg_args):
        self.append_msg_buff_list(msg_args, Logger.INFO_LEVEL)

    def warning(self, *msg_args):
        self.append_msg_buff_list(msg_args, Logger.WARNING_LEVEL)

    def error(self, *msg_args):
        self.append_msg_buff_list(msg_args, Logger.ERROR_LEVEL)

    def fatal(self, *msg_args):
        self.append_msg_buff_list(msg_args, Logger.FATAL_LEVEL)

class BlackboxStream(object):
    def __init__(self, event_client):
        self.logger = Logger(event_client)

    def open(self):
        pass

    def close(self):
        pass

    def write(self, msg_string):
        self.logger.add_msg(msg_string)

    def read(self):
        pas

    def flush(self):
        pass
