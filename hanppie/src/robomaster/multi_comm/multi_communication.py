import queue
import rm_log
import threading

logger = rm_log.dji_scratch_logger_get()


class MultiCommunication(object):
    PORT = 40930

    def __init__(self, event_client, socket):
        self.event_client = event_client
        self.socket = socket

        self.user_fd = -1
        self.send_group = 0
        self.recv_group = ()
        self.recv_callback = None

        self.recv_msg_queue = queue.Queue(16)
        self.__recv_callback_process_thread = None
        self.__recv_callback_process_finish = True

    def init(self):
        logger.info('MULTI_COMM INIT')
        self.user_fd = self.socket.create(
            self.socket.UDP_MODE,
            ('', self.PORT),
            server=True,
            recv_callback=self.__recv_msg_from_socket,
        )
        if self.user_fd == -1:
            logger.info('create socket error')

        self.socket.set_udp_default_target_addr(self.user_fd, ('<broadcast>', self.PORT))

    # ir / wifi
    def set_mode(self, mode):
        pass

    # support multi group, default: boardcast
    def set_group(self, send_group, recv_group=()):
        self.send_group = send_group
        if recv_group == ():
            self.recv_group = (send_group,)
        elif type(recv_group) == tuple or type(recv_group) == list:
            self.recv_group = tuple(recv_group)
        elif type(recv_group) == int:
            self.recv_group = (recv_group,)
        else:
            logger.error('SET GROUP ERROR, value is %s' % (str(self.recv_group)))

    # broadcast or group
    def send_msg(self, msg, group=None):
        if group == None:
            group = self.send_group
        msg = 'SENDER_GROUP:%s MSG:%s' % (str(group), str(msg))
        self.socket.send(self.user_fd, msg)

    # recv msg
    def recv_msg(self, timeout=None):
        timeout_t = 0.2
        if timeout == None or timeout <= 0:
            timeout = 7200
        count_t = timeout / 0.2
        if self.recv_callback == None:
            while count_t >= 0 and not self.event_client.script_state.check_stop():
                try:
                    msg = self.recv_msg_queue.get(timeout=timeout_t)
                    return msg
                except queue.Empty:
                    count_t -= 1
                    continue
            logger.info('timeout')
        return None

    # callback function(group, msg)
    def register_recv_callback(self, callback):
        if callable(callback):
            self.recv_callback = callback
            if self.__recv_callback_process_thread == None:
                self.__recv_callback_process_finish = False
                self.__recv_callback_process_thread = threading.Thread(target=self.__recv_callback_process)
                self.__recv_callback_process_thread.start()

    def exit(self):
        logger.info('MULTI_COMM EXIT')
        if not self.__recv_callback_process_finish:
            self.__recv_callback_process_finish = True
            self.recv_msg_queue.put('eixt')
            self.recv_msg_queue.put('eixt')
            self.__recv_callback_process_thread.join()
        self.socket.close(self.user_fd)

    def __recv_callback_process(self):
        while not self.__recv_callback_process_finish and not self.event_client.script_state.check_stop():
            try:
                msg = self.recv_msg_queue.get()
                if self.__recv_callback_process_finish or self.event_client.script_state.check_stop():
                    break
                if self.recv_callback:
                    self.recv_callback(msg)
            except queue.Empty:
                continue

    def __recv_msg_from_socket(self, recv_addr, user_id, msg):
        if msg.find('SENDER_GROUP:') != -1 and msg.find(' ') != -1:
            group = msg[msg.find('SENDER_GROUP:') + len('SENDER_GROUP:'):msg.find(' ')]
            msg = msg[msg.find('MSG:') + len('MSG:'):]

            if not group.isdigit():
                return

            group = int(group)

            if group not in self.recv_group:
                return

            if self.recv_msg_queue.full():
                self.recv_msg_queue.get()
            self.recv_msg_queue.put((group, msg))
