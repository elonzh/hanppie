import socket
import threading
import time
import rm_log
import select
import queue
import subprocess

logger = rm_log.dji_scratch_logger_get()

STREAM_PORT = 40921
CTRL_PORT = 40923
PUSH_PORT = 40924
EVENT_PORT = 40925
BROADCAST_PORT = 40926
INADDR_ANY = '0.0.0.0'
WIFI_DIRECT_CONNECTION_IP = '192.168.2.1'

class Connection(object):
    def __init__(self):
        self.remote_host_ip = INADDR_ANY

        self.ctrl_socket = None
        self.push_socket = None
        self.event_socket = None
        self.broadcast_socket = None

        self.server_action_callback_dict = {}

        self.ctrl_recv_callback = None
        self.ctrl_exit_callback = None
        self.ctrl_connect_callback = None
        self.ctrl_disconnect_callback = None

        self.event_recv_callback = None
        self.event_exit_callback = None

        self.recv_thread = None
        self.recv_thread_finish = False

        self.TCP_server_client_dict = {}
        self.UDP_server_client_dict = {}

        self.local_host_ip = WIFI_DIRECT_CONNECTION_IP

        self.msg_queue = {}

    def init(self):
        self.ctrl_info_socket_init()
        self.event_info_socket_init()
        self.push_info_socket_init()
        self.broadcast_info_socket_init()

        self.recv_thread = threading.Thread(target=self.__recv_task)
        self.recv_thread_finish = False
        self.recv_thread.start()

    def exit(self):
        self.recv_thread_finish = True
        self.recv_thread.join()

    def ctrl_info_socket_init(self):
        self.ctrl_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.ctrl_socket.bind((INADDR_ANY, CTRL_PORT))
        self.ctrl_socket.listen()

    def event_info_socket_init(self):
        self.event_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.event_socket.bind((INADDR_ANY, EVENT_PORT))
        self.event_socket.listen()

    def push_info_socket_init(self):
        self.push_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    def broadcast_info_socket_init(self):
        self.broadcast_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.broadcast_socket.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.broadcast_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    def update_local_host_ip(self):
        ifconfig_pipe = subprocess.Popen(['busybox', 'ifconfig', 'wlan0'],
                        stdout = subprocess.PIPE,
                        stderr = subprocess.PIPE,
                        )
        ifconfig_info, error = ifconfig_pipe.communicate()
        ifconfig_pipe.kill()

        if len(error) != 0:
            #get wlan0 error
            return None

        ifconfig_info = ifconfig_info.decode('utf-8')

        inet_addr_str = ifconfig_info.split('\n')[1]

        if 'inet addr' in inet_addr_str:
            self.local_host_ip = inet_addr_str.split(':')[1].split(' ')[0]

        return self.local_host_ip

    def ctrl_send(self, data):
        #send msg to all client by default
        if self.ctrl_socket in self.TCP_server_client_dict.keys():
            for client in self.TCP_server_client_dict[self.ctrl_socket]:
                try:
                    client.send(data.encode('utf-8'))
                except ConnectionResetError as e:
                    #clear
                    pass

    def ctrl_ack(self, client, data):
        #send msg to target client by default
        try:
            client.send(data.encode('utf-8'))
        except ConnectionResetError as e:
            logger.fatal('Connection lost')

    def event_send(self, data):
        #send msg to all client by default
        if self.event_socket in self.TCP_server_client_dict.keys():
            for client in self.TCP_server_client_dict[self.event_socket]:
                try:
                    client.send(data.encode('utf-8'))
                except ConnectionResetError as e:
                    pass

    def event_ack(self, data):
        #send msg to target client by default
        try:
            client.send(data.encode('utf-8'))
        except ConnectionResetError as e:
            logger.fatal('Connection lost')

    def ctrl_connect_register(self, cb):
        if self.ctrl_connect_callback:
            logger.info('ctrl recv callback has already been set, will be overwrite, last callback %s'%(self.ctrl_connect_callback))
        if callable(cb):
            self.ctrl_connect_callback = cb

    def ctrl_disconnect_register(self, cb):
        if self.ctrl_disconnect_callback:
            logger.info('ctrl recv callback has already been set, will be overwrite, last callback %s'%(self.ctrl_disconnect_callback))
        if callable(cb):
            self.ctrl_disconnect_callback = cb

    def ctrl_recv_register(self, cb):
        if self.ctrl_recv_callback:
            logger.info('ctrl recv callback has already been set, will be overwrite, last callback %s'%(self.ctrl_recv_callback))
        if callable(cb):
            self.ctrl_recv_callback = cb

    def ctrl_exit_register(self, cb):
        if self.ctrl_exit_callback:
            logger.info('ctrl exit callback has already been set, will be overwrite, last callback %s'%(self.ctrl_exit_callback))
        if callable(cb):
            self.ctrl_exit_callback = cb

    def ctrl_msg_put(self, msg):
        try:
            self.msg_queue[self.ctrl_socket].put_nowait()
        except queue.Full:
            logger.fatal("Queue full")

    def event_msg_put(self, msg):
        try:
            self.msg_queue[self.event_socket].put_nowait()
        except queue.Full:
            logger.fatal("Queue full")

    def push_msg_put(self, msg):
        try:
            self.msg_queue[self.push_socket].put_nowait((msg, (remote_host_ip, PUSH_PORT)))
        except queue.Full:
            logger.fatal("Queue full")

    def broadcast_msg_put(self, msg):
        try:
            self.msg_queue[self.broadcast_socket].put_nowait((msg, ('<broadcast>', BROADCAST_PORT)))
        except queue.Full:
            logger.fatal("Queue full")

    def set_remote_host_ip(self, ip):
        self.remote_host_ip = ip

    def report_local_host_ip(self):
        self.update_local_host_ip()
        self.broadcast_msg_put('robot ip %s'%self.local_host_ip)

    def __recv_task(self):
        # event and ctrl connection use TCP connection
        all_client_list = []

        epoll_obj = select.epoll()

        if self.ctrl_socket:
            epoll_obj.register(self.ctrl_socket.fileno(), select.EPOLLIN)
            self.TCP_server_client_dict[self.ctrl_socket] = []
            self.server_action_callback_dict[self.ctrl_socket] = {
                    'connect':self.ctrl_connect_callback,
                    'disconnect':self.ctrl_disconnect_callback,
                    'recv':self.ctrl_recv_callback,
                    'exit':self.ctrl_exit_callback
            }
            self.msg_queue[self.ctrl_socket] = queue.Queue(8)

        if self.event_socket:
            epoll_obj.register(self.event_socket.fileno(), select.EPOLLIN)
            self.TCP_server_client_dict[self.event_socket] = []
            self.server_action_callback_dict[self.event_socket] = {
                    'recv':self.event_recv_callback,
                    'exit':self.event_exit_callback
            }
            self.msg_queue[self.event_socket] = queue.Queue(8)

        if self.push_socket:
            epoll_obj.register(self.push_socket.fileno(), select.EPOLLIN)
            self.UDP_server_client_dict[self.push_socket] = []
            self.msg_queue[self.push_socket] = queue.Queue(128)

        if self.broadcast_socket:
            epoll_obj.register(self.broadcast_socket.fileno(), select.EPOLLIN)
            self.UDP_server_client_dict[self.broadcast_socket] = []
            self.msg_queue[self.broadcast_socket] = queue.Queue(8)

        fd_to_socket = {
                self.ctrl_socket.fileno():self.ctrl_socket,
                self.event_socket.fileno():self.event_socket,
                self.push_socket.fileno():self.push_socket,
                self.broadcast_socket.fileno():self.broadcast_socket
        }

        timeout = 2
        while not self.recv_thread_finish:

            for socket_obj in self.UDP_server_client_dict.keys():
                if self.msg_queue[socket_obj].qsize() > 0:
                    epoll_obj.modify(socket_obj.fileno(), select.EPOLLOUT)

            events = epoll_obj.poll(timeout)

            if not events:
                logger.info('epoll wait connection timeout')
                continue

            for fd, event in events:
                socket_obj = fd_to_socket[fd]

                if socket_obj  in self.TCP_server_client_dict.keys():
                    conn, addr = socket_obj.accept()

                    # Support one client in a server now
                    if len(self.TCP_server_client_dict[socket_obj]) >= 1:
                        continue

                    if 'connect' in self.server_action_callback_dict[socket_obj].keys():
                        self.server_action_callback_dict[socket_obj]['connect'](conn.getsockname())

                    conn.setblocking(False)
                    epoll_obj.register(conn.fileno(), select.EPOLLIN)
                    fd_to_socket[conn.fileno()] = conn

                    self.TCP_server_client_dict[socket_obj].append(conn)
                    all_client_list.append(conn)

                    logger.info('New tcp conncection %s'%str(conn))

                elif socket_obj in all_client_list:
                    if event & select.EPOLLHUP:
                        logger.info('client close')

                        epoll_obj.unregister(fd)
                        del fd_to_socket[fd]
                        all_client_list.remove(socket_obj)

                        for (server, client_list) in self.TCP_server_client_dict.items():
                            if socket_obj in client_list:
                                if len(client_list) == 1:
                                    if 'exit' in self.server_action_callback_dict[server].keys() and self.server_action_callback_dict[server]['exit']:
                                        self.server_action_callback_dict[server]['exit'](None)
                                self.TCP_server_client_dict[server].remove(socket_obj)

                    elif event & select.EPOLLIN:
                        recv_buff = socket_obj.recv(2048)
                        if recv_buff:
                            for (server, client_list) in self.TCP_server_client_dict.items():
                                if socket_obj in client_list:
                                    # process data
                                    if 'recv' in self.server_action_callback_dict[server].keys() and self.server_action_callback_dict[server]['recv']:
                                        self.server_action_callback_dict[server]['recv'](recv_buff.decode('utf-8'), socket_obj)
                        else:
                            epoll_obj.unregister(fd)
                            del fd_to_socket[fd]
                            all_client_list.remove(socket_obj)

                            for (server, client_list) in self.TCP_server_client_dict.items():
                                if socket_obj in client_list:
                                    if len(client_list) == 1:
                                        if 'exit' in self.server_action_callback_dict[server].keys() and self.server_action_callback_dict[server]['exit']:
                                            self.server_action_callback_dict[server]['exit'](None)

                                    self.TCP_server_client_dict[server].remove(socket_obj)

                            logger.fatal('socket recv buff length error, disconnect')
                    elif event & select.EOPLLOUT:
                        try:
                            msg = self.msg_queue[socket_obj].get_nowait()
                        except queue.Empty:
                            logger.fatal('Queue empty')
                elif socket_obj in self.UDP_server_client_dict.keys():
                    if event & select.EPOLLOUT:
                        try:
                            msg, addr = self.msg_queue[socket_obj].get_nowait()
                        except queue.Empty:
                            logger.fatal('Queue empty')
                            continue
                        try:
                            logger.info(msg)
                            socket_obj.sendto(msg.encode('utf-8'), addr)
                        except:
                            pass
                        epoll_obj.modify(fd, 0)
                else:
                    logger.fatal('EPOLL ERROR, CONNECTION NOT RECORDER AND PROCESSER')
        logger.info('recv task exit')
