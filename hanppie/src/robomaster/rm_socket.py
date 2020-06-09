import traceback

import errno
import queue
import rm_log
import select
import socket
import subprocess
import threading

logger = rm_log.dji_scratch_logger_get()


class RmSocket(object):
    TCP_MODE = 'tcp'
    UDP_MODE = 'udp'

    def __init__(self):
        self.user_fd_to_socket_fd = {}
        self.socket_fileno_info = {}

        # self.recv_msg_queue = queue.Queue(128)
        self.send_msg_queue = queue.Queue(128)

        self.user_fd = 0

        self.epoll_obj = select.epoll()

        self.recv_thread_finish = True
        self.recv_thread = None

    def init(self):
        logger.info('SOCKET INIT')
        self.recv_thread = threading.Thread(target=self.__epoll_task)
        self.recv_thread_finish = False
        self.recv_thread.start()

    def exit(self):
        logger.info('RM SOCKET EXIT')
        self.recv_thread_finish = True
        for socket_fileno in self.socket_fileno_info.keys():
            self.socket_fileno_info[socket_fileno]['socket'].close()
        self.socket_fileno_info = {}
        self.user_fd_to_socket_fd = {}
        self.recv_thread.join()

    def close(self, user_fd):
        logger.info('SHUWDOWN %d' % (user_fd))
        if user_fd in self.user_fd_to_socket_fd.keys():
            self.__remove_socket_fileno_info(self.user_fd_to_socket_fd[user_fd])

    def create(self, mode, ip_port, server=True, recv_msgq_size=16, send_msgq_size=16, **callback):
        if mode == RmSocket.TCP_MODE:
            if server:
                return self.__create_tcp_server(ip_port, recv_msgq_size=recv_msgq_size, send_msgq_size=send_msgq_size,
                                                **callback)
            else:
                return self.__create_tcp_client(ip_port, recv_msgq_size=recv_msgq_size, send_msgq_size=send_msgq_size,
                                                **callback)
        elif mode == RmSocket.UDP_MODE:
            if server:
                return self.__create_udp_server(ip_port, recv_msgq_size=recv_msgq_size, send_msgq_size=send_msgq_size,
                                                **callback)
            else:
                return self.__create_udp_client(ip_port, recv_msgq_size=recv_msgq_size, send_msgq_size=send_msgq_size,
                                                **callback)
        else:
            return None

    # send msg directly until send_buff overflow, and put the msg to msgq
    # return value same as read on success
    # return None on error
    def send(self, user_fd, msg, ip_port=None):
        try:
            msg = str(msg)
            if user_fd in self.user_fd_to_socket_fd.keys():
                fileno = self.user_fd_to_socket_fd[user_fd]
                attr = self.socket_fileno_info[fileno]
                if attr['type'] == self.TCP_MODE and attr['server_flag'] != True:
                    return attr['socket'].send(msg.encode('utf-8'))
                elif attr['type'] == self.UDP_MODE:
                    if ip_port:
                        return attr['socket'].sendto(msg.encode('utf-8'), ip_port)
                    elif 'default_target_addr' in attr.keys() and attr['default_target_addr']:
                        ip_port = attr['default_target_addr']
                        return attr['socket'].sendto(msg.encode('utf-8'), ip_port)
                    else:
                        logger.error('no target ip and port, cur msg is %s' % (msg.encode('utf-8')))
                        return None
        except socket.error as e:
            if e.errno == errno.EAGAIN:
                if not attr['send_msgq'].full():
                    attr['send_msgq'].put((msg, ip_port))
            else:
                logger.fatal(traceback.format_exc())
        except Exception as e:
            logger.fatal(traceback.format_exc())

    # get msg data from msg_queue
    # the msg recv from socket will be putted into msg_queue if there is no recv_callback
    def recv(self, user_fd):
        if user_fd in self.user_fd_to_socket_fd.keys():
            fileno = self.user_fd_to_socket_fd[user_fd]
            msg_queue = self.socket_fileno_info[fileno]['recv_msgq']
            if not msg_queue.empty():
                msg = msg_queue.get()
                return msg
            else:
                return None

    def get_status(self):
        pass

    def get_local_host_ip(self, user_fd=None):
        if user_fd in self.user_fd_to_socket_fd.keys():
            socket = self.socket_fileno_info[self.user_fd_to_socket_fd[user_fd]]['socket']
            try:
                return socket.getsockname()[0]
            except Exception as e:
                logger.error(traceback.format_exc())
                return None
        else:
            ifconfig_pipe = subprocess.Popen(['busybox', 'ifconfig', 'wlan0'],
                                             stdout=subprocess.PIPE,
                                             stderr=subprocess.PIPE,
                                             )
            ifconfig_info, error = ifconfig_pipe.communicate()
            ifconfig_pipe.kill()

            if len(error) != 0:
                # get wlan0 error
                return None

            ifconfig_info = ifconfig_info.decode('utf-8')

            inet_addr_str = ifconfig_info.split('\n')[1]

            local_host_ip = None
            if 'inet addr' in inet_addr_str:
                local_host_ip = inet_addr_str.split(':')[1].split(' ')[0]

            return local_host_ip

    def get_remote_host_ip(self, user_fd):
        if user_fd in self.user_fd_to_socket_fd.keys():
            socket = self.socket_fileno_info[self.user_fd_to_socket_fd[user_fd]]['socket']
            try:
                return socket.getpeername()[0]
            except Exception as e:
                logger.error(traceback.format_exc())
        else:
            return None

    def set_udp_default_target_addr(self, user_fd, ip_port):
        if user_fd in self.user_fd_to_socket_fd.keys():
            fileno = self.user_fd_to_socket_fd[user_fd]
            if ip_port[0] == '<broadcast>':
                self.socket_fileno_info[fileno]['socket'].setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                self.socket_fileno_info[fileno]['socket'].setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.socket_fileno_info[fileno]['default_target_addr'] = ip_port

    def update_socket_info(self, user_fd, recv_msgq_size=None, send_msgq_size=None, connected_callback=None,
                           disconnected_callback=None, recv_callback=None, send_callback=None):
        if user_fd in self.user_fd_to_socket_fd.keys():
            fileno = self.user_fd_to_socket_fd[user_fd]
            if recv_msgq_size:
                self.socket_fileno_info[fileno]['recv_msgq'] = queue.Queue(recv_msgq_size)
            if send_msgq_size:
                self.socket_fileno_info[fileno]['send_msgq'] = queue.Queue(send_msgq_size)
            if connected_callback and callable(connecti_calback):
                self.socket_fileno_info[fileno]['callback']['connected_callback'] = connected_callback
            if disconnected_callback and callable(disconnected_calback):
                self.socket_fileno_info[fileno]['callback']['disconnected_callback'] = disconnected_callback
            if recv_callback and callable(recv_callback):
                self.socket_fileno_info[fileno]['callback']['recv_callback'] = recv_callback
            if send_callback and callable(send_callback):
                self.socket_fileno_info[fileno]['callback']['send_callback'] = send_callback

    def __add_socket_fileno_info(self, fd, type, server=False, recv_msgq_size=1, send_msgq_size=1, **callback):
        # update fd
        self.user_fd += 1
        fd_t = self.user_fd

        # maping custom fd and real fd
        self.user_fd_to_socket_fd[self.user_fd] = fd.fileno()

        fd.setblocking(False)

        self.epoll_obj.register(fd.fileno(), select.EPOLLIN | select.EPOLLET)

        self.socket_fileno_info[fd.fileno()] = {
            'socket': fd,
            'user_fd': self.user_fd,
            'type': type,
            'server_flag': server,
            'callback': None,
            'recv_msgq': queue.Queue(recv_msgq_size),
            'send_msgq': queue.Queue(send_msgq_size)
        }

        callback_dict = {}

        if 'connected_callback' in callback.keys() and callable(callback['connected_callback']):
            callback_dict['connected_callback'] = callback['connected_callback']
        if 'disconnected_callback' in callback.keys() and callable(callback['disconnected_callback']):
            callback_dict['disconnected_callback'] = callback['disconnected_callback']
        if 'recv_callback' in callback.keys() and callable(callback['recv_callback']):
            callback_dict['recv_callback'] = callback['recv_callback']
        if 'send_callback' in callback.keys() and callable(callback['send_callback']):
            callback_dict['send_callback'] = callback['send_callback']

        self.socket_fileno_info[fd.fileno()]['callback'] = callback_dict
        logger.info('NEW SOCKET %s' % fd)

        return fd_t

    def __remove_socket_fileno_info(self, fileno):
        if fileno in self.socket_fileno_info.keys():
            socket = self.socket_fileno_info[fileno]['socket']
            socket.close()
            if self.socket_fileno_info[fileno]['user_fd'] in self.user_fd_to_socket_fd.keys():
                self.user_fd_to_socket_fd.pop(self.socket_fileno_info[fileno]['user_fd'])
            self.socket_fileno_info.pop(fileno)

    def __create_tcp_server(self, ip_port, recv_msgq_size, send_msgq_size, **callback):
        try:
            fd = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            fd.bind(ip_port)

            fd_t = self.__add_socket_fileno_info(fd, self.TCP_MODE, server=True, recv_msgq_size=recv_msgq_size,
                                                 send_msgq_size=send_msgq_size, **callback)

            fd.listen()

            return fd_t
        except Exception as e:
            logger.fatal(traceback.format_exc())
            return None

    def __create_tcp_client(self, ip_port, recv_msgq_size, send_msgq_size, **callback):
        try:
            fd = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            fd.bind(ip_port)

            fd_t = self.__add_socket_fileno_info(fd, self.TCP_MODE, server=False, recv_msgq_size=recv_msgq_size,
                                                 send_msgq_size=send_msgq_size, **callback)

            fd.connect()
            return fd_t

        except Exception as e:
            logger.fatal(traceback.format_exc())
            return None

    def __create_udp_server(self, ip_port, recv_msgq_size, send_msgq_size, **callback):
        try:
            fd = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

            fd.bind(ip_port)

            fd_t = self.__add_socket_fileno_info(fd, self.UDP_MODE, server=True, recv_msgq_size=recv_msgq_size,
                                                 send_msgq_size=send_msgq_size, **callback)

            return fd_t
        except Exception as e:
            logger.fatal(traceback.format_exc())
            return fd

    def __create_udp_client(self, ip_port, recv_msgq_size, send_msgq_size, **callback):
        try:
            fd = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            fd_t = self.__add_socket_fileno_info(fd, self.UDP_MODE, server=False, recv_msgq_size=recv_msgq_size,
                                                 send_msgq_size=send_msgq_size, **callback)

            return fd_t
        except Exception as e:
            logger.fatal(traceback.format_exc())
            return fd

    def __register_connected_cb(self):
        pass

    def __register_disconnected_cb(self):
        pass

    def __register_recv_cb(self):
        pass

    def __register_send_cb(self):
        pass

    def __epoll_task(self):
        timeout = -1
        while not self.recv_thread_finish:
            events = self.epoll_obj.poll(timeout)

            if not events:
                continue

            for fd_fileno, event in events:

                cur_socket_info = self.socket_fileno_info[fd_fileno]

                # tcp
                if cur_socket_info['type'] == self.TCP_MODE:

                    # check if new connection or not
                    if cur_socket_info['server_flag'] == True:
                        # ET mode, loop accept until raise exception
                        while True:
                            try:
                                conn, addr = cur_socket_info['socket'].accept()

                                fd_t = self.__add_socket_fileno_info(conn, self.TCP_MODE, server=False,
                                                                     **self.socket_fileno_info[fd_fileno]['callback'])

                                if 'connected_callback' in self.socket_fileno_info[fd_fileno]['callback'].keys():
                                    # connected callback (conn_addr, new_user_fd)
                                    self.socket_fileno_info[fd_fileno]['callback']['connected_callback'](
                                        self.socket_fileno_info[fd_fileno]['user_fd'], fd_t)
                                logger.info('NEW CONNECTION %s %s' % (conn, addr))
                            except socket.error as e:
                                if e.errno == errno.EAGAIN:
                                    logger.info("NO NEW CONNECTION ALSO")
                                else:
                                    logger.fatal(traceback.format_exc())
                                break
                            except Exception as e:
                                logger.fatal(traceback.format_exc())
                                break
                    else:
                        if event & select.EPOLLHUP:
                            if 'disconnected_callback' in self.socket_fileno_info[fd_fileno]['callback'].keys():
                                self.socket_fileno_info[fd_fileno]['callback']['disconnected_callback'](
                                    self.socket_fileno_info[fd_fileno]['user_fd'])
                            self.__remove_socket_fileno_info(fd_fileno)
                            self.epoll_obj.unregister(fd_fileno)
                        # Read available
                        elif event & select.EPOLLIN:
                            buff = b''
                            # loop read until raise exception
                            while True:
                                try:
                                    recv_buff = cur_socket_info['socket'].recv(2048)
                                    buff += recv_buff

                                    # connection disconnected
                                    if not recv_buff:
                                        logger.info('connection disconnected')
                                        if 'disconnected_callback' in self.socket_fileno_info[fd_fileno][
                                            'callback'].keys():
                                            self.socket_fileno_info[fd_fileno]['callback']['disconnected_callback'](
                                                self.socket_fileno_info[fd_fileno]['user_fd'])
                                        self.__remove_socket_fileno_info(fd_fileno)
                                        self.epoll_obj.unregister(fd_fileno)
                                    break
                                except socket.error as e:
                                    if e.errno == errno.EAGAIN:
                                        logger.info('READ DATA EAGAIN ERROR')
                                    else:
                                        logger.fatal(traceback.format_exc())
                                    break
                                except Exception as e:
                                    logger.fatal(traceback.format_exc())
                                    break

                            if recv_buff:
                                if 'recv_callback' in cur_socket_info['callback'].keys():
                                    # recv callback (user_fd, msg)
                                    cur_socket_info['callback']['recv_callback'](cur_socket_info['user_fd'],
                                                                                 recv_buff.decode('utf-8'))
                                else:
                                    # put data in msg_queue if no recv_callback
                                    if cur_socket_info['recv_msgq'].full():
                                        cur_socket_info['recv_msgq'].get()
                                    cur_socket_info['recv_msgq'].put((addr, recv_buff.decode('utf-8')))
                        # Write available
                        elif event & select.EPOLLOUT:
                            send_info = None
                            while self.socket_fileno_info[fd_fileno]['send_msgq'].qsize() > 0:
                                send_info = self.socket_fileno_info[fd_fileno]['send_msgq'].get()
                                if send_info == None:
                                    continue
                                if 'send_callback' in cur_socket_info['callback'].keys():
                                    # send callback (user_fd, msg)
                                    cur_socket_info['callback']['send_callback'](cur_socket_info['user_fd'],
                                                                                 send_info[0])
                                try:
                                    cur_socket_info['socket'].send(send_info[0].encode('utf-8'))
                                except Exception as e:
                                    logger.fatal(traceback.format_exc())
                                    # send error, reput the msg to msgq
                                    # lost the msgq if the queue full
                                    if not self.socket_fileno_info[fd_fileno]['send_msgq'].full():
                                        self.socket_fileno_info[fd_fileno]['send_msgq'].put(send_info)
                                    break
                            # set fd status to read
                            self.epoll_obj.modify(fd_fileno, select.EPOLLIN)
                        else:
                            # reset fd status
                            self.epoll_obj.modify(fd_fileno, 0)
                # udp
                elif cur_socket_info['type'] == self.UDP_MODE:
                    if event & select.EPOLLOUT:
                        send_info = None
                        while self.socket_fileno_info[fd_fileno]['send_msgq'].qsize() > 0:
                            send_info = self.socket_fileno_info[fd_fileno]['send_msgq'].get()
                            if send_info == None:
                                continue
                            ip_port = send_info[1]
                            if ip_port == None and 'default_target_addr' in cur_socket_info.keys():
                                ip_port = cur_socket_info['default_target_addr']
                            if 'send_callback' in cur_socket_info['callback'].keys():
                                # send callback (user_fd, msg)
                                cur_socket_info['callback']['send_callback'](cur_socket_info['user_id'], send_info[0])
                            try:
                                cur_socket_info['socket'].sendto(send_info[0].encode('utf-8'), ip_port)
                            except Exception as e:
                                if e.errno == errno.EAGAIN:
                                    logger.info('WRITE DATA EAGAIN ERROR')
                                else:
                                    logger.fatal(traceback.format_exc())
                                # send error, reput the msg to msgq
                                # lost the msgq if the queue full
                                if not self.socket_fileno_info[fd_fileno]['send_msgq'].full():
                                    self.socket_fileno_info[fd_fileno]['send_msgq'].put(send_info)
                                break

                        # set fd status to read
                        self.epoll_obj.modify(fd_fileno, select.EPOLLIN)
                    elif event & select.EPOLLIN:
                        buff = b''
                        while True:
                            try:
                                recv_buff, addr = cur_socket_info['socket'].recvfrom(2048)
                                buff += recv_buff
                            except socket.error as e:
                                if e.errno == errno.EAGAIN:
                                    logger.info('RECV DATA EAGAIN ERROR')
                                else:
                                    logger.fatal(traceback.format_exc())
                                break
                            except Exception as e:
                                logger.fatal(traceback.format_exc())
                                break
                        if buff:
                            if 'recv_callback' in cur_socket_info['callback'].keys():
                                # recv callback (recv_addr, user_fd, msg)
                                cur_socket_info['callback']['recv_callback'](addr, cur_socket_info['user_fd'],
                                                                             buff.decode('utf-8'))
                            else:
                                # remove oldest data if the msg_queue full
                                if cur_socket_info['recv_msgq'].full():
                                    cur_socket_info['recv_msgq'].get()
                                cur_socket_info['recv_msgq'].put((addr, recv_buff.decode('utf-8')))
                    else:
                        # reset fd status
                        self.epoll_obj.modify(fd_fileno, 0)
                else:
                    logger.info('KNOW SOCKET %s' % (cur_socket_info))
        logger.info('exit')
